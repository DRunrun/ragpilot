package com.ragpilot.eval.metrics;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MRR（Mean Reciprocal Rank）的单题 Reciprocal Rank 分量：{@code 1 / rank}。
 *
 * <p>链路位置：评测体系的检索排序指标（M2）；不调用 LLM。
 * <p>对本题：在按相关度排序的 docId 列表中，找第一个属于 {@code expected_doc_ids}
 * 的位置（1-based），返回其倒数；未命中返回 0。多题平均才是真正的 Mean RR（由 Runner 聚合）。
 */
public final class Mrr {

    private Mrr() {
    }

    /**
     * 计算单题 Reciprocal Rank。
     *
     * @param expectedDocIds 期望文档集合；空则返回 {@link Double#NaN}（本题跳过）
     * @param rankedDocIds   检索结果 docId 降序列表
     * @return {@code 1/rank} ∈ (0, 1]，未命中 0；expected 空为 NaN
     */
    public static double reciprocalRank(Set<String> expectedDocIds, List<String> rankedDocIds) {
        Objects.requireNonNull(expectedDocIds, "expectedDocIds");
        Objects.requireNonNull(rankedDocIds, "rankedDocIds");
        if (expectedDocIds.isEmpty()) {
            return Double.NaN;
        }

        // rank 从 1 起；同一 docId 重复出现时以首次为准
        int rank = 0;
        Set<String> seen = new java.util.HashSet<>();
        for (String docId : rankedDocIds) {
            if (docId == null || docId.isBlank() || !seen.add(docId)) {
                continue;
            }
            rank++;
            if (expectedDocIds.contains(docId)) {
                return 1.0 / rank;
            }
        }
        return 0.0;
    }

    /** 便捷重载。 */
    public static double reciprocalRank(List<String> expectedDocIds, List<String> rankedDocIds) {
        Objects.requireNonNull(expectedDocIds, "expectedDocIds");
        return reciprocalRank(Set.copyOf(expectedDocIds), rankedDocIds);
    }

    /**
     * 对多题 RR 取平均（跳过 NaN）。
     *
     * @param reciprocalRanks 各题 RR
     * @return 平均值；若全部跳过返回 NaN
     */
    public static double mean(List<Double> reciprocalRanks) {
        Objects.requireNonNull(reciprocalRanks, "reciprocalRanks");
        double sum = 0.0;
        int n = 0;
        for (Double rr : reciprocalRanks) {
            if (rr == null || rr.isNaN()) {
                continue;
            }
            sum += rr;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
