package com.ragpilot.core.agent;

import com.ragpilot.core.domain.RetrievedChunk;

import java.util.List;

/**
 * 答案合成器端口：Agent 检索完成后，把检索证据变成自然语言答案。
 *
 * <p>链路位置：ReAct finish 前的一步（core 定义端口，bootstrap 接 PromptBuilder + Generator）。
 *
 * <p>为什么入参是结构化块而不是 observation 文本：合成器只需要证据，
 * 文本证据里块内容被截断且不可逆推；直接传 knowledge_search 首次检索的
 * {@link RetrievedChunk} 列表，避免合成侧再发起第二次检索（延迟与结果漂移）。
 */
public interface AnswerSynthesizer {

    /**
     * 基于证据生成最终答案。
     *
     * @param question 用户原问题
     * @param evidence knowledge_search 首次检索命中的块（沿 AgentStep 传递）；
     *                 空列表表示无证据，实现应返回明确拒答文案
     * @return 最终答案文本，不得为 null
     */
    String synthesize(String question, List<RetrievedChunk> evidence);
}
