package com.ragpilot.bootstrap.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量表运维：按知识库/文档删块、统计、块预览（ADM-3 / ADM-3.8）。
 *
 * <p>链路位置：Admin 知识库级联与向量库页；直接 JDBC 操作 {@code vector_store.metadata}。
 */
@Repository
public class VectorChunkStore {

    public static final String META_KB = "knowledgeBaseId";
    public static final String META_DOC = "docId";

    private final JdbcTemplate jdbcTemplate;

    public VectorChunkStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int deleteByDoc(String knowledgeBaseId, String docId) {
        return jdbcTemplate.update(
                """
                        DELETE FROM vector_store
                        WHERE metadata->>? = ? AND metadata->>? = ?
                        """,
                META_KB, knowledgeBaseId, META_DOC, docId);
    }

    public int deleteByKnowledgeBase(String knowledgeBaseId) {
        return jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>? = ?",
                META_KB, knowledgeBaseId);
    }

    public int deleteAll() {
        return jdbcTemplate.update("DELETE FROM vector_store");
    }

    /** 把缺失 knowledgeBaseId 的旧块标成 default（ADM-3.6）。 */
    public int migrateMissingKbToDefault() {
        return jdbcTemplate.update(
                """
                        UPDATE vector_store
                        SET metadata = jsonb_set(
                            coalesce(metadata, '{}'::jsonb),
                            '{knowledgeBaseId}',
                            '"default"',
                            true
                        )
                        WHERE metadata->>'knowledgeBaseId' IS NULL
                           OR metadata->>'knowledgeBaseId' = ''
                        """);
    }

    public long countAll() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Long.class);
        return n == null ? 0L : n;
    }

    public List<Map<String, Object>> countByKb() {
        return jdbcTemplate.queryForList(
                """
                        SELECT coalesce(metadata->>'knowledgeBaseId', '(missing)') AS knowledge_base_id,
                               COUNT(*)::bigint AS chunk_count
                        FROM vector_store
                        GROUP BY 1
                        ORDER BY chunk_count DESC
                        """);
    }

    public List<Map<String, Object>> countByDoc(String knowledgeBaseId) {
        return jdbcTemplate.queryForList(
                """
                        SELECT metadata->>'docId' AS doc_id,
                               COUNT(*)::bigint AS chunk_count
                        FROM vector_store
                        WHERE metadata->>'knowledgeBaseId' = ?
                        GROUP BY 1
                        ORDER BY chunk_count DESC
                        """,
                knowledgeBaseId);
    }

    public List<Map<String, Object>> listChunks(String knowledgeBaseId, String docId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 500);
        return jdbcTemplate.query(
                """
                        SELECT id::text AS id,
                               metadata->>'chunkId' AS chunk_id,
                               coalesce(metadata->>'rawContent', content) AS content,
                               length(coalesce(metadata->>'rawContent', content)) AS length
                        FROM vector_store
                        WHERE metadata->>'knowledgeBaseId' = ?
                          AND metadata->>'docId' = ?
                        ORDER BY metadata->>'chunkId'
                        LIMIT ?
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("chunkId", rs.getString("chunk_id"));
                    row.put("content", rs.getString("content"));
                    row.put("length", rs.getInt("length"));
                    return row;
                },
                knowledgeBaseId, docId, lim);
    }

    public List<Map<String, Object>> inspectByDocId(String docId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        List<Map<String, Object>> rows = jdbcTemplate.query(
                """
                        SELECT id::text AS id,
                               metadata->>'knowledgeBaseId' AS knowledge_base_id,
                               metadata->>'chunkId' AS chunk_id,
                               left(coalesce(metadata->>'rawContent', content), 400) AS preview,
                               length(coalesce(metadata->>'rawContent', content)) AS length
                        FROM vector_store
                        WHERE metadata->>'docId' = ?
                        ORDER BY metadata->>'chunkId'
                        LIMIT ?
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("knowledgeBaseId", rs.getString("knowledge_base_id"));
                    row.put("chunkId", rs.getString("chunk_id"));
                    row.put("preview", rs.getString("preview"));
                    row.put("length", rs.getInt("length"));
                    return row;
                },
                docId, lim);
        return new ArrayList<>(rows);
    }
}
