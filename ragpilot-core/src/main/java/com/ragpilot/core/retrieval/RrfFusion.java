package com.ragpilot.core.retrieval;

import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reciprocal Rank Fusion（RRF）融合器：把多路已排序的召回列表合成一路。
 *
 * <p>链路位置：多路召回（向量 / BM25）之后、Rerank / Prompt 组装之前。
 * <p>为什么存在：向量抓语义、关键词抓精确词，两路排序尺度不同，不能直接比分数；
 * RRF 只看名次，公式 {@code score = Σ 1/(k + rank)}，无量纲、可复现、无外部依赖。
 *
 * <p>本类是纯函数工具，不持有状态、不依赖 Spring，方便单测与消融实验复用。
 */
public final class RrfFusion {

    /**
     * RRF 平滑常数的经验默认值。
     * <p>k 越大越平滑（头部与尾部差距缩小），越小越偏向各路 Top-1；
     * 业界常用 60，本项目消融实验默认与此对齐。
     */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
        // 纯静态工具，禁止实例化
    }

    /**
     * 用默认 k=60 融合多路召回，取 topK。
     *
     * @param rankedLists 各路已按相关度排序的命中列表（列表下标 0 = 该路第 1 名）
     * @param topK        融合后最多返回条数，必须 &gt; 0
     * @return 按 RRF 分数降序的命中；channel 一律标为 {@link RetrievedChunk.Channel#RRF}；
     *         所有列表都为空时返回空列表
     */
    public static List<RetrievedChunk> fuse(List<List<RetrievedChunk>> rankedLists, int topK) {
        return fuse(rankedLists, DEFAULT_K, topK);
    }

    /**
     * 融合多路召回。
     *
     * <p>算法要点：
     * <ol>
     *   <li>按列表位置（从 1 起）计 rank，不信任入参里的 {@code rank} 字段——避免上游漏写</li>
     *   <li>同一 {@code chunk.id} 跨路出现时累加 {@code 1/(k+rank)}</li>
     *   <li>块本体取「第一次见到」的 {@link TextChunk}，保证内容稳定</li>
     * </ol>
     *
     * @param rankedLists 各路已排序命中；null 元素会被忽略；列表本身不可为 null
     * @param k           RRF 常数，必须 &gt; 0
     * @param topK        最终返回条数，必须 &gt; 0
     * @return 融合排序后的命中列表（长度 ≤ topK）
     * @throws IllegalArgumentException k 或 topK ≤ 0，或 rankedLists 为 null
     */
    public static List<RetrievedChunk> fuse(List<List<RetrievedChunk>> rankedLists, int k, int topK) {
        Objects.requireNonNull(rankedLists, "rankedLists");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0, got " + k);
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got " + topK);
        }

        // chunkId -> 累加 RRF 分 + 首次见到的块本体
        Map<String, Accumulator> byId = new HashMap<>();

        for (List<RetrievedChunk> list : rankedLists) {
            if (list == null || list.isEmpty()) {
                continue;
            }
            // 名次从 1 开始：列表位置 i 对应 rank = i+1
            for (int i = 0; i < list.size(); i++) {
                RetrievedChunk hit = list.get(i);
                if (hit == null || hit.chunk() == null || hit.chunk().id() == null) {
                    continue;
                }
                int rank = i + 1;
                double contribution = 1.0 / (k + rank);
                String chunkId = hit.chunk().id();
                Accumulator acc = byId.get(chunkId);
                if (acc == null) {
                    byId.put(chunkId, new Accumulator(hit.chunk(), contribution));
                } else {
                    acc.rrfScore += contribution;
                }
            }
        }

        if (byId.isEmpty()) {
            return List.of();
        }

        // 按 RRF 分降序；同分时按 chunkId 字典序，保证结果可复现
        List<Accumulator> sorted = new ArrayList<>(byId.values());
        sorted.sort(Comparator
                .comparingDouble((Accumulator a) -> a.rrfScore).reversed()
                .thenComparing(a -> a.chunk.id()));

        int limit = Math.min(topK, sorted.size());
        List<RetrievedChunk> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Accumulator acc = sorted.get(i);
            // 融合后的 rank 重新从 1 编号；channel 固定 RRF，score 为 RRF 分
            result.add(new RetrievedChunk(
                    acc.chunk,
                    acc.rrfScore,
                    i + 1,
                    RetrievedChunk.Channel.RRF
            ));
        }
        return List.copyOf(result);
    }

    /** 融合过程中的可变累加器：块本体只记第一次，分数持续累加。 */
    private static final class Accumulator {
        private final TextChunk chunk;
        private double rrfScore;

        private Accumulator(TextChunk chunk, double initialScore) {
            this.chunk = chunk;
            this.rrfScore = initialScore;
        }
    }
}
