package com.ragpilot.core.agent;

/**
 * Agent 单步记录：一次 Thought → Action → Observation 的完整快照。
 *
 * <p>链路位置：ReAct 循环的每轮产出；后续 Trace（F3.4）会按步落库/落内存。
 *
 * @param index        从 1 开始的步号
 * @param thought      本步推理文本（为什么选这个工具）
 * @param action       工具名；约定 {@code finish} 表示给出最终答案并结束
 * @param actionInput  工具入参或 finish 时的最终答案
 * @param observation  工具返回；finish 步可为空
 * @param durationMs   本步墙钟耗时（含工具执行）
 * @param timedOut     是否因单步超时熔断
 * @param retried      本步工具是否经历过失败重试
 * @param failed       本步是否以失败结束（超时或两次都失败）
 */
public record AgentStep(
        int index,
        String thought,
        String action,
        String actionInput,
        String observation,
        long durationMs,
        boolean timedOut,
        boolean retried,
        boolean failed
) {

    /** 约定的终止动作名：不再调工具，actionInput 即最终答案。 */
    public static final String FINISH = "finish";

    /** 本步是否为终止步。 */
    public boolean isFinish() {
        return FINISH.equalsIgnoreCase(action);
    }
}
