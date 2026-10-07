package com.ragpilot.core.agent;

/**
 * Agent 工具端口：ReAct Action 的可调用能力单元。
 *
 * <p>链路位置：ReAct 循环的 ACT 阶段；决策器根据 name/description/schema 选择工具。
 * <p>为什么抽接口：检索、HTTP、MCP 都是工具，统一契约后 Agent 编排与具体实现解耦。
 */
public interface Tool {

    /** 工具短名，决策里的 action 必须与此一致（如 {@code knowledge_search}）。 */
    String name();

    /** 给决策器/LLM 看的能力说明，决定「会不会被选中」。 */
    String description();

    /**
     * 入参 JSON Schema（草稿 07 子集即可），描述 actionInput 应长什么样。
     *
     * @return JSON Schema 字符串，不得为 null
     */
    String inputSchema();

    /**
     * 执行工具。
     *
     * @param input 通常为 JSON 或纯查询串；由各工具自行解析
     * @return 观察结果文本，供下一步 Thought 使用
     * @throws Exception 失败时抛出，由 ReActAgent 重试
     */
    String execute(String input) throws Exception;

    /**
     * 结构化执行：文本观察结果 + 可复用的检索证据。
     *
     * <p>检索类工具（如 knowledge_search）应重写本方法把命中块带出，
     * 下游答案合成就能复用首次检索结果、不再重检一遍；
     * 非检索工具默认委托 {@link #execute}，零改动成本。
     *
     * @param input 工具入参
     * @return 观察结果；不得为 null
     * @throws Exception 失败时抛出，由 ReActAgent 重试
     */
    default ToolObservation observe(String input) throws Exception {
        return ToolObservation.of(execute(input));
    }
}
