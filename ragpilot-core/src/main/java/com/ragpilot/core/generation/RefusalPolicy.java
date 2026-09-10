package com.ragpilot.core.generation;

import com.ragpilot.core.domain.AskResult;
import com.ragpilot.core.domain.RetrievedChunk;

import java.util.List;
import java.util.Optional;

/**
 * 拒答策略：RAG 可信度的守门员，决定什么时候「不生成」。
 *
 * <p>链路位置：两处卡口——
 * <ol>
 *   <li>检索后、生成前：无命中（或全部低于阈值）→ 拒答，<b>不调用 LLM</b></li>
 *   <li>生成后：答案为空 → 拒答，不把空串当正常回答返回</li>
 * </ol>
 *
 * <p>为什么单独成类：拒答规则会随评测数据调整（M2 可能收紧 minScore、
 * 加「命中但答非所问」判定），集中在一处才好回归验证。
 * 注意：minScore 的分数过滤已在检索器执行，这里的空命中判定是第二道保险。
 */
public final class RefusalPolicy {

    /** 原因码：没有任何检索证据。 */
    public static final String NO_EVIDENCE = "NO_EVIDENCE";

    /** 原因码：模型生成了空答案。 */
    public static final String EMPTY_ANSWER = "EMPTY_ANSWER";

    /** 给用户看的拒答提示语，前端直接展示。 */
    private static final String NO_EVIDENCE_MESSAGE = "根据现有资料无法回答";
    private static final String EMPTY_ANSWER_MESSAGE = "模型未给出有效回答，请重试或换个问法";

    /** 是否启用「无命中即拒答」，来自 ragpilot.refusal.empty-hits；关掉仅供调试，生产必须开。 */
    private final boolean refuseOnEmptyHits;

    public RefusalPolicy(boolean refuseOnEmptyHits) {
        this.refuseOnEmptyHits = refuseOnEmptyHits;
    }

    /**
     * 生成前卡口：判断是否因无证据而拒答。
     *
     * <p>命中此分支时调用方<b>绝不能再调 LLM</b>——这是 m1-spec §4.3 的硬约束，
     * 日志里也应能看到「未调用生成模型」的记录（由调用方编排保证并打点）。
     *
     * @param hits    检索命中列表（已经过 minScore 过滤）
     * @param traceId 追踪 ID，写进拒答结果便于查日志
     * @return 需要拒答时返回 refused 的 AskResult；放行生成时返回 Optional.empty()
     */
    public Optional<AskResult> checkBeforeGeneration(List<RetrievedChunk> hits, String traceId) {
        if (!refuseOnEmptyHits) {
            return Optional.empty();
        }
        if (hits == null || hits.isEmpty()) {
            return Optional.of(AskResult.refused(NO_EVIDENCE, NO_EVIDENCE_MESSAGE, traceId));
        }
        return Optional.empty();
    }

    /**
     * 生成后卡口：空答案视为失败，转成拒答结果。
     *
     * @param answer  模型完整答案（token 流拼接后的文本）
     * @param traceId 追踪 ID
     * @return 答案空白时返回 refused 的 AskResult；否则 Optional.empty()
     */
    public Optional<AskResult> checkAfterGeneration(String answer, String traceId) {
        if (answer == null || answer.isBlank()) {
            return Optional.of(AskResult.refused(EMPTY_ANSWER, EMPTY_ANSWER_MESSAGE, traceId));
        }
        return Optional.empty();
    }
}
