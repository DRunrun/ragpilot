package com.ragpilot.bootstrap.admin;

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
 * 评测管理 API（ADM-4.2～4.4）。
 */
@RestController
@RequestMapping("/api/admin/v1/eval")
public class EvalAdminController {

    private final EvalJobService evalJobService;

    public EvalAdminController(EvalJobService evalJobService) {
        this.evalJobService = evalJobService;
    }

    public record CreateJobRequest(String mode, String goldenSet, Integer topK, Boolean withJudge) {
    }

    @GetMapping("/golden")
    public Mono<AdminApiResponse<Map<String, Object>>> golden(
            @RequestParam(defaultValue = "v1.0") String goldenSet
    ) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(evalJobService.goldenMeta(goldenSet));
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/jobs")
    public Mono<AdminApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return Mono.fromCallable(() -> {
            List<EvalJobService.EvalJobView> items = evalJobService.list(limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("total", items.size());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/jobs")
    public Mono<AdminApiResponse<EvalJobService.EvalJobView>> create(@RequestBody CreateJobRequest body) {
        return Mono.fromCallable(() -> {
            try {
                return AdminApiResponse.success(evalJobService.create(
                        body == null ? "VECTOR" : body.mode(),
                        body == null ? "v1.0" : body.goldenSet(),
                        body == null ? null : body.topK(),
                        body != null && Boolean.TRUE.equals(body.withJudge())));
            } catch (IllegalArgumentException e) {
                return AdminApiResponse.<EvalJobService.EvalJobView>fail("BAD_REQUEST", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/jobs/{id}")
    public Mono<AdminApiResponse<EvalJobService.EvalJobView>> get(@PathVariable String id) {
        return Mono.fromCallable(() -> evalJobService.get(id)
                        .map(AdminApiResponse::success)
                        .orElseGet(() -> AdminApiResponse.fail("NOT_FOUND", "任务不存在: " + id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/jobs/{id}/report")
    public Mono<AdminApiResponse<Map<String, Object>>> report(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            if (evalJobService.get(id).isEmpty()) {
                return AdminApiResponse.<Map<String, Object>>fail("NOT_FOUND", "任务不存在: " + id);
            }
            String md = evalJobService.reportMarkdown(id).orElse("");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("markdown", md);
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
