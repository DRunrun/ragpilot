package com.ragpilot.bootstrap.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.bootstrap.ingest.SpringAiVectorStoreWriter;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import com.ragpilot.core.retrieval.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词召回器：用 Postgres 全文检索（tsvector + ts_rank）做 BM25 近似。
 *
 * <p>链路位置：问答链路第二环的<b>第二路召回</b>（F2.1）。M1 向量路抓语义；
 * 本路补精确词（类名如 {@code BeanPostProcessor}）。F2.2 会用 RRF 融合两路。
 *
 * <p>为什么不用 Spring AI VectorStore：它只暴露向量相似度 API，没有全文检索端口；
 * 直接 JDBC 查 {@code content_tsv} 列，保持适配器边界清晰。
 *
 * <p>分数口径：{@code ts_rank}，越大越相关；channel 标记为 {@link RetrievedChunk.Channel#BM25}。
 */
public class PgFullTextRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(PgFullTextRetriever.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * 全文检索 SQL：simple 配置 + plainto_tsquery。
     * 命中文本优先 rawContent，避免把 search_document: 前缀带进 Prompt。
     */
    private static final String SEARCH_SQL = """
            SELECT id::text AS id,
                   coalesce(metadata->>'rawContent', content) AS body,
                   metadata::text AS metadata_json,
                   ts_rank(content_tsv, plainto_tsquery('simple', ?)) AS rank_score
            FROM vector_store
            WHERE content_tsv @@ plainto_tsquery('simple', ?)
            ORDER BY rank_score DESC
            LIMIT ?
            """;

    private static final String SEARCH_SQL_KB = """
            SELECT id::text AS id,
                   coalesce(metadata->>'rawContent', content) AS body,
                   metadata::text AS metadata_json,
                   ts_rank(content_tsv, plainto_tsquery('simple', ?)) AS rank_score
            FROM vector_store
            WHERE content_tsv @@ plainto_tsquery('simple', ?)
              AND metadata->>'knowledgeBaseId' = ANY(?)
            ORDER BY rank_score DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PgFullTextRetriever(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 关键词召回。
     *
     * @param query 用户问题或精确词；空白时直接返回空列表
     * @param topK  最多返回条数
     * @return BM25 通道命中；无匹配返回空列表
     */
    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    /**
     * @param kbIds 限定知识库；null/空表示不限制
     */
    public List<RetrievedChunk> retrieve(String query, int topK, List<String> kbIds) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int k = Math.max(topK, 1);
        String q = query.strip();

        List<RetrievedChunk> rows;
        if (kbIds == null || kbIds.isEmpty()) {
            rows = jdbcTemplate.query(SEARCH_SQL, keywordRowMapper(), q, q, k);
        } else {
            rows = jdbcTemplate.query(con -> {
                var ps = con.prepareStatement(SEARCH_SQL_KB);
                ps.setString(1, q);
                ps.setString(2, q);
                ps.setArray(3, con.createArrayOf("varchar", kbIds.toArray()));
                ps.setInt(4, k);
                return ps;
            }, keywordRowMapper());
        }

        List<RetrievedChunk> ranked = new ArrayList<>(rows.size());
        int rank = 1;
        for (RetrievedChunk hit : rows) {
            ranked.add(new RetrievedChunk(hit.chunk(), hit.score(), rank++, hit.channel()));
        }

        if (ranked.isEmpty()) {
            log.info("Full-text retrieval: no hits for query='{}'", q);
        } else {
            log.info("Full-text retrieval: query='{}' → {} hits, topScore={}",
                    q, ranked.size(), ranked.get(0).score());
        }
        return List.copyOf(ranked);
    }

    private RowMapper<RetrievedChunk> keywordRowMapper() {
        return (rs, rowNum) -> {
            String body = rs.getString("body");
            Map<String, String> meta = parseMetadata(rs.getString("metadata_json"));
            String chunkId = meta.getOrDefault(
                    SpringAiVectorStoreWriter.CHUNK_ID_KEY,
                    rs.getString("id"));
            String docId = meta.getOrDefault("docId",
                    chunkId.contains("#") ? chunkId.substring(0, chunkId.indexOf('#')) : chunkId);
            if (!meta.containsKey(SpringAiVectorStoreWriter.RAW_CONTENT_KEY) && body != null) {
                meta = new LinkedHashMap<>(meta);
                meta.put(SpringAiVectorStoreWriter.RAW_CONTENT_KEY, body);
            }
            TextChunk chunk = new TextChunk(
                    chunkId,
                    docId,
                    body == null ? "" : body,
                    parseIndex(chunkId),
                    Map.copyOf(meta));
            double score = rs.getDouble("rank_score");
            // rank 先占位 0，retrieve() 里按结果顺序统一编号
            return new RetrievedChunk(chunk, score, 0, RetrievedChunk.Channel.BM25);
        };
    }

    private Map<String, String> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, MAP_TYPE);
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (v != null) {
                    result.put(k, String.valueOf(v));
                }
            });
            return result;
        } catch (Exception e) {
            log.debug("Failed to parse metadata JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private static int parseIndex(String chunkId) {
        int hash = chunkId.lastIndexOf('#');
        if (hash < 0 || hash == chunkId.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(chunkId.substring(hash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
