package com.ragpilot.bootstrap.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.retrieval.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * BGE 系 Cross-Encoder 重排序适配器：调用 Cohere 兼容的 {@code /v1/rerank} HTTP 接口。
 *
 * <p>链路位置：召回 / RRF 之后的重排环（bootstrap 基础设施）。
 * <p>为什么用 HTTP 而不内嵌 ONNX：避免引入计划外本地推理依赖；
 * 本地可用 TEI / vLLM 等提供 {@code /v1/rerank}，与 LM Studio embedding/chat 并列部署。
 *
 * <p>请求体对齐 Cohere Rerank API：{@code model / query / documents / top_n}；
 * 响应读 {@code results[].index + relevance_score}，再按分降序重写命中。
 */
public class BgeReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(BgeReranker.class);

    /** 单次重排超时：候选通常 ≤ topK*4，本地模型应在数秒内返回。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;

    /**
     * @param webClientBuilder Spring 自动装配的构建器
     * @param objectMapper     JSON 编解码
     * @param baseUrl          rerank 服务根地址（不含 /v1），如 http://127.0.0.1:8082
     * @param apiKey           鉴权头；无鉴权服务可填任意非空
     * @param model            模型 id，如 bge-reranker-v2-m3
     */
    public BgeReranker(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            String baseUrl,
            String apiKey,
            String model
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.model = Objects.requireNonNull(model, "model");
        this.webClient = webClientBuilder
                .baseUrl(trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl")))
                .defaultHeader("Authorization", "Bearer " + (apiKey == null ? "local" : apiKey))
                .build();
    }

    /**
     * 调用远程 BGE rerank，按 relevance_score 重排并截断。
     *
     * @param query      用户问题
     * @param candidates 初召回候选；空则直接返回空
     * @param topK       最终条数
     * @return 重排后的命中；score 改为 relevance_score，channel 保留原值
     * @throws IllegalStateException 远端失败或响应无法解析时
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        Objects.requireNonNull(query, "query");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got " + topK);
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("query", query);
        body.put("top_n", Math.min(topK, candidates.size()));
        ArrayNode docs = body.putArray("documents");
        for (RetrievedChunk hit : candidates) {
            String text = hit.chunk() == null ? "" : hit.chunk().content();
            docs.add(text == null ? "" : text);
        }

        String raw;
        try {
            raw = webClient.post()
                    .uri("/v1/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
        } catch (WebClientResponseException e) {
            throw new IllegalStateException(
                    "BGE rerank HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("BGE rerank call failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("BGE rerank returned empty body");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) {
                throw new IllegalStateException("BGE rerank response missing results: " + raw);
            }

            // 先按 relevance_score 收集，再统一写 rank
            record Scored(RetrievedChunk source, double score) {}
            List<Scored> scored = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    log.warn("Rerank result index out of range: {}", index);
                    continue;
                }
                double score = item.path("relevance_score").asDouble(
                        item.path("score").asDouble(0.0));
                scored.add(new Scored(candidates.get(index), score));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());

            int limit = Math.min(topK, scored.size());
            List<RetrievedChunk> out = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                Scored s = scored.get(i);
                RetrievedChunk src = s.source();
                out.add(new RetrievedChunk(src.chunk(), s.score(), i + 1, src.channel()));
            }
            log.info("BGE rerank: query='{}' candidates={} → {} hits, topScore={}",
                    query, candidates.size(), out.size(),
                    out.isEmpty() ? 0.0 : out.get(0).score());
            return List.copyOf(out);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("BGE rerank parse failed: " + e.getMessage(), e);
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
