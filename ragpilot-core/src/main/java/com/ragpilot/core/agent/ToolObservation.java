package com.ragpilot.core.agent;

import com.ragpilot.core.domain.RetrievedChunk;

import java.util.List;

/**
 * 工具观察结果：可读文本（喂给下一步 Thought / Trace）+ 可选的结构化检索证据。
 *
 * <p>链路位置：ReAct 循环 ACT → OBSERVE 阶段的数据载体。
 *
 * <p>为什么要带结构化 chunks：旧版 Observation 只有文本，答案合成器拿不到
 * knowledge_search 已检索到的块，只能对同一问题<b>再检索一次</b>——延迟翻倍，
 * 且配置热切换时两次结果可能不一致。现在检索工具把命中块随观察结果传递，
 * 决策器 finish 时直接复用，全链路只检索一次。
 *
 * @param text   给决策器/Trace 看的文本；不得为 null（可为空串）
 * @param chunks 检索类工具产出的证据块；非检索工具为空列表
 */
public record ToolObservation(String text, List<RetrievedChunk> chunks) {

    public ToolObservation {
        text = text == null ? "" : text;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    /** 纯文本观察结果（HTTP 抓取、MCP 透传等非检索工具用）。 */
    public static ToolObservation of(String text) {
        return new ToolObservation(text, List.of());
    }

    /** 是否携带可复用的检索证据。 */
    public boolean hasChunks() {
        return !chunks.isEmpty();
    }
}
