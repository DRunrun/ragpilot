package com.ragpilot.bootstrap.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量 MCP JSON-RPC 客户端：对接兼容 {@code tools/list} / {@code tools/call} 的 HTTP MCP Server。
 *
 * <p>链路位置：M3 可选扩展（F3.7）；不引入 LangGraph / 官方 MCP SDK，用手写 JSON-RPC
 * 演示「Agent 可调外部 MCP 工具」的边界，依赖保持可控。
 *
 * <p>协议假设：POST {@code baseUrl}，body 为 JSON-RPC 2.0（method / params / id）。
 * 真实 stdio MCP 可用适配器转发到同一接口；本类聚焦面试可讲的最小可运行路径。
 */
public final class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Duration timeout;
    private final AtomicLong idSeq = new AtomicLong(1);

    /**
     * @param endpoint MCP HTTP JSON-RPC 入口，如 http://127.0.0.1:3100/mcp
     * @param timeout  单次 RPC 超时
     */
    public McpClient(String endpoint, Duration timeout, ObjectMapper objectMapper) {
        this.endpoint = URI.create(Objects.requireNonNull(endpoint, "endpoint"));
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * 列出远端工具。
     *
     * @return 工具摘要列表；服务不可达时抛 IllegalStateException
     */
    public List<McpToolInfo> listTools() {
        JsonNode result = rpc("tools/list", objectMapper.createObjectNode());
        List<McpToolInfo> tools = new ArrayList<>();
        JsonNode arr = result.path("tools");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                tools.add(new McpToolInfo(
                        t.path("name").asText(""),
                        t.path("description").asText(""),
                        t.path("inputSchema").toString()
                ));
            }
        }
        return List.copyOf(tools);
    }

    /**
     * 调用远端工具。
     *
     * @param name      工具名
     * @param arguments JSON 对象参数（可为空对象）
     * @return 文本化结果
     */
    public String callTool(String name, JsonNode arguments) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments);
        JsonNode result = rpc("tools/call", params);
        // 常见返回：{ content: [ { type:text, text:"..." } ] }
        JsonNode content = result.path("content");
        if (content.isArray() && !content.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : content) {
                if ("text".equals(c.path("type").asText()) || c.has("text")) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(c.path("text").asText());
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }
        return result.toString();
    }

    private JsonNode rpc(String method, JsonNode params) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("id", idSeq.getAndIncrement());
            body.put("method", method);
            body.set("params", params);

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            log.info("MCP RPC {} → {}", method, endpoint);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("MCP HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root.has("error") && !root.get("error").isNull()) {
                throw new IllegalStateException("MCP error: " + root.get("error"));
            }
            return root.path("result");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MCP call failed: " + e.getMessage(), e);
        }
    }

    /**
     * @param name        工具名
     * @param description 描述
     * @param inputSchema schema JSON 字符串
     */
    public record McpToolInfo(String name, String description, String inputSchema) {
    }
}
