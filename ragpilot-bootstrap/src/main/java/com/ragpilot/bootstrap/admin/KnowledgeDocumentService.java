package com.ragpilot.bootstrap.admin;

import com.ragpilot.core.domain.SourceDocument;
import com.ragpilot.core.ingestion.DocumentParser;
import com.ragpilot.core.ingestion.IngestReport;
import com.ragpilot.core.ingestion.IngestionPipeline;
import com.ragpilot.core.ingestion.MarkdownParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 知识库文档：上传/samples 摄入、重灌、删除、块预览（ADM-3.2～3.4）。
 *
 * <p>链路位置：Admin 知识库 → 解析/分块 → 向量写入（metadata 带 knowledgeBaseId）。
 */
@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestionPipeline ingestionPipeline;
    private final VectorChunkStore vectorChunkStore;
    private final DocumentParser parser = new MarkdownParser();

    public KnowledgeDocumentService(
            JdbcTemplate jdbcTemplate,
            KnowledgeBaseService knowledgeBaseService,
            IngestionPipeline ingestionPipeline,
            VectorChunkStore vectorChunkStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeBaseService = knowledgeBaseService;
        this.ingestionPipeline = ingestionPipeline;
        this.vectorChunkStore = vectorChunkStore;
    }

    public record DocumentView(
            String id,
            String knowledgeBaseId,
            String docId,
            String title,
            String sourceUri,
            String status,
            int chunkCount,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    private final RowMapper<DocumentView> mapper = (rs, i) -> new DocumentView(
            rs.getString("id"),
            rs.getString("knowledge_base_id"),
            rs.getString("doc_id"),
            rs.getString("title"),
            rs.getString("source_uri"),
            rs.getString("status"),
            rs.getInt("chunk_count"),
            rs.getString("error_message"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
    );

    public List<DocumentView> list(String kbId) {
        requireKb(kbId);
        return jdbcTemplate.query(
                """
                        SELECT id, knowledge_base_id, doc_id, title, source_uri, status,
                               chunk_count, error_message, created_at, updated_at
                        FROM knowledge_document
                        WHERE knowledge_base_id = ?
                        ORDER BY updated_at DESC
                        """,
                mapper, kbId);
    }

    public Optional<DocumentView> get(String kbId, String docId) {
        List<DocumentView> rows = jdbcTemplate.query(
                """
                        SELECT id, knowledge_base_id, doc_id, title, source_uri, status,
                               chunk_count, error_message, created_at, updated_at
                        FROM knowledge_document
                        WHERE knowledge_base_id = ? AND doc_id = ?
                        """,
                mapper, kbId, docId);
        return rows.stream().findFirst();
    }

    /**
     * 上传摄入（WebFlux 侧先读成字节再调本方法，避免依赖 servlet MultipartFile）。
     *
     * @param filename 原始文件名，用于扩展名校验与 docId
     * @param bytes    文件内容；空或超 5MB 拒绝
     */
    @Transactional
    public DocumentView upload(String kbId, String filename, byte[] bytes) {
        requireKb(kbId);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (bytes.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("文件超过 5MB 上限");
        }
        String original = (filename == null || filename.isBlank()) ? "upload.md" : filename;
        String lower = original.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".markdown"))) {
            throw new IllegalArgumentException("仅支持 .md / .txt");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        String baseName = stripExt(original);
        String docId = sanitizeDocId(baseName);
        return ingestText(kbId, docId, original, text, original);
    }

    @Transactional
    public List<DocumentView> ingestSamples(String kbId) {
        requireKb(kbId);
        Path samplesDir = Path.of("samples");
        if (!Files.isDirectory(samplesDir)) {
            throw new IllegalArgumentException("samples/ 目录不存在");
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(samplesDir)) {
            files = stream.filter(parser::supports).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("无法列出 samples/: " + e.getMessage());
        }
        List<DocumentView> results = new ArrayList<>();
        for (Path file : files) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String docId = sanitizeDocId(stripExt(file.getFileName().toString()));
                results.add(ingestText(kbId, docId, file.getFileName().toString(), text, file.toString()));
            } catch (Exception e) {
                log.warn("Sample ingest failed {}: {}", file, e.toString());
            }
        }
        return results;
    }

    @Transactional
    public DocumentView reingest(String kbId, String docId) {
        requireKb(kbId);
        String content = jdbcTemplate.query(
                """
                        SELECT content_text FROM knowledge_document
                        WHERE knowledge_base_id = ? AND doc_id = ?
                        """,
                rs -> rs.next() ? rs.getString(1) : null,
                kbId, docId);
        if (content == null || content.isBlank()) {
            throw new KnowledgeBaseService.NotFoundException(
                    "文档不存在或无正文可重灌: " + docId);
        }
        DocumentView current = get(kbId, docId).orElseThrow();
        return ingestText(kbId, docId, current.title(), content, current.sourceUri());
    }

    @Transactional
    public Map<String, Object> delete(String kbId, String docId) {
        requireKb(kbId);
        if (get(kbId, docId).isEmpty()) {
            throw new KnowledgeBaseService.NotFoundException("文档不存在: " + docId);
        }
        int vectors = vectorChunkStore.deleteByDoc(kbId, docId);
        int n = jdbcTemplate.update(
                "DELETE FROM knowledge_document WHERE knowledge_base_id = ? AND doc_id = ?",
                kbId, docId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBaseId", kbId);
        result.put("docId", docId);
        result.put("deleted", n > 0);
        result.put("deletedVectors", vectors);
        return result;
    }

    public List<Map<String, Object>> listChunks(String kbId, String docId) {
        requireKb(kbId);
        if (get(kbId, docId).isEmpty()) {
            throw new KnowledgeBaseService.NotFoundException("文档不存在: " + docId);
        }
        return vectorChunkStore.listChunks(kbId, docId, 500);
    }

    private DocumentView ingestText(
            String kbId,
            String docId,
            String title,
            String text,
            String sourceUri
    ) {
        String rowId = jdbcTemplate.query(
                """
                        SELECT id FROM knowledge_document
                        WHERE knowledge_base_id = ? AND doc_id = ?
                        """,
                rs -> rs.next() ? rs.getString(1) : null,
                kbId, docId);
        if (rowId == null) {
            rowId = UUID.randomUUID().toString();
            jdbcTemplate.update(
                    """
                            INSERT INTO knowledge_document(
                                id, knowledge_base_id, doc_id, title, source_uri, content_text,
                                status, chunk_count, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, 'INDEXING', 0, NOW(), NOW())
                            """,
                    rowId, kbId, docId, title, sourceUri, text);
        } else {
            jdbcTemplate.update(
                    """
                            UPDATE knowledge_document
                            SET title = ?, source_uri = ?, content_text = ?,
                                status = 'INDEXING', error_message = NULL, updated_at = NOW()
                            WHERE id = ?
                            """,
                    title, sourceUri, text, rowId);
        }

        // 重灌前先删旧向量，避免残留旧策略块
        vectorChunkStore.deleteByDoc(kbId, docId);

        try {
            Map<String, String> meta = new HashMap<>();
            meta.put("title", title == null ? docId : title);
            meta.put(VectorChunkStore.META_KB, kbId);
            SourceDocument doc = new SourceDocument(
                    docId,
                    sourceUri == null ? "upload://" + docId : sourceUri,
                    "text/markdown",
                    text,
                    meta
            );
            IngestReport report = ingestionPipeline.ingest(doc);
            jdbcTemplate.update(
                    """
                            UPDATE knowledge_document
                            SET status = 'READY', chunk_count = ?, error_message = NULL, updated_at = NOW()
                            WHERE id = ?
                            """,
                    report.chunkCount(), rowId);
            jdbcTemplate.update("UPDATE knowledge_base SET updated_at = NOW() WHERE id = ?", kbId);
            log.info("KB ingest ok: kb={}, docId={}, chunks={}", kbId, docId, report.chunkCount());
        } catch (Exception e) {
            jdbcTemplate.update(
                    """
                            UPDATE knowledge_document
                            SET status = 'FAILED', error_message = ?, updated_at = NOW()
                            WHERE id = ?
                            """,
                    e.getMessage(), rowId);
            throw new IllegalStateException("摄入失败: " + e.getMessage(), e);
        }
        return get(kbId, docId).orElseThrow();
    }

    private void requireKb(String kbId) {
        if (!knowledgeBaseService.exists(kbId)) {
            throw new KnowledgeBaseService.NotFoundException("知识库不存在: " + kbId);
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sanitizeDocId(String raw) {
        String s = raw.strip().replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]+", "-");
        if (s.isBlank()) {
            s = "doc-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
