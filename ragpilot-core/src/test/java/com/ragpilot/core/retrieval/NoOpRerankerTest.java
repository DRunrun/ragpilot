package com.ragpilot.core.retrieval;

import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NoOpReranker} 回归：关闭 Rerank 时必须是恒等变换（截断 + 重编号除外）。
 */
class NoOpRerankerTest {

    private final NoOpReranker reranker = new NoOpReranker();

    @Test
    void 关闭时保留原序与原分() {
        List<RetrievedChunk> in = List.of(
                hit("c1", 0.9, 1),
                hit("c2", 0.5, 2),
                hit("c3", 0.1, 3)
        );

        List<RetrievedChunk> out = reranker.rerank("q", in, 10);

        assertEquals(3, out.size());
        assertEquals("c1", out.get(0).chunk().id());
        assertEquals(0.9, out.get(0).score());
        assertEquals(RetrievedChunk.Channel.VECTOR, out.get(0).channel());
        assertEquals(1, out.get(0).rank());
        assertEquals("c3", out.get(2).chunk().id());
        assertEquals(3, out.get(2).rank());
    }

    @Test
    void topK截断() {
        List<RetrievedChunk> in = List.of(
                hit("a", 1.0, 1),
                hit("b", 0.5, 2),
                hit("c", 0.2, 3)
        );
        List<RetrievedChunk> out = reranker.rerank("q", in, 2);
        assertEquals(2, out.size());
        assertEquals(List.of("a", "b"), out.stream().map(h -> h.chunk().id()).toList());
    }

    @Test
    void 空候选返回空() {
        assertTrue(reranker.rerank("q", List.of(), 5).isEmpty());
        assertTrue(reranker.rerank("q", null, 5).isEmpty());
    }

    @Test
    void 非法topK抛IAE() {
        assertThrows(IllegalArgumentException.class,
                () -> reranker.rerank("q", List.of(), 0));
    }

    private static RetrievedChunk hit(String id, double score, int rank) {
        TextChunk chunk = new TextChunk(id, "doc", "text-" + id, 0, Map.of());
        return new RetrievedChunk(chunk, score, rank, RetrievedChunk.Channel.VECTOR);
    }
}
