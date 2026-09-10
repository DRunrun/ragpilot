package com.ragpilot.eval.metrics;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Recall@k（文档级）：{@code |expected ∩ top-k| / |expected|}。
 *
 * <p>链路位置：评测体系的检索命中指标（M2）；不调用 LLM，纯规则。
 * <p>口径见 {@code docs/evaluation.md}：看 {@code expected_doc_ids} 是否出现在
 * 检索 top-k 的 docId 集合中。多期望文档时用集合召回率，避免只报「命中任一」过于宽松。
 */
public final class RecallAtK {

    private RecallAtK() {
    }

    /**
     * 计算文档级 Recall@k。
     *
     * @param expectedDocIds 黄金集期望文档 ID；空集合表示本题不评检索（返回 {@link Double#NaN}）
     * @param rankedDocIds   检索结果按相关度降序的 docId 列表（可含重复，按首次出现计入 top-k）
     * @param k              截断位置，必须 &gt; 0
     * @return {@code [0, 1]}；expected 为空时返回 NaN
     * @throws IllegalArgumentException k ≤ 0
     */
    public static double of(Set<String> expectedDocIds, List<String> rankedDocIds, int k) {
        Objects.requireNonNull(expectedDocIds, "expectedDocIds");
        Objects.requireNonNull(rankedDocIds, "rankedDocIds");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0, got " + k);
        }
        if (expectedDocIds.isEmpty()) {
            return Double.NaN;
        }

        // top-k = 排序列表的前 k 个位置（按块计），再取其中 docId 集合
        Set<String> topK = new HashSet<>();
        int limit = Math.min(k, rankedDocIds.size());
        for (int i = 0; i < limit; i++) {
            String docId = rankedDocIds.get(i);
            if (docId != null && !docId.isBlank()) {
                topK.add(docId);
            }
        }

        int hit = 0;
        for (String expected : expectedDocIds) {
            if (expected != null && topK.contains(expected)) {
                hit++;
            }
        }
        return (double) hit / expectedDocIds.size();
    }

    /**
     * 便捷重载：期望 ID 用 List 传入。
     */
    public static double of(List<String> expectedDocIds, List<String> rankedDocIds, int k) {
        Objects.requireNonNull(expectedDocIds, "expectedDocIds");
        return of(Set.copyOf(expectedDocIds), rankedDocIds, k);
    }
}
