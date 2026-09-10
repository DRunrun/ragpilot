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
 * {@link RrfFusion} 单测：用两路已知排名手算期望序，与代码结果逐项对比。
 *
 * <p>手算口径（k=60）：
 * <pre>
 * 向量路：c1, c2, c3（rank 1/2/3）
 * BM25路：c2, c4, c1（rank 1/2/3）
 *
 * c1 = 1/61 + 1/63
 * c2 = 1/62 + 1/61
 * c3 = 1/63
 * c4 = 1/62
 * → 序：c2 > c1 > c4 > c3
 * </pre>
 */
class RrfFusionTest {

    private static final int K = 60;

    @Test
    void 两路已知排名融合序与手算一致() {
        List<RetrievedChunk> vector = List.of(
                hit("c1", RetrievedChunk.Channel.VECTOR, 0.9, 1),
                hit("c2", RetrievedChunk.Channel.VECTOR, 0.8, 2),
                hit("c3", RetrievedChunk.Channel.VECTOR, 0.7, 3)
        );
        List<RetrievedChunk> bm25 = List.of(
                hit("c2", RetrievedChunk.Channel.BM25, 12.0, 1),
                hit("c4", RetrievedChunk.Channel.BM25, 8.0, 2),
                hit("c1", RetrievedChunk.Channel.BM25, 5.0, 3)
        );

        // 手算期望分数（与实现同一公式，避免魔法数漂移）
        double c1 = 1.0 / (K + 1) + 1.0 / (K + 3);
        double c2 = 1.0 / (K + 2) + 1.0 / (K + 1);
        double c3 = 1.0 / (K + 3);
        double c4 = 1.0 / (K + 2);

        List<RetrievedChunk> fused = RrfFusion.fuse(List.of(vector, bm25), K, 10);

        assertEquals(4, fused.size());
        assertEquals(List.of("c2", "c1", "c4", "c3"),
                fused.stream().map(h -> h.chunk().id()).toList());

        assertEquals(c2, fused.get(0).score(), 1e-12);
        assertEquals(c1, fused.get(1).score(), 1e-12);
        assertEquals(c4, fused.get(2).score(), 1e-12);
        assertEquals(c3, fused.get(3).score(), 1e-12);

        // 融合后 channel / rank 约定
        assertEquals(RetrievedChunk.Channel.RRF, fused.get(0).channel());
        assertEquals(1, fused.get(0).rank());
        assertEquals(2, fused.get(1).rank());
        assertEquals(3, fused.get(2).rank());
        assertEquals(4, fused.get(3).rank());
    }

    @Test
    void topK截断只返回前N() {
        List<RetrievedChunk> a = List.of(
                hit("a", RetrievedChunk.Channel.VECTOR, 1.0, 1),
                hit("b", RetrievedChunk.Channel.VECTOR, 0.5, 2)
        );
        List<RetrievedChunk> b = List.of(
                hit("c", RetrievedChunk.Channel.BM25, 1.0, 1)
        );

        List<RetrievedChunk> fused = RrfFusion.fuse(List.of(a, b), 2);
        assertEquals(2, fused.size());
        // a 与 c 同分（都是 1/61），按 chunkId 字典序：a 在 c 前；b 分更低被截断
        assertEquals("a", fused.get(0).chunk().id());
        assertEquals("c", fused.get(1).chunk().id());
    }

    @Test
    void 空列表返回空() {
        assertTrue(RrfFusion.fuse(List.of(), 5).isEmpty());
        assertTrue(RrfFusion.fuse(List.of(List.of(), List.of()), 5).isEmpty());
    }

    @Test
    void 非法参数抛IAE() {
        assertThrows(IllegalArgumentException.class,
                () -> RrfFusion.fuse(List.of(List.of()), 0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> RrfFusion.fuse(List.of(List.of()), 60, 0));
        assertThrows(NullPointerException.class,
                () -> RrfFusion.fuse(null, 5));
    }

    /** 构造测试命中：content/metadata 对融合无关，只关心 id 与列表位置。 */
    private static RetrievedChunk hit(String id, RetrievedChunk.Channel channel, double score, int rank) {
        TextChunk chunk = new TextChunk(id, "doc-" + id, "text-" + id, 0, Map.of());
        return new RetrievedChunk(chunk, score, rank, channel);
    }
}
