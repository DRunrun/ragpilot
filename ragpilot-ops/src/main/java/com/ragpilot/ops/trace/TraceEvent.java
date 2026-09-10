package com.ragpilot.ops.trace;

import java.util.Map;

/**
 * 单条 Trace 事件：一次可观测动作的摘要。
 *
 * <p>链路位置：ops 可观测层；由 {@link TraceRecorder} 按 traceId 聚合。
 *
 * @param type           事件类型，如 {@code agent_step} / {@code retrieval} / {@code generation} / {@code token}
 * @param timestampEpochMs 事件时间（epoch millis）
 * @param durationMs     耗时；未知时为 0
 * @param tool           工具名（非工具事件可空）
 * @param inputSummary   输入摘要（截断后）
 * @param outputSummary  输出摘要（截断后）
 * @param promptTokens   本事件相关 prompt token；无则 null
 * @param completionTokens 本事件相关 completion token；无则 null
 * @param attributes     扩展字段（步号、失败标记等）
 */
public record TraceEvent(
        String type,
        long timestampEpochMs,
        long durationMs,
        String tool,
        String inputSummary,
        String outputSummary,
        Integer promptTokens,
        Integer completionTokens,
        Map<String, Object> attributes
) {

    public TraceEvent {
        type = type == null ? "" : type;
        tool = tool == null ? "" : tool;
        inputSummary = inputSummary == null ? "" : inputSummary;
        outputSummary = outputSummary == null ? "" : outputSummary;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
