package com.ragpilot.bootstrap.admin;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库 CRUD（ADM-3.1）：业务侧库表，与向量 metadata.knowledgeBaseId 对齐。
 *
 * <p>链路位置：Admin 知识库主入口；删库时级联文档元数据 + 向量块。
 */
@Service
public class KnowledgeBaseService {

    public static final String DEFAULT_ID = "default";

    private final JdbcTemplate jdbcTemplate;
    private final VectorChunkStore vectorChunkStore;

    public KnowledgeBaseService(JdbcTemplate jdbcTemplate, VectorChunkStore vectorChunkStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorChunkStore = vectorChunkStore;
    }

    public record KnowledgeBaseView(
            String id,
            String name,
            String description,
            boolean enabled,
            long documentCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    private final RowMapper<KnowledgeBaseView> mapper = (rs, i) -> new KnowledgeBaseView(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getBoolean("enabled"),
            rs.getLong("document_count"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
    );

    public List<KnowledgeBaseView> list() {
        return jdbcTemplate.query(
                """
                        SELECT kb.id, kb.name, kb.description, kb.enabled,
                               kb.created_at, kb.updated_at,
                               (SELECT COUNT(*) FROM knowledge_document d
                                 WHERE d.knowledge_base_id = kb.id) AS document_count
                        FROM knowledge_base kb
                        ORDER BY CASE WHEN kb.id = 'default' THEN 0 ELSE 1 END, kb.created_at
                        """,
                mapper);
    }

    public Optional<KnowledgeBaseView> get(String id) {
        List<KnowledgeBaseView> rows = jdbcTemplate.query(
                """
                        SELECT kb.id, kb.name, kb.description, kb.enabled,
                               kb.created_at, kb.updated_at,
                               (SELECT COUNT(*) FROM knowledge_document d
                                 WHERE d.knowledge_base_id = kb.id) AS document_count
                        FROM knowledge_base kb
                        WHERE kb.id = ?
                        """,
                mapper, id);
        return rows.stream().findFirst();
    }

    public KnowledgeBaseView create(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String id = "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO knowledge_base(id, name, description, enabled, created_at, updated_at)
                            VALUES (?, ?, ?, TRUE, NOW(), NOW())
                            """,
                    id, name.strip(), description);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("知识库名称已存在: " + name);
        }
        return get(id).orElseThrow();
    }

    public KnowledgeBaseView patch(String id, String name, String description, Boolean enabled) {
        KnowledgeBaseView current = get(id).orElseThrow(() -> new NotFoundException("知识库不存在: " + id));
        String newName = name != null ? name.strip() : current.name();
        String newDesc = description != null ? description : current.description();
        boolean newEnabled = enabled != null ? enabled : current.enabled();
        if (newName.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        try {
            jdbcTemplate.update(
                    """
                            UPDATE knowledge_base
                            SET name = ?, description = ?, enabled = ?, updated_at = NOW()
                            WHERE id = ?
                            """,
                    newName, newDesc, newEnabled, id);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("知识库名称已存在: " + newName);
        }
        return get(id).orElseThrow();
    }

    /**
     * 删除知识库。内置 default 禁止删除；须 confirm=DELETE_KB。
     */
    @Transactional
    public Map<String, Object> delete(String id, String confirm) {
        if (DEFAULT_ID.equals(id)) {
            throw new IllegalArgumentException("内置默认知识库不可删除");
        }
        if (!"DELETE_KB".equals(confirm)) {
            throw new IllegalArgumentException("删除须传 confirm=DELETE_KB");
        }
        if (get(id).isEmpty()) {
            throw new NotFoundException("知识库不存在: " + id);
        }
        int vectors = vectorChunkStore.deleteByKnowledgeBase(id);
        int docs = jdbcTemplate.update("DELETE FROM knowledge_document WHERE knowledge_base_id = ?", id);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("deletedDocuments", docs);
        result.put("deletedVectors", vectors);
        return result;
    }

    /** 参与默认检索的启用库 ID 列表。 */
    public List<String> listEnabledIds() {
        return jdbcTemplate.query(
                "SELECT id FROM knowledge_base WHERE enabled = TRUE ORDER BY id",
                (rs, i) -> rs.getString("id"));
    }

    public boolean exists(String id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /** 资源不存在。 */
    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
