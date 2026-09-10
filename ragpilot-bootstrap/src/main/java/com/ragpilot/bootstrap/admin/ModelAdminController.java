package com.ragpilot.bootstrap.admin;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型管理 API（ADM-6）：Overlay 读写 + 网关/Embedding 探测 + 维度警告。
 */
@RestController
@RequestMapping("/api/admin/v1/models")
public class ModelAdminController {

    private final RuntimeConfigService runtimeConfigService;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final WebClient.Builder webClientBuilder;

    public ModelAdminController(
            RuntimeConfigService runtimeConfigService,
            ObjectProvider<EmbeddingModel> embeddingModel,
            WebClient.Builder webClientBuilder
    ) {
        this.runtimeConfigService = runtimeConfigService;
        this.embeddingModel = embeddingModel;
        this.webClientBuilder = webClientBuilder;
    }

    public record ModelSettingsRequest(
            String baseUrl,
            String chatModel,
            Double temperature,
            String embeddingModel,
            Integer embeddingDimensions,
            Boolean rerankEnabled,
            String rerankModel,
            String rerankBaseUrl
    ) {
    }

    @GetMapping
    public Mono<AdminApiResponse<Map<String, Object>>> current() {
        return Mono.fromCallable(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("baseUrl", runtimeConfigService.llmBaseUrl());
            data.put("chatModel", runtimeConfigService.chatModel());
            data.put("temperature", runtimeConfigService.chatTemperature());
            data.put("embeddingModel", runtimeConfigService.embeddingModel());
            data.put("embeddingDimensions", runtimeConfigService.embeddingDimensions());
            data.put("rerankEnabled", runtimeConfigService.rerankEnabled());
            data.put("rerankModel", runtimeConfigService.rerankModel());
            data.put("rerankBaseUrl", runtimeConfigService.rerankBaseUrl());
            data.put("hint", "改 baseUrl/model 后需重启以切换 Spring AI 客户端；探测可立即按 Overlay 地址请求。");
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping
    public Mono<AdminApiResponse<Map<String, Object>>> save(@RequestBody ModelSettingsRequest body) {
        return Mono.fromCallable(() -> {
            List<String> warnings = new ArrayList<>();
            if (body == null) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", "body 不能为空");
            }
            if (body.baseUrl() != null) {
                warnings.addAll(runtimeConfigService.put(RuntimeConfigKeys.LLM_BASE_URL, body.baseUrl()));
            }
            if (body.chatModel() != null) {
                warnings.addAll(runtimeConfigService.put(RuntimeConfigKeys.CHAT_MODEL, body.chatModel()));
            }
            if (body.temperature() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.CHAT_TEMPERATURE, String.valueOf(body.temperature())));
            }
            if (body.embeddingModel() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.EMBEDDING_MODEL, body.embeddingModel()));
            }
            if (body.embeddingDimensions() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.EMBEDDING_DIM, String.valueOf(body.embeddingDimensions())));
            }
            if (body.rerankEnabled() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.RERANK_ENABLED, String.valueOf(body.rerankEnabled())));
            }
            if (body.rerankModel() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.RERANK_MODEL, body.rerankModel()));
            }
            if (body.rerankBaseUrl() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.RERANK_BASE_URL, body.rerankBaseUrl()));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", true);
            data.put("warnings", warnings.stream().distinct().toList());
            data.put("effective", runtimeConfigService.effectiveView());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/probe")
    public Mono<AdminApiResponse<Map<String, Object>>> probe() {
        return Mono.fromCallable(this::doProbe).subscribeOn(Schedulers.boundedElastic());
    }

    private AdminApiResponse<Map<String, Object>> doProbe() {
        Map<String, Object> data = new LinkedHashMap<>();
        String baseUrl = runtimeConfigService.llmBaseUrl().replaceAll("/$", "");
        data.put("baseUrl", baseUrl);
        data.put("chatModel", runtimeConfigService.chatModel());
        data.put("embeddingModel", runtimeConfigService.embeddingModel());
        data.put("configuredDim", runtimeConfigService.embeddingDimensions());

        // 探测 /v1/models（LM Studio OpenAI 兼容）
        try {
            WebClient client = webClientBuilder.baseUrl(baseUrl).build();
            String body = client.get()
                    .uri("/v1/models")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            data.put("modelsEndpointOk", true);
            data.put("modelsRawPreview", body == null ? ""
                    : body.substring(0, Math.min(body.length(), 400)));
        } catch (Exception e) {
            data.put("modelsEndpointOk", false);
            data.put("modelsError", e.getMessage());
        }

        // Embedding 探针（用已装配的 Spring AI 客户端；未重启时可能仍是旧模型）
        EmbeddingModel emb = embeddingModel.getIfAvailable();
        List<String> warnings = new ArrayList<>();
        if (emb == null) {
            data.put("embeddingDim", -1);
            data.put("embeddingError", "EmbeddingModel 未装配");
        } else {
            try {
                float[] vector = emb.embed("hello");
                data.put("embeddingDim", vector.length);
                int configured = runtimeConfigService.embeddingDimensions();
                if (vector.length != configured) {
                    warnings.add("探测维度 " + vector.length + " 与配置 dimensions="
                            + configured + " 不一致，需重建向量库。");
                }
            } catch (Exception e) {
                data.put("embeddingDim", -1);
                data.put("embeddingError", e.getMessage());
            }
        }
        data.put("warnings", warnings);
        return AdminApiResponse.success(data);
    }
}
