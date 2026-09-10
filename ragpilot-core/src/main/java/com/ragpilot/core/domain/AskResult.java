package com.ragpilot.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * 一次问答的最终结果，是 RAG 链路对外的核心契约。
 *
 * <p>链路位置：检索 → 生成 → <b>本对象</b> → HTTP/SSE 序列化给前端。
 *
 * <p>为什么用 record 而不是 {@code Map<String,Object>}：
 * 契约要能被编译器检查，字段改名时调用方立刻报错，避免上下游靠约定俗成对齐。
 *
 * <p>两种合法状态，只能二选一：
 * <ul>
 *   <li>已回答：{@code refused=false}，answer 非空，通常带 citations</li>
 *   <li>已拒答：{@code refused=true}，refuseReason 说明原因（如 NO_EVIDENCE），citations 为空</li>
 * </ul>
 * 请用静态工厂 {@link #answered} / {@link #refused} 构造，不要直接 new，避免造出「既回答又拒答」的非法状态。
 *
 * @param answer       最终答案文本；拒答时存放给用户看的提示语
 * @param refused      是否拒答（无证据时必须为 true，禁止让模型自由发挥）
 * @param refuseReason 拒答原因码，如 NO_EVIDENCE；未拒答时为 null
 * @param citations    引用列表，答案中的 {@code [n]} 与其 index 对应
 * @param traceId      全链路追踪 ID，用于串起日志与 Trace 记录
 * @param tokenUsage   本次请求的 token 消耗，用于成本治理
 */
public record AskResult(
        String answer,
        boolean refused,
        String refuseReason,
        List<Citation> citations,
        String traceId,
        TokenUsage tokenUsage
) {
    /** 紧凑构造器：做防御性拷贝与空值归一，保证对象不可变且字段永不为 null。 */
    public AskResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        answer = answer == null ? "" : answer;
    }

    /**
     * 构造「拒答」结果。检索无命中或命中分数全部低于阈值时走这里。
     *
     * @param reason  机器可读的原因码，如 NO_EVIDENCE
     * @param message 给用户看的自然语言提示，如「根据现有资料无法回答」
     * @param traceId 追踪 ID
     */
    public static AskResult refused(String reason, String message, String traceId) {
        return new AskResult(message, true, reason, List.of(), traceId, TokenUsage.ZERO);
    }

    /**
     * 构造「已回答」结果。
     *
     * @param citations 不允许为 null；确实无引用时传空列表（但正常 RAG 路径不该出现）
     * @param usage     为 null 时归零，避免调用方在未接入计量时被迫造对象
     */
    public static AskResult answered(String answer, List<Citation> citations, String traceId, TokenUsage usage) {
        Objects.requireNonNull(citations, "citations");
        return new AskResult(answer, false, null, citations, traceId, usage == null ? TokenUsage.ZERO : usage);
    }

    /**
     * Token 消耗计量，成本治理的最小单位。
     *
     * @param prompt     输入侧 token（上下文越长这个越大，是 RAG 成本的主要来源）
     * @param completion 输出侧 token
     */
    public record TokenUsage(int prompt, int completion) {
        /** 未计量或拒答场景的零值，避免到处判 null。 */
        public static final TokenUsage ZERO = new TokenUsage(0, 0);
    }
}
