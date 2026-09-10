package com.ragpilot.bootstrap.retrieval;

import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import com.ragpilot.core.retrieval.NoOpReranker;
import com.ragpilot.core.retrieval.RetrievalMode;
import com.ragpilot.core.retrieval.Reranker;
import com.ragpilot.core.retrieval.Retriever;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * F2.4 验收：同一问题跑三种模式，得到三份可对比命中列表。
 *
 * <p>用固定假数据模拟向量路 / 关键词路 / 重排，避免依赖 PG 与 BGE 服务。
 */
class ModeAwareRetrieverTest {

    @Test
    void 同一问题三种模式产出可对比命中列表() {
        // 向量序：v1, v2, shared（语义路）
        Retriever vector = (q, topK) -> truncate(List.of(
                hit("v1", 0.9, RetrievedChunk.Channel.VECTOR),
                hit("v2", 0.8, RetrievedChunk.Channel.VECTOR),
                hit("shared", 0.7, RetrievedChunk.Channel.VECTOR)
        ), topK);

        // 关键词序：k1, shared, k2（精确词路，与向量交叉）
        Retriever keyword = (q, topK) -> truncate(List.of(
                hit("k1", 10.0, RetrievedChunk.Channel.BM25),
                hit("shared", 8.0, RetrievedChunk.Channel.BM25),
                hit("k2", 5.0, RetrievedChunk.Channel.BM25)
        ), topK);

        // 重排：故意把最后一名提到第一（与 HYBRID 结果拉开差距）
        Reranker reversing = (query, candidates, topK) -> {
            List<RetrievedChunk> copy = new ArrayList<>(candidates);
            copy.sort(Comparator.comparingInt(RetrievedChunk::rank).reversed());
            List<RetrievedChunk> out = new ArrayList<>();
            int limit = Math.min(topK, copy.size());
            for (int i = 0; i < limit; i++) {
                RetrievedChunk c = copy.get(i);
                out.add(new RetrievedChunk(c.chunk(), 1.0 - i * 0.1, i + 1, c.channel()));
            }
            return out;
        };

        ModeAwareRetriever retriever = new ModeAwareRetriever(
                vector, keyword, reversing, RetrievalMode.VECTOR);

        List<RetrievedChunk> vectorHits = retriever.retrieve("q", 3, RetrievalMode.VECTOR);
        List<RetrievedChunk> hybridHits = retriever.retrieve("q", 3, RetrievalMode.HYBRID);
        List<RetrievedChunk> rerankHits = retriever.retrieve("q", 3, RetrievalMode.HYBRID_RERANK);

        List<String> vectorIds = ids(vectorHits);
        List<String> hybridIds = ids(hybridHits);
        List<String> rerankIds = ids(rerankHits);

        // VECTOR：纯向量序
        assertEquals(List.of("v1", "v2", "shared"), vectorIds);

        // HYBRID：RRF 后应同时出现两路 id，且 channel 为 RRF
        assertEquals(3, hybridHits.size());
        assertEquals(RetrievedChunk.Channel.RRF, hybridHits.get(0).channel());
        // shared 两路都有 → RRF 分最高
        assertEquals("shared", hybridIds.get(0));

        // HYBRID_RERANK：排序应与 HYBRID 不同（反转重排）
        assertEquals(3, rerankHits.size());
        assertNotEquals(hybridIds, rerankIds);

        // 三份列表彼此可对比（至少 VECTOR ≠ HYBRID）
        assertNotEquals(vectorIds, hybridIds);
    }

    @Test
    void 默认VECTOR委托与NoOp下HYBRID_RERANK等于HYBRID截断() {
        Retriever vector = (q, topK) -> truncate(List.of(
                hit("a", 0.9, RetrievedChunk.Channel.VECTOR),
                hit("b", 0.5, RetrievedChunk.Channel.VECTOR)
        ), topK);
        Retriever keyword = (q, topK) -> List.of();

        ModeAwareRetriever retriever = new ModeAwareRetriever(
                vector, keyword, new NoOpReranker(), RetrievalMode.VECTOR);

        assertEquals(List.of("a", "b"), ids(retriever.retrieve("q", 5)));
        assertEquals(
                ids(retriever.retrieve("q", 2, RetrievalMode.HYBRID)),
                ids(retriever.retrieve("q", 2, RetrievalMode.HYBRID_RERANK))
        );
    }

    private static List<String> ids(List<RetrievedChunk> hits) {
        return hits.stream().map(h -> h.chunk().id()).toList();
    }

    private static List<RetrievedChunk> truncate(List<RetrievedChunk> all, int topK) {
        int n = Math.min(Math.max(topK, 1), all.size());
        List<RetrievedChunk> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            RetrievedChunk h = all.get(i);
            out.add(new RetrievedChunk(h.chunk(), h.score(), i + 1, h.channel()));
        }
        return out;
    }

    private static RetrievedChunk hit(String id, double score, RetrievedChunk.Channel channel) {
        TextChunk chunk = new TextChunk(id, "doc", "text-" + id, 0, Map.of());
        return new RetrievedChunk(chunk, score, 0, channel);
    }
}
