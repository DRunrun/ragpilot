package com.ragpilot.bootstrap.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragpilot.eval.judge.JudgeLlm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 评测 Judge LLM 组装：OpenAI 兼容 chat/completions，temperature 固定 0。
 *
 * <p>链路位置：bootstrap 适配 {@link JudgeLlm} 端口；eval 模块本身不依赖 WebClient。
 */
@Configuration
public class EvalJudgeConfig {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Bean
    JudgeLlm judgeLlm(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            RagPilotProperties props
    ) {
        WebClient client = webClientBuilder
                .baseUrl(trimTrailingSlash(baseUrl))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        String model = props.generation().model();

        return prompt -> {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.0);
            body.put("stream", false);
            ArrayNode messages = body.putArray("messages");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", prompt);

            String raw = client.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
            if (raw == null || raw.isBlank()) {
                return "";
            }
            try {
                JsonNode root = objectMapper.readTree(raw);
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                if (content.isMissingNode() || content.isNull()) {
                    // 部分思考模型正文在 reasoning_content；Judge 只要可解析 JSON 的文本
                    content = root.path("choices").path(0).path("message").path("reasoning_content");
                }
                return content.asText("");
            } catch (Exception e) {
                throw new IllegalStateException("parse judge chat response failed: " + e.getMessage(), e);
            }
        };
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
