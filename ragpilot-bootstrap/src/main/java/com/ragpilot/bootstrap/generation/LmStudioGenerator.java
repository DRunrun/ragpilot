package com.ragpilot.bootstrap.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.core.generation.Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LM Studio（OpenAI 兼容）流式生成器：Generator 端口的 M1 唯一实现。
 *
 * <p>链路位置：问答链路最后一环的<b>基础设施实现</b>（F1.11 从 HelloController 抽出）。
 *
 * <p>为什么手写 WebClient 而不用 Spring AI 的 ChatClient.stream()：
 * Qwen 系列思考模型把正文放 {@code delta.reasoning_content} 之外的 {@code delta.content}，
 * 但思考阶段只有 reasoning_content。正式 RAG 答案<b>只要 content</b>——
 * 思考过程不进正文（避免 {@code <think>} 残留污染答案与 citation 校验），
 * 而 /hello 自检口可选放开，因此这里做成开关。
 *
 * <p>token 用量：请求带 {@code stream_options.include_usage=true}，
 * 网关会在最后一帧返回 usage，解析后缓存供 {@link #lastUsage()} 读取。
 */
public class LmStudioGenerator implements Generator {

    private static final Logger log = LoggerFactory.getLogger(LmStudioGenerator.class);

    /** OpenAI 流的结束哨兵。 */
    private static final String DONE_SENTINEL = "[DONE]";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String chatModel;
    private final double temperature;

    /** 最近一次生成的用量；每次 stream 订阅开始时重置，流结束时回填。 */
    private final AtomicReference<AskTokenUsage> lastUsage = new AtomicReference<>(AskTokenUsage.ZERO);

    /**
     * @param webClientBuilder Spring 自动装配的 WebClient 构建器
     * @param objectMapper     JSON 解析器（Spring 单例，线程安全）
     * @param baseUrl          网关地址，如 http://127.0.0.1:1234（不带 /v1）
     * @param apiKey           LM Studio 不校验，但协议要求非空
     * @param chatModel        chat 模型 id，来自 ragpilot.generation.model
     * @param temperature      采样温度，来自 ragpilot.generation.temperature；评测要求 0.0 可复现
     */
    public LmStudioGenerator(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            String baseUrl,
            String apiKey,
            String chatModel,
            double temperature
    ) {
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
        this.temperature = temperature;
        this.webClient = webClientBuilder
                .baseUrl(trimTrailingSlash(baseUrl))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    /** 正式 RAG 路径：只输出正文 token，思考内容被过滤。 */
    @Override
    public Flux<String> stream(String prompt) {
        return stream(prompt, false);
    }

    /**
     * 流式生成。
     *
     * @param prompt           完整 Prompt
     * @param includeReasoning true 时思考内容也输出（仅 /hello 自检用，正式问答必须 false）
     * @return 正文 token 增量流
     */
    public Flux<String> stream(String prompt, boolean includeReasoning) {
        // 每次订阅重置用量，避免并发/连续请求读到上一次的旧值
        lastUsage.set(AskTokenUsage.ZERO);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chatModel);
        body.put("stream", true);
        body.put("temperature", temperature);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        // 要求网关在最后一帧返回 usage，供 token 计量（不支持的网关会忽略，不致命）
        body.put("stream_options", Map.of("include_usage", true));

        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                // 取原始字节自己解析 SSE：一个 DataBuffer 可能含多行，也可能半行截断
                .bodyToFlux(DataBuffer.class)
                .map(this::dataBufferToString)
                .concatMapIterable(chunk -> List.of(chunk.split("\n")))
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .takeUntil(DONE_SENTINEL::equals)
                .filter(data -> !data.isEmpty() && !DONE_SENTINEL.equals(data))
                .mapNotNull(data -> extractDelta(data, includeReasoning))
                .filter(StringUtils::hasText)
                .doOnComplete(() -> log.debug("Generation finished: usage={}", lastUsage.get()));
    }

    @Override
    public AskTokenUsage lastUsage() {
        return lastUsage.get();
    }

    /**
     * 把 DataBuffer 转成字符串并<b>立即释放</b>。
     *
     * <p>Netty 的 DataBuffer 是池化堆外内存，忘 release 会泄漏。
     */
    private String dataBufferToString(DataBuffer buffer) {
        String text = buffer.toString(StandardCharsets.UTF_8);
        DataBufferUtils.release(buffer);
        return text;
    }

    /**
     * 解析一帧 SSE：提取正文增量 + 顺手回填 usage。
     *
     * @param includeReasoning false 时只认 content（思考内容丢弃）；
     *                         true 时 content 优先、缺 content 退回 reasoning_content
     * @return 增量文本；该帧无文本时返回 null（由上游 filter 丢弃）
     */
    private String extractDelta(String dataJson, boolean includeReasoning) {
        try {
            JsonNode root = objectMapper.readTree(dataJson);

            // usage 帧（通常最后一帧）：没有 choices，只有统计信息
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                lastUsage.set(new AskTokenUsage(
                        usage.path("prompt_tokens").asInt(0),
                        usage.path("completion_tokens").asInt(0)
                ));
            }

            JsonNode delta = root.path("choices").path(0).path("delta");
            if (delta.isMissingNode() || delta.isNull()) {
                return null;
            }
            String content = textOrNull(delta.get("content"));
            if (StringUtils.hasText(content)) {
                return content;
            }
            return includeReasoning ? textOrNull(delta.get("reasoning_content")) : null;
        } catch (Exception e) {
            // 单帧解析失败不中断整条流，记日志跳过
            log.debug("Skip unparseable SSE frame: {}", e.getMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 去掉 base-url 末尾斜杠，避免拼出 {@code //v1/...} 双斜杠。 */
    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
