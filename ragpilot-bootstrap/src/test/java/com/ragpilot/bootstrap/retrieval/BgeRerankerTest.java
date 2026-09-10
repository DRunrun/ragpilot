package com.ragpilot.bootstrap.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.domain.TextChunk;
import com.ragpilot.core.retrieval.NoOpReranker;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link BgeReranker} 验收：用本机 HttpServer 模拟 Cohere {@code /v1/rerank}，
 * 证明「开 Rerank」与 {@link NoOpReranker} 对同一候选给出不同排序。
 *
 * <p>不依赖真实 BGE 服务，也不引入 Mock 框架（无计划外依赖）。
 */
class BgeRerankerTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startMockRerankServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 故意把 index=1 排到前面，制造与入参顺序不同的结果
        server.createContext("/v1/rerank", exchange -> {
            byte[] body = """
                    {"results":[
                      {"index":1,"relevance_score":0.95},
                      {"index":0,"relevance_score":0.10},
                      {"index":2,"relevance_score":0.50}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 开关切换两次结果不同() {
        List<RetrievedChunk> candidates = List.of(
                hit("c0", 0.9, 1),
                hit("c1", 0.8, 2),
                hit("c2", 0.7, 3)
        );

        List<RetrievedChunk> off = new NoOpReranker().rerank("BeanPostProcessor", candidates, 3);

        BgeReranker on = new BgeReranker(
                WebClient.builder(),
                new ObjectMapper(),
                baseUrl,
                "local",
                "bge-reranker-v2-m3"
        );
        List<RetrievedChunk> enabled = on.rerank("BeanPostProcessor", candidates, 3);

        // 关闭：保持原序 c0,c1,c2
        assertEquals(List.of("c0", "c1", "c2"),
                off.stream().map(h -> h.chunk().id()).toList());

        // 开启：mock 按 score 排成 c1,c2,c0
        assertEquals(List.of("c1", "c2", "c0"),
                enabled.stream().map(h -> h.chunk().id()).toList());
        assertEquals(0.95, enabled.get(0).score(), 1e-9);

        assertNotEquals(
                off.stream().map(h -> h.chunk().id()).toList(),
                enabled.stream().map(h -> h.chunk().id()).toList()
        );
    }

    private static RetrievedChunk hit(String id, double score, int rank) {
        TextChunk chunk = new TextChunk(id, "doc", "content-" + id, 0, Map.of());
        return new RetrievedChunk(chunk, score, rank, RetrievedChunk.Channel.VECTOR);
    }
}
