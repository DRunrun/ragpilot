package com.ragpilot.bootstrap.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.core.agent.Tool;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 把 MCP Server 上的工具桥接成 Agent {@link Tool}（演示用单一入口）。
 *
 * <p>链路位置：F3.7；Agent 通过 {@code mcp_proxy} 列出/调用远端 MCP 工具，
 * 证明可插拔扩展而不引入多 Agent 框架。
 */
public final class McpProxyTool implements Tool {

    public static final String NAME = "mcp_proxy";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "op": { "type": "string", "enum": ["list", "call"], "description": "list 或 call" },
                "name": { "type": "string", "description": "call 时的远端工具名" },
                "arguments": { "type": "object", "description": "call 时的参数对象" }
              },
              "required": ["op"]
            }
            """;

    private final McpClient client;
    private final ObjectMapper objectMapper;

    public McpProxyTool(McpClient client, ObjectMapper objectMapper) {
        this.client = Objects.requireNonNull(client, "client");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Proxy to an external MCP server: op=list lists remote tools; "
                + "op=call invokes a remote tool by name. Demo for F3.7.";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(String input) throws Exception {
        JsonNode root = parse(input);
        String op = root.path("op").asText("list");
        if ("list".equalsIgnoreCase(op)) {
            return client.listTools().stream()
                    .map(t -> t.name() + ": " + t.description())
                    .collect(Collectors.joining("\n"));
        }
        if ("call".equalsIgnoreCase(op)) {
            String name = root.path("name").asText("");
            if (name.isBlank()) {
                throw new IllegalArgumentException("mcp_proxy call requires name");
            }
            JsonNode args = root.path("arguments");
            return client.callTool(name, args.isMissingNode() ? objectMapper.createObjectNode() : args);
        }
        throw new IllegalArgumentException("unknown op: " + op);
    }

    private JsonNode parse(String input) throws Exception {
        if (input == null || input.isBlank()) {
            return objectMapper.readTree("{\"op\":\"list\"}");
        }
        String s = input.strip();
        if (!s.startsWith("{")) {
            return objectMapper.readTree("{\"op\":\"list\"}");
        }
        return objectMapper.readTree(s);
    }
}
