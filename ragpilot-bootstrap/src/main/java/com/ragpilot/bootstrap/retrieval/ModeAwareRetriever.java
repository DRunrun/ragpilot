package com.ragpilot.bootstrap.retrieval;

import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.retrieval.RetrievalMode;
import com.ragpilot.core.retrieval.Reranker;
import com.ragpilot.core.retrieval.Retriever;
import com.ragpilot.core.retrieval.RrfFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 模式感知检索器：按 {@link RetrievalMode} 在向量 / 混合 / 混合+Rerank 之间切换。
 *
 * <p>链路位置：问答与调试 API 的检索编排（bootstrap）；core 只提供 RRF / Reranker 端口。
 * <p>为什么单独一层：消融实验要「同一问题 × 三种模式」产出可对比命中列表，
 * 而默认 mode=VECTOR 保证未改配置时 /ask 行为与 M1 完全一致。
 */
public class ModeAwareRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(ModeAwareRetriever.class);

    /**
     * 融合前每路多取的倍数：候选池 = topK * 倍数，再 RRF/Rerank 截回 topK。
     * 经验值 4——太小融合空间不足，太大拖慢关键词与重排。
     */
    public static final int CANDIDATE_MULTIPLIER = 4;

    private final Retriever vectorRetriever;
    private final Retriever keywordRetriever;
    private final Reranker reranker;
    private final RetrievalMode defaultMode;

    /**
     * @param vectorRetriever  向量路（已含 minScore 过滤）
     * @param keywordRetriever 关键词 / BM25 近似路
     * @param reranker         重排实现；HYBRID_RERANK 时调用；关闭时通常为 NoOp
     * @param defaultMode      配置默认模式，来自 ragpilot.retrieval.mode
     */
    public ModeAwareRetriever(
            Retriever vectorRetriever,
            Retriever keywordRetriever,
            Reranker reranker,
            RetrievalMode defaultMode
    ) {
        this.vectorRetriever = Objects.requireNonNull(vectorRetriever, "vectorRetriever");
        this.keywordRetriever = Objects.requireNonNull(keywordRetriever, "keywordRetriever");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
        this.defaultMode = defaultMode == null ? RetrievalMode.VECTOR : defaultMode;
    }

    /** 使用配置默认模式检索（/ask 主链路走这里）。 */
    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        return retrieve(query, topK, defaultMode, null, null);
    }

    /**
     * 按指定模式检索，供消融对比与调试 API 使用。
     *
     * @param query 用户问题
     * @param topK  最终返回条数
     * @param mode  本次强制使用的模式；null 则回退默认
     * @return 命中列表；无召回返回空列表
     */
    public List<RetrievedChunk> retrieve(String query, int topK, RetrievalMode mode) {
        return retrieve(query, topK, mode, null, null);
    }

    /**
     * 按模式 + 知识库过滤检索（ADM-3.5）。
     *
     * @param filterExpression 向量路 Spring AI 过滤式；null=不限
     * @param kbIds            关键词路库 ID 列表；null/空=不限
     */
    public List<RetrievedChunk> retrieve(
            String query,
            int topK,
            RetrievalMode mode,
            String filterExpression,
            List<String> kbIds
    ) {
        RetrievalMode m = mode == null ? defaultMode : mode;
        int k = Math.max(topK, 1);
        return switch (m) {
            case VECTOR -> {
                List<RetrievedChunk> hits = retrieveVector(query, k, filterExpression);
                log.info("Retrieve mode=VECTOR hits={} filter={}", hits.size(), filterExpression);
                yield hits;
            }
            case HYBRID -> {
                List<RetrievedChunk> fused = hybrid(query, k, filterExpression, kbIds);
                log.info("Retrieve mode=HYBRID hits={}", fused.size());
                yield fused;
            }
            case HYBRID_RERANK -> {
                List<RetrievedChunk> fused = hybrid(
                        query, k * CANDIDATE_MULTIPLIER, filterExpression, kbIds);
                List<RetrievedChunk> reranked = reranker.rerank(query, fused, k);
                log.info("Retrieve mode=HYBRID_RERANK candidates={} hits={}",
                        fused.size(), reranked.size());
                yield reranked;
            }
        };
    }

    /** 当前配置默认模式（调试响应里回显用）。 */
    public RetrievalMode defaultMode() {
        return defaultMode;
    }

    /**
     * 双路召回 + RRF。每路取 topK*倍数，融合后截到 topK。
     * 任一路为空不妨碍另一路结果进入融合。
     */
    private List<RetrievedChunk> hybrid(
            String query,
            int topK,
            String filterExpression,
            List<String> kbIds
    ) {
        int pool = Math.max(topK * CANDIDATE_MULTIPLIER, topK);
        List<RetrievedChunk> vectorHits = retrieveVector(query, pool, filterExpression);
        List<RetrievedChunk> keywordHits = retrieveKeyword(query, pool, kbIds);
        return RrfFusion.fuse(List.of(vectorHits, keywordHits), topK);
    }

    private List<RetrievedChunk> retrieveVector(String query, int topK, String filterExpression) {
        if (vectorRetriever instanceof VectorRetriever vr) {
            return vr.retrieve(query, topK, filterExpression);
        }
        return vectorRetriever.retrieve(query, topK);
    }

    private List<RetrievedChunk> retrieveKeyword(String query, int topK, List<String> kbIds) {
        if (keywordRetriever instanceof PgFullTextRetriever kr) {
            return kr.retrieve(query, topK, kbIds);
        }
        return keywordRetriever.retrieve(query, topK);
    }
}
