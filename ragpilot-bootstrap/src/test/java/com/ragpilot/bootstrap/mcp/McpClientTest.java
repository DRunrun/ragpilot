package com.ragpilot.bootstrap.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3.7 验收：对本机假 MCP Server 做 list/call。
 */
class McpClientTest {

    private HttpServer server;
    private String endpoint;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startFakeMcp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ObjectNode root = (ObjectNode) mapper.readTree(req);
            String method = root.path("method").asText();
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", root.get("id"));
            ObjectNode result = resp.putObject("result");
            if ("tools/list".equals(method)) {
                ArrayNode tools = result.putArray("tools");
                ObjectNode t = tools.addObject();
                t.put("name", "echo");
                t.put("description", "echo text");
                t.putObject("inputSchema").put("type", "object");
            } else if ("tools/call".equals(method)) {
                String name = root.path("params").path("name").asText();
                String msg = root.path("params").path("arguments").path("text").asText("hi");
                ArrayNode content = result.putArray("content");
                ObjectNode c = content.addObject();
                c.put("type", "text");
                c.put("text", name + ":" + msg);
            }
            byte[] body = mapper.writeValueAsBytes(resp);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void list与call可用() throws Exception {
        McpClient client = new McpClient(endpoint, Duration.ofSeconds(2), mapper);
        assertTrue(client.listTools().stream().anyMatch(t -> "echo".equals(t.name())));
        String out = client.callTool("echo", mapper.readTree("{\"text\":\"ping\"}"));
        assertTrue(out.contains("echo:ping"));

        McpProxyTool proxy = new McpProxyTool(client, mapper);
        String listed = proxy.execute("{\"op\":\"list\"}");
        assertTrue(listed.contains("echo"));
        String called = proxy.execute("{\"op\":\"call\",\"name\":\"echo\",\"arguments\":{\"text\":\"x\"}}");
        assertTrue(called.contains("echo:x"));
    }
}
