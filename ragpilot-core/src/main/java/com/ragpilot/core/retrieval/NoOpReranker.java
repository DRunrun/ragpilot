package com.ragpilot.core.retrieval;

import com.ragpilot.core.domain.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 空操作重排序器：原样保留候选顺序与分数，仅按 topK 截断并重编号 rank。
 *
 * <p>链路位置：与 {@link Reranker} 相同；当 {@code ragpilot.retrieval.rerank.enabled=false}
 * 时注入本实现，保证关闭 Rerank 后行为与 M1 完全一致（消融实验对照组）。
 */
public final class NoOpReranker implements Reranker {

    /**
     * 截断候选并重写 rank（从 1 起），不改 score / channel / chunk。
     *
     * @param query      未使用；保留签名与接口一致
     * @param candidates 初召回列表；null 视为空
     * @param topK       最多保留条数
     * @return 截断后的不可变列表
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        Objects.requireNonNull(query, "query");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got " + topK);
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int limit = Math.min(topK, candidates.size());
        List<RetrievedChunk> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            RetrievedChunk hit = candidates.get(i);
            // 只重编号 rank，其余字段原样拷贝，确保「关闭 = 恒等变换」
            out.add(new RetrievedChunk(hit.chunk(), hit.score(), i + 1, hit.channel()));
        }
        return List.copyOf(out);
    }
}
