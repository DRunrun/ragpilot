package com.ragpilot.bootstrap.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型网关配置自检接口。
 *
 * <p>存在的意义：本地模型排障时，第一个要确认的永远是「应用到底连的是谁、用的哪个模型」。
 * 有这个接口就不用翻配置文件和启动日志，浏览器打开 {@code /api/v1/gateway} 一眼看清。
 *
 * <p>F1.4 起增加 embedding 自检：对固定文本 "hello" 实时取一次向量并返回维度。
 * EmbeddingModel.embed 是阻塞 HTTP，必须在 {@code boundedElastic} 上执行，
 * 不能占用 WebFlux 的 reactor-http-nio 线程。
 */
@RestController
@RequestMapping("/api/v1")
public class GatewayInfoController {

    private static final Logger log = LoggerFactory.getLogger(GatewayInfoController.class);

    /** embedding 自检用的固定探针文本，取什么都行，只要每次一致便于对比维度。 */
    private static final String PROBE_TEXT = "hello";

    /** 本地网关地址，默认 LM Studio 的 http://127.0.0.1:1234。 */
    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    /** 当前使用的生成模型名，必须与 LM Studio 中已加载的模型一致，否则请求会 404。 */
    @Value("${spring.ai.openai.chat.options.model}")
    private String chatModel;

    /** 当前使用的 embedding 模型名，输出维度必须与 PGVector 建表维度一致。 */
    @Value("${spring.ai.openai.embedding.options.model}")
    private String embeddingModel;

    /** Spring AI 自动装配的 OpenAI 兼容 embedding 客户端，与 chat 共用 base-url。 */
    private final EmbeddingModel embeddingClient;

    public GatewayInfoController(EmbeddingModel embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    /**
     * 返回当前网关配置 + embedding 实时自检。
     *
     * <p>用 LinkedHashMap 是为了让返回字段顺序稳定，人肉查看时位置固定。
     * 注意：这里刻意不返回 api-key，避免把凭据暴露在无鉴权接口上。
     */
    @GetMapping("/gateway")
    public Mono<Map<String, Object>> gateway() {
        return Mono.fromCallable(this::buildInfo)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> buildInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("provider", "openai-compatible");
        info.put("baseUrl", baseUrl);
        info.put("chatModel", chatModel);
        info.put("embeddingModel", embeddingModel);
        info.putAll(probeEmbedding());
        info.put("hint", "Ensure LM Studio Server is running and both chat/embedding models are loaded");
        return info;
    }

    /**
     * 对探针文本取一次向量，返回维度。
     *
     * @return 含 embeddingDim 的键值对；成功时维度应为 768（nomic-embed），
     *         失败时 embeddingDim=-1 且 embeddingError 携带原因
     */
    private Map<String, Object> probeEmbedding() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            float[] vector = embeddingClient.embed(PROBE_TEXT);
            result.put("embeddingDim", vector.length);
            log.info("Embedding probe ok: model={}, dim={}", embeddingModel, vector.length);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result.put("embeddingDim", -1);
            result.put("embeddingError", reason);
            log.warn("Embedding probe failed: {}", reason);
        }
        return result;
    }
}
