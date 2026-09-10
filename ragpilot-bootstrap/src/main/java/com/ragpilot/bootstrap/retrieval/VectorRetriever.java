package com.ragpilot.bootstrap.retrieval;

import com.ragpilot.bootstrap.ingest.SpringAiVectorStoreWriter;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import com.ragpilot.core.retrieval.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量检索器：Retriever 端口的 M1 唯一实现，基于 Spring AI PGVector 相似度搜索。
 *
 * <p>链路位置：问答链路第二环（bootstrap 适配器）。
 *
 * <p>minScore 过滤放在这里而不是 PromptBuilder/RefusalPolicy：
 * 低于阈值的命中在语义上等于「没召回」，尽早丢弃可以让拒答判断、日志与 prompt 三处口径一致。
 *
 * <p>分数口径：PGVector 用 cosine_distance，Spring AI 归一为相似度（越大越相关）。
 */
public class VectorRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(VectorRetriever.class);

    private final VectorStore vectorStore;

    /** 最低相似度阈值，来自 ragpilot.retrieval.min-score；低于它的命中视为未召回。 */
    private final double minScore;

    public VectorRetriever(VectorStore vectorStore, double minScore) {
        this.vectorStore = vectorStore;
        this.minScore = minScore;
    }

    /**
     * 执行向量检索并过滤低分命中。
     *
     * @param query 用户问题原文
     * @param topK  最多返回条数，非法值（&lt;=0）按 1 处理，宁可少召回不崩链路
     * @return 按分数降序的命中列表；库空或全低分时为空列表
     */
    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    /**
     * 带元数据过滤的向量检索（ADM-3.5：按 knowledgeBaseId）。
     *
     * @param filterExpression Spring AI 过滤表达式，如 {@code knowledgeBaseId == 'default'}；null=不限
     */
    public List<RetrievedChunk> retrieve(String query, int topK, String filterExpression) {
        int k = Math.max(topK, 1);
        // nomic-embed 查询侧必须带 search_query: 前缀，与入库侧 search_document: 配对
        String embedQuery = "search_query: " + query;
        // similarityThreshold 即 minScore：PGVector 侧直接过滤，少传数据
        var builder = SearchRequest.builder()
                .query(embedQuery)
                .topK(k)
                .similarityThreshold(minScore);
        if (filterExpression != null && !filterExpression.isBlank()) {
            builder.filterExpression(filterExpression);
        }
        SearchRequest request = builder.build();

        List<Document> docs = vectorStore.similaritySearch(request);
        if (docs == null || docs.isEmpty()) {
            log.info("Vector retrieval: no hits for query='{}' (minScore={})", query, minScore);
            return List.of();
        }

        List<RetrievedChunk> hits = new ArrayList<>(docs.size());
        int rank = 1;
        for (Document doc : docs) {
            // Spring AI 把相似度放在 getScore()，null 表示底层未返回分数，按 0 兜底
            double score = doc.getScore() == null ? 0.0 : doc.getScore();
            TextChunk chunk = toTextChunk(doc);
            hits.add(new RetrievedChunk(chunk, score, rank++, RetrievedChunk.Channel.VECTOR));
            log.debug("Hit rank={} score={} chunkId={}", rank - 1, score, chunk.id());
        }
        log.info("Vector retrieval: query='{}' → {} hits, topScore={}",
                query, hits.size(), hits.get(0).score());
        return List.copyOf(hits);
    }

    /**
     * Spring AI Document → 领域 TextChunk。
     *
     * <p>业务 chunkId/docId 在 metadata 里（Document.id 是 PG 侧 UUID）；
     * 兼容旧数据：缺失 chunkId 时再回退到 Document.id。
     */
    private static TextChunk toTextChunk(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        Object chunkIdObj = meta.get(SpringAiVectorStoreWriter.CHUNK_ID_KEY);
        String chunkId = chunkIdObj != null ? String.valueOf(chunkIdObj) : doc.getId();
        String docId = String.valueOf(meta.getOrDefault("docId",
                chunkId.contains("#") ? chunkId.substring(0, chunkId.indexOf('#')) : chunkId));
        // 优先用入库时保存的原文，避免把 search_document: 前缀带进 Prompt
        Object raw = meta.get(SpringAiVectorStoreWriter.RAW_CONTENT_KEY);
        String content = raw != null ? String.valueOf(raw) : stripDocumentPrefix(doc.getText());
        return new TextChunk(chunkId, docId, content, parseIndex(chunkId), toStringMap(meta));
    }

    /** 兼容旧数据：没有 rawContent 时剥掉入库前缀。 */
    private static String stripDocumentPrefix(String text) {
        if (text != null && text.startsWith(SpringAiVectorStoreWriter.DOCUMENT_PREFIX)) {
            return text.substring(SpringAiVectorStoreWriter.DOCUMENT_PREFIX.length());
        }
        return text;
    }

    /** 从 {@code docId#序号} 解析块序号；格式不符返回 -1。 */
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

    /** Spring AI 的 metadata 值类型放宽为 Object；我们只放 String，安全窄化回 String 映射。 */
    private static Map<String, String> toStringMap(Map<String, Object> meta) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        meta.forEach((k, v) -> {
            if (v != null) {
                result.put(k, String.valueOf(v));
            }
        });
        return result;
    }
}
