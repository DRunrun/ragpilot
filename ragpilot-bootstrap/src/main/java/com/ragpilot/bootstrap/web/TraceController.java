package com.ragpilot.bootstrap.web;

import com.ragpilot.ops.trace.TraceRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trace 查询接口：按 traceId 返回完整事件列表。
 *
 * <p>链路位置：F3.4 验收入口；面试时可对着 Agent 跑完后的 ID 现场展开。
 */
@RestController
@RequestMapping("/api/v1/trace")
public class TraceController {

    private final TraceRecorder traceRecorder;

    public TraceController(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    /**
     * {@code GET /api/v1/trace/{traceId}}
     */
    @GetMapping("/{traceId}")
    public Map<String, Object> get(@PathVariable("traceId") String traceId) {
        TraceRecorder.TraceRecord record = traceRecorder.find(traceId)
                .map(TraceRecorder.TraceRecord::snapshot)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "trace not found"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", record.traceId());
        body.put("kind", record.kind());
        body.put("startedAtEpochMs", record.startedAtEpochMs());
        body.put("eventCount", record.events().size());
        body.put("events", record.events());
        return body;
    }
}
