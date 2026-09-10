package com.ragpilot.bootstrap.admin;

import com.ragpilot.ops.trace.TraceRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace 与日志 tail Admin API（ADM-10）。
 */
@RestController
@RequestMapping("/api/admin/v1")
public class TraceAdminController {

    private final TraceRecorder traceRecorder;
    private final LogRingBuffer logRingBuffer;

    public TraceAdminController(TraceRecorder traceRecorder, LogRingBuffer logRingBuffer) {
        this.traceRecorder = traceRecorder;
        this.logRingBuffer = logRingBuffer;
    }

    @GetMapping("/traces")
    public Mono<AdminApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> items = traceRecorder.listRecent(limit).stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("traceId", r.traceId());
                        m.put("kind", r.kind());
                        m.put("startedAtEpochMs", r.startedAtEpochMs());
                        m.put("eventCount", r.events().size());
                        return m;
                    })
                    .toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("total", items.size());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/traces/{traceId}")
    public Mono<AdminApiResponse<Map<String, Object>>> get(@PathVariable String traceId) {
        return Mono.fromCallable(() -> {
            var opt = traceRecorder.find(traceId).map(TraceRecorder.TraceRecord::snapshot);
            if (opt.isEmpty()) {
                return AdminApiResponse.<Map<String, Object>>fail("NOT_FOUND", "trace 不存在: " + traceId);
            }
            var r = opt.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("traceId", r.traceId());
            data.put("kind", r.kind());
            data.put("startedAtEpochMs", r.startedAtEpochMs());
            data.put("eventCount", r.events().size());
            data.put("events", r.events());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/logs/tail")
    public Mono<AdminApiResponse<Map<String, Object>>> logs(
            @RequestParam(defaultValue = "100") int limit
    ) {
        return Mono.fromCallable(() -> {
            List<String> lines = logRingBuffer.tail(limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("lines", lines);
            data.put("total", lines.size());
            data.put("buffered", logRingBuffer.size());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
