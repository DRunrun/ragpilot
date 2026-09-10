package com.ragpilot.core.ingestion;

import com.ragpilot.core.domain.SourceDocument;
import com.ragpilot.core.domain.TextChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 摄入管线：把一篇源文档走完「分块 → 组装 TextChunk → 写入向量库」。
 *
 * <p>链路位置：ingestion 的编排者（解析产物 → <b>本类</b> → 向量库）。
 *
 * <p>为什么放在 core 且只依赖两个端口（{@link Chunker} / {@link ChunkWriter}）：
 * 管线逻辑是纯领域编排，不碰 Spring 与具体向量库——这是本项目的六边形架构核心，
 * 也是换向量库/换分块策略时零改动的保证。
 */
public final class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final Chunker chunker;
    private final ChunkWriter writer;

    /**
     * @param chunker 分块策略，由 bootstrap 按 ragpilot.chunk.* 配置注入
     * @param writer  写入端口，bootstrap 注入 Spring AI 适配器
     */
    public IngestionPipeline(Chunker chunker, ChunkWriter writer) {
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * 摄入一篇文档。
     *
     * <p>空内容文档（rawText 为空白）直接返回 chunkCount=0，不调用 writer，
     * 避免产生空向量污染检索结果。
     *
     * @param doc 解析好的源文档
     * @return 摄入回执（docId + 实际写入块数）
     */
    public IngestReport ingest(SourceDocument doc) {
        Objects.requireNonNull(doc, "doc");

        List<String> parts = chunker.chunk(doc.rawText());
        if (parts.isEmpty()) {
            log.info("Skip empty document: docId={}", doc.id());
            return new IngestReport(doc.id(), 0);
        }

        List<TextChunk> chunks = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            // chunkId 约定 docId#序号：全局唯一且能肉眼定位到文档内位置
            chunks.add(new TextChunk(
                    doc.id() + "#" + i,
                    doc.id(),
                    parts.get(i),
                    i,
                    doc.metadata()
            ));
        }

        writer.writeAll(chunks);
        log.info("Ingested document: docId={}, chunks={}", doc.id(), chunks.size());
        return new IngestReport(doc.id(), chunks.size());
    }
}
