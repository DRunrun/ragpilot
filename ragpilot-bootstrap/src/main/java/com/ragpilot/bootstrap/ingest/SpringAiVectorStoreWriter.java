package com.ragpilot.bootstrap.ingest;

import com.ragpilot.core.domain.TextChunk;
import com.ragpilot.core.ingestion.ChunkWriter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ChunkWriter 的 Spring AI PGVector 适配器：把领域分块转成 Spring AI Document 并入库。
 *
 * <p>链路位置：ingestion 最后一步的<b>基础设施实现</b>。六边形架构里的「适配器」——
 * core 只认 {@link ChunkWriter} 端口，Spring AI 的侵入性全部被隔离在本类内。
 *
 * <p>入库动作 {@code vectorStore.add} 内部会自动调用 EmbeddingModel 向量化，
 * 因此本类不直接碰 embedding 客户端。
 */
public class SpringAiVectorStoreWriter implements ChunkWriter {

    /**
     * nomic-embed-text-v1.5 要求入库文本带任务前缀，否则检索质量会明显下降。
     * 原文另存 metadata.rawContent，检索回传给 LLM 时用原文，避免前缀污染上下文。
     */
    public static final String DOCUMENT_PREFIX = "search_document: ";

    /** metadata 键：未被前缀污染的原文。 */
    public static final String RAW_CONTENT_KEY = "rawContent";

    /**
     * metadata 键：业务侧 chunkId（如 spring-bean-lifecycle#0）。
     * PGVector/Spring AI 要求 Document.id 必须是合法 UUID，不能直接用业务 id。
     */
    public static final String CHUNK_ID_KEY = "chunkId";

    private final VectorStore vectorStore;

    public SpringAiVectorStoreWriter(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 批量写入：领域 TextChunk → Spring AI Document，一次 add 完成向量化与入库。
     *
     * <p>metadata 需要从 {@code Map<String,String>} 放宽为 {@code Map<String,Object>}，
     * 因为 Spring AI 的 metadata 契约是 Object 值；我们仍只放 String，保持可序列化。
     */
    @Override
    public void writeAll(List<TextChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<Document> documents = chunks.stream()
                .map(SpringAiVectorStoreWriter::toDocument)
                .toList();
        vectorStore.add(documents);
    }

    /**
     * 领域对象 → Spring AI Document。
     *
     * <p>Document.id 用随机 UUID（PGVector 主键约束）；业务 {@code chunkId}/{@code docId}
     * 全部进 metadata，检索侧再还原，避免「Invalid UUID string: xxx#0」。
     */
    private static Document toDocument(TextChunk chunk) {
        Map<String, Object> metadata = new HashMap<>(chunk.metadata());
        metadata.put("docId", chunk.docId());
        metadata.put(CHUNK_ID_KEY, chunk.id());
        // 原文留给检索后的 Prompt/Citation；向量化用带前缀的 text
        metadata.put(RAW_CONTENT_KEY, chunk.content());
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(DOCUMENT_PREFIX + chunk.content())
                .metadata(metadata)
                .build();
    }
}
