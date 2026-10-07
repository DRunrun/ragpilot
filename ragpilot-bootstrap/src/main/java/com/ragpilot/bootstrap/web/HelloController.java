package com.ragpilot.bootstrap.web;

import com.ragpilot.bootstrap.generation.LmStudioGenerator;
import com.ragpilot.core.generation.GenerationEvent;
import com.ragpilot.ops.trace.TraceIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * M0 阶段的模型网关连通性自检接口（纯聊天，不走 RAG）。
 *
 * <p>链路位置：独立于 RAG 主链路之外，只验证本地 LM Studio 能否流式返回。
 * F1.11 已把 SSE 解析逻辑抽进 {@link LmStudioGenerator}，本类退化为薄壳：
 * 负责事件包装（token/done/meta/error）与 traceId 分配。
 *
 * <p>注意这里用 {@code includeReasoning=true}：自检场景希望看到思考模型的输出，
 * 与正式问答（只要正文）刻意不同。
 */
@RestController
@RequestMapping("/api/v1")
public class HelloController {

    private final LmStudioGenerator generator;
    private final String chatModel;

    public HelloController(
            LmStudioGenerator generator,
            @Value("${spring.ai.openai.chat.options.model}") String chatModel
    ) {
        this.generator = generator;
        this.chatModel = chatModel;
    }

    /**
     * 流式聊天自检。
     *
     * <p>{@code @RequestParam} 显式写 name：编译未开 -parameters 时 Spring 拿不到形参名；
     * 父 POM 已开启，此处显式命名做双保险。
     *
     * @param q 提问内容，缺省给一句默认问题方便浏览器直接打开验证
     * @return SSE 流：token（逐字内容）/ done（结束）/ meta（模型名）/ error（异常）
     */
    @GetMapping(value = "/hello", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> hello(
            @RequestParam(name = "q", defaultValue = "用一句话介绍 RAG") String q
    ) {
        if (!StringUtils.hasText(q)) {
            return Flux.just(sse("error", "q must not be blank"));
        }

        String traceId = TraceIds.newId();

        Flux<ServerSentEvent<String>> tokens = generator
                // 自检口放开 reasoning：Qwen 思考模型只输出 reasoning 时也能看到字
                .stream(q, true)
                // 只要正文事件；Completed 在本自检口不展示
                .mapNotNull(GenerationEvent::tokenTextOrNull)
                .map(text -> sse("token", text, traceId))
                // 错误转成 error 事件，不让流断在客户端手里
                .onErrorResume(err -> Mono.just(sse(
                        "error",
                        err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage(),
                        traceId
                )));

        return tokens.concatWithValues(
                sse("done", "ok", traceId),
                ServerSentEvent.<String>builder().event("meta").data("model=" + chatModel).build()
        );
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    /** 带 traceId 的 SSE 事件；traceId 放在 SSE 的 id 字段，前端可直接读取用于问题定位。 */
    private static ServerSentEvent<String> sse(String event, String data, String traceId) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(data)
                .id(traceId)
                .build();
    }
}
