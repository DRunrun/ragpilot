package com.ragpilot.core.ingestion;

import com.ragpilot.core.domain.SourceDocument;
import com.ragpilot.core.domain.TextChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IngestionPipeline} 单元测试。
 *
 * <p>用内存假写入器替代真实向量库：管线是纯编排逻辑，
 * 必须能在无 DB、无 Spring 的环境里离线验证。
 */
class IngestionPipelineTest {

    /** 内存假写入器：只收集写入的分块，模拟 ChunkWriter 端口。 */
    private static final class RecordingWriter implements ChunkWriter {
        private final List<TextChunk> written = new ArrayList<>();

        @Override
        public void writeAll(List<TextChunk> chunks) {
            written.addAll(chunks);
        }
    }

    private static SourceDocument doc(String text) {
        return new SourceDocument(
                "demo-doc", "/samples/demo.md", "text/markdown", text,
                Map.of("title", "演示文档"));
    }

    @Test
    @DisplayName("正常摄入：chunkId 为 docId#序号，metadata 从源文档继承")
    void ingestsChunksWithStableIds() {
        RecordingWriter writer = new RecordingWriter();
        IngestionPipeline pipeline = new IngestionPipeline(new FixedSizeChunker(5, 0), writer);

        IngestReport report = pipeline.ingest(doc("abcdefghij"));

        assertEquals("demo-doc", report.docId());
        assertEquals(2, report.chunkCount());
        assertEquals(List.of("demo-doc#0", "demo-doc#1"),
                writer.written.stream().map(TextChunk::id).toList());
        // metadata 必须随块传递，入库后 citation 展示要用
        assertEquals("演示文档", writer.written.get(0).metadata().get("title"));
    }

    @Test
    @DisplayName("空内容文档：返回 0 块且不调用写入器，避免空向量污染")
    void skipsEmptyDocument() {
        RecordingWriter writer = new RecordingWriter();
        IngestionPipeline pipeline = new IngestionPipeline(new FixedSizeChunker(5, 0), writer);

        IngestReport report = pipeline.ingest(doc("   "));

        assertEquals(0, report.chunkCount());
        assertTrue(writer.written.isEmpty());
    }
}
