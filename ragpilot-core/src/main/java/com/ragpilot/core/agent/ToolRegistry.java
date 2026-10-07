package com.ragpilot.core.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具注册表：把 {@link Tool} 列表适配成 {@link ReActAgent.ToolInvoker}。
 *
 * <p>链路位置：Agent 组装层（core 内纯 Java）；bootstrap 注册具体工具后注入 Agent。
 */
public final class ToolRegistry implements ReActAgent.ToolInvoker {

    private final Map<String, Tool> byName;

    public ToolRegistry(List<Tool> tools) {
        Objects.requireNonNull(tools, "tools");
        Map<String, Tool> map = new LinkedHashMap<>();
        for (Tool tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                throw new IllegalArgumentException("tool name must not be blank");
            }
            if (map.put(tool.name(), tool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + tool.name());
            }
        }
        this.byName = Map.copyOf(map);
    }

    /** 供决策器展示的工具清单。 */
    public List<Tool> tools() {
        return List.copyOf(byName.values());
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public ToolObservation invoke(String name, String input) throws Exception {
        Tool tool = byName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        // 走 observe()：检索类工具能顺带带出结构化命中块，非检索工具默认纯文本
        return tool.observe(input);
    }
}
