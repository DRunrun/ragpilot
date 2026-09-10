package com.ragpilot.core.agent;

/**
 * Agent 答案合成器：在工具 Observation 之后，把「问题 + 证据」组织成最终自然语言答案。
 *
 * <p>链路位置：ReAct 循环末步（检索/HTTP 等 ACT 完成 → <b>合成</b> → finish）。
 * <p>为什么抽接口：core 的 {@link KnowledgeFirstDecisionMaker} 不能绑死 LM Studio；
 * 单测可注入假实现，bootstrap 再接 {@code PromptBuilder + Generator}。
 */
@FunctionalInterface
public interface AnswerSynthesizer {

    /**
     * 基于证据生成最终答案。
     *
     * @param question 用户原问题
     * @param evidence 最近一步工具 Observation（如 knowledge_search 的 HITS 摘要）；
     *                 可能以 {@code NO_HITS} 开头表示无命中
     * @return 最终答案文本，不得为 null；证据不足时应返回明确拒答文案
     */
    String synthesize(String question, String evidence);
}
