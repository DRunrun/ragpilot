package com.ragpilot.bootstrap.admin;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理 API（ADM-5.1 / 5.4）。
 */
@RestController
@RequestMapping("/api/admin/v1/sessions")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    public ChatSessionController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    public record CreateSessionRequest(String title, String mode, String knowledgeBaseId) {
    }

    @GetMapping
    public Mono<AdminApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return Mono.fromCallable(() -> AdminApiResponse.success(
                        chatSessionService.toListPayload(chatSessionService.list(limit))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<AdminApiResponse<ChatSessionService.SessionView>> create(
            @RequestBody CreateSessionRequest body
    ) {
        return Mono.fromCallable(() -> AdminApiResponse.success(chatSessionService.create(
                        body == null ? null : body.title(),
                        body == null ? null : body.mode(),
                        body == null ? null : body.knowledgeBaseId())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<AdminApiResponse<ChatSessionService.SessionView>> get(@PathVariable String id) {
        return Mono.fromCallable(() -> chatSessionService.get(id)
                        .map(AdminApiResponse::success)
                        .orElseGet(() -> AdminApiResponse.fail("NOT_FOUND", "会话不存在: " + id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<AdminApiResponse<Map<String, Object>>> delete(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            chatSessionService.delete(id);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("deleted", true);
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/messages")
    public Mono<AdminApiResponse<Map<String, Object>>> messages(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            if (chatSessionService.get(id).isEmpty()) {
                return AdminApiResponse.<Map<String, Object>>fail("NOT_FOUND", "会话不存在: " + id);
            }
            List<ChatSessionService.MessageView> items = chatSessionService.listMessages(id);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("total", items.size());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
