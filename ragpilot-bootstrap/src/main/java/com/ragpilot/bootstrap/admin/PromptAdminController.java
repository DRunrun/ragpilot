package com.ragpilot.bootstrap.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Prompt Admin API（ADM-1.1～1.4）。
 */
@RestController
@RequestMapping("/api/admin/v1/prompts")
public class PromptAdminController {

    private final PromptAdminService promptAdminService;

    public PromptAdminController(PromptAdminService promptAdminService) {
        this.promptAdminService = promptAdminService;
    }

    public record UpsertRequest(String version, String content) {
    }

    public record ActivateRequest(String version) {
    }

    @GetMapping
    public Mono<AdminApiResponse<Map<String, Object>>> list() {
        return Mono.fromCallable(() -> AdminApiResponse.success(promptAdminService.list()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 使用 /versions/{version}，避免与 /diff 路径变量冲突。 */
    @GetMapping("/versions/{version}")
    public Mono<AdminApiResponse<Map<String, Object>>> get(@PathVariable String version) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(promptAdminService.get(version));
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<Map<String, Object>>fail("NOT_FOUND", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping
    public Mono<AdminApiResponse<Map<String, Object>>> upsert(@RequestBody UpsertRequest body) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(promptAdminService.upsert(
                        body == null ? null : body.version(),
                        body == null ? null : body.content()));
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/activate")
    public Mono<AdminApiResponse<Map<String, Object>>> activate(@RequestBody ActivateRequest body) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(promptAdminService.activate(
                        body == null ? null : body.version()));
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/rollback")
    public Mono<AdminApiResponse<Map<String, Object>>> rollback() {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(promptAdminService.rollback());
            } catch (IllegalStateException e) {
                return AdminApiResponse.<Map<String, Object>>fail("CONFLICT", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/diff")
    public Mono<AdminApiResponse<Map<String, Object>>> diff(
            @RequestParam String left,
            @RequestParam String right
    ) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(promptAdminService.diff(left, right));
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
