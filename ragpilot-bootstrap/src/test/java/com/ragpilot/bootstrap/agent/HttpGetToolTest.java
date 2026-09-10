package com.ragpilot.bootstrap.agent;

import com.ragpilot.core.agent.ReActAgent;
import com.ragpilot.core.agent.ToolRegistry;
import com.ragpilot.core.agent.tools.KnowledgeSearchTool;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3.3 验收：第二个工具可调用；越权域名被拒。
 */
class HttpGetToolTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/doc", exchange -> {
            byte[] body = "Spring Bean lifecycle docs".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 白名单域名可GET() throws Exception {
        HttpGetTool tool = new HttpGetTool(Set.of("127.0.0.1"), Duration.ofSeconds(2));
        String out = tool.execute("{\"url\":\"" + baseUrl + "/doc\"}");
        assertTrue(out.contains("status=200"));
        assertTrue(out.contains("Spring Bean"));
    }

    @Test
    void 越权域名被拒() {
        HttpGetTool tool = new HttpGetTool(Set.of("docs.spring.io"), Duration.ofSeconds(2));
        SecurityException ex = assertThrows(SecurityException.class,
                () -> tool.execute("https://evil.example/steal"));
        assertTrue(ex.getMessage().contains("not allowlisted"));
    }

    @Test
    void 作为第二工具可被Agent调用() {
        KnowledgeSearchTool search = new KnowledgeSearchTool((q, k) -> {
            TextChunk c = new TextChunk("d#0", "d", "kb", 0, Map.of());
            return List.of(new RetrievedChunk(c, 1.0, 1, RetrievedChunk.Channel.VECTOR));
        }, 3);
        HttpGetTool http = new HttpGetTool(Set.of("127.0.0.1"), Duration.ofSeconds(2));
        ToolRegistry registry = new ToolRegistry(List.of(search, http));

        ReActAgent.DecisionMaker planner = (q, history) -> {
            if (history.isEmpty()) {
                return new ReActAgent.Decision("先检索", KnowledgeSearchTool.NAME, q);
            }
            if (history.size() == 1) {
                return new ReActAgent.Decision(
                        "再拉文档页",
                        HttpGetTool.NAME,
                        "{\"url\":\"" + baseUrl + "/doc\"}"
                );
            }
            return new ReActAgent.Decision("结束", "finish", "done with 2 tools");
        };

        ReActAgent.AgentResult result = new ReActAgent(planner, registry, 5, Duration.ofSeconds(3))
                .run("需要两步");
        assertTrue(result.steps().stream().anyMatch(s -> HttpGetTool.NAME.equals(s.action())));
        assertTrue(result.finalAnswer().contains("2 tools"));
    }
}
