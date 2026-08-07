package com.ragpilot.bootstrap.web;

import com.ragpilot.ops.trace.TraceIds;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Week 0 smoke endpoint: Ollama chat stream (no RAG yet).
 */
@RestController
@RequestMapping("/api/v1")
public class HelloController {

    private final ChatClient chatClient;

    public HelloController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping(value = "/hello", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, String>> hello(@RequestParam(defaultValue = "用一句话介绍 RAG") String q) {
        if (!StringUtils.hasText(q)) {
            return Flux.just(Map.of("event", "error", "message", "q must not be blank"));
        }
        String traceId = TraceIds.newId();
        return chatClient.prompt()
                .user(q)
                .stream()
                .content()
                .map(token -> Map.of("event", "token", "text", token, "traceId", traceId))
                .concatWithValues(Map.of("event", "done", "traceId", traceId));
    }
}
