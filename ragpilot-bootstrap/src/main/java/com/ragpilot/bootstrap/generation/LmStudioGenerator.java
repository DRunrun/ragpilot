package com.ragpilot.bootstrap.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.core.generation.GenerationEvent;
import com.ragpilot.core.generation.Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * <p>并发与用量：本类是无状态单例 Bean，<b>不得持有请求级状态</b>。
 * token 用量存在每次订阅独立的 {@code Flux.defer} 作用域里，
 * 流结束时以 {@link GenerationEvent.Completed} 事件随流返回（旧版实例字段
 * lastUsage 在并发请求下会串号，已废弃）。
 *
 * <p>SSE 解析踩坑：TCP 帧（DataBuffer）边界可能落在多字节 UTF-8 字符中间，
 * 直接对每个 buffer {@code toString(UTF_8)} 会产出 U+FFFD 乱码——中文输出必踩。
 * 现在先按<b>字节</b>切行（UTF-8 续字节永不等于 '\n'，字节级切分安全），
 * 攒够一整行再解码，跨帧的行与半个汉字都由 {@link SseLineBuffer} 续上。
 */
public class LmStudioGenerator implements Generator {

    private static final Logger log = LoggerFactory.getLogger(LmStudioGenerator.class);

    /** OpenAI 流的结束哨兵。 */
    private static final String DONE_SENTINEL = "[DONE]";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String chatModel;
    private final double temperature;

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
    public Flux<GenerationEvent> stream(String prompt) {
        return stream(prompt, false);
    }

    /**
     * 流式生成（事件流）。
     *
     * @param prompt           完整 Prompt
     * @param includeReasoning true 时思考内容也作为 Token 输出（仅 /hello 自检用，正式问答必须 false）
     * @return 生成事件流；最后一个元素恒为 Completed（正常结束时），携带本次订阅的用量
     */
    public Flux<GenerationEvent> stream(String prompt, boolean includeReasoning) {
        // defer：usageRef 与 lineBuffer 都是「每次订阅」的私有状态，并发订阅互不干扰
        return Flux.defer(() -> {
            AtomicReference<AskTokenUsage> usageRef =
                    new AtomicReference<>(AskTokenUsage.ZERO);
            SseLineBuffer lineBuffer = new SseLineBuffer();

            Flux<String> dataFrames = webClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(buildRequestBody(prompt))
                    .retrieve()
                    // 原始字节进缓冲区，按 '\n' 切出完整行后再解码（见类注释踩坑说明）
                    .bodyToFlux(DataBuffer.class)
                    .concatMapIterable(buffer -> lineBuffer.consume(toBytesAndRelease(buffer)))
                    // 上游正常结束后冲刷最后一行（没有换行符结尾的残行）
                    .concatWith(Flux.defer(() -> Flux.fromIterable(lineBuffer.flush())))
                    .map(String::trim)
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).trim())
                    .takeUntil(DONE_SENTINEL::equals)
                    .filter(data -> !data.isEmpty() && !DONE_SENTINEL.equals(data));

            Flux<GenerationEvent> tokens = dataFrames
                    .mapNotNull(data -> extractDelta(data, usageRef, includeReasoning))
                    .filter(StringUtils::hasText)
                    .map(GenerationEvent.Token::new);

            // 用量随流返回：正常结束时以 Completed 收尾；异常路径不发（上游 error 直接传播）
            return tokens.concatWith(
                    Mono.fromSupplier(() -> new GenerationEvent.Completed(usageRef.get())));
        });
    }

    /** 组装 OpenAI chat 请求体；stream_options 要求网关在最后一帧回 usage。 */
    private Map<String, Object> buildRequestBody(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chatModel);
        body.put("stream", true);
        body.put("temperature", temperature);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        // 要求网关在最后一帧返回 usage，供 token 计量（不支持的网关会忽略，不致命）
        body.put("stream_options", Map.of("include_usage", true));
        return body;
    }

    /**
     * 把 DataBuffer 拷成字节数组并<b>立即释放</b>。
     *
     * <p>Netty 的 DataBuffer 是池化堆外内存，忘 release 会泄漏。
     * 这里不再 toString——解码推迟到「凑齐一整行」之后，避免半字符乱码。
     */
    private static byte[] toBytesAndRelease(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return bytes;
    }

    /**
     * 解析一帧 SSE：提取正文增量 + 把 usage 记进<b>本次订阅</b>的 usageRef。
     *
     * @param includeReasoning false 时只认 content（思考内容丢弃）；
     *                         true 时 content 优先、缺 content 退回 reasoning_content
     * @return 增量文本；该帧无文本时返回 null（由上游 filter 丢弃）
     */
    private String extractDelta(String dataJson,
                                AtomicReference<AskTokenUsage> usageRef,
                                boolean includeReasoning) {
        try {
            JsonNode root = objectMapper.readTree(dataJson);

            // usage 帧（通常最后一帧）：没有 choices，只有统计信息
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                usageRef.set(new AskTokenUsage(
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

    /**
     * SSE 行缓冲器：攒字节流、按 '\n' 切出完整行再按 UTF-8 解码。
     *
     * <p>为什么按字节找换行：UTF-8 多字节字符的续字节都 ≥ 0x80，永远不等于 0x0A，
     * 所以字节级切行不会拆坏汉字；被拆开的只有「行」，由 pending 缓冲续上下一帧。
     * 每个实例只服务于一次订阅（在 Flux.defer 内创建），无需线程安全。
     */
    static final class SseLineBuffer {

        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        /** 吃掉一帧字节，返回其中凑完整的行（已去掉行首尾空白由上游负责）。 */
        List<String> consume(byte[] bytes) {
            List<String> lines = new ArrayList<>();
            for (byte b : bytes) {
                if (b == '\n') {
                    String line = decodeAndReset();
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                } else if (b != '\r') {
                    // 顺带吃掉 \r，兼容 CRLF 分行
                    pending.write(b);
                }
            }
            return lines;
        }

        /** 上游结束后冲刷没有换行收尾的残行。 */
        List<String> flush() {
            String tail = decodeAndReset();
            return tail.isEmpty() ? List.of() : List.of(tail);
        }

        private String decodeAndReset() {
            String s = pending.toString(StandardCharsets.UTF_8);
            pending.reset();
            return s;
        }
    }
}
