package com.ragpilot.ops.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 Trace 记录器：按 traceId 追加事件，供调试 API 查询。
 *
 * <p>链路位置：ops 可观测核心（F3.4）。M3 用进程内 ConcurrentHashMap 即可；
 * 不引入外部 APM，面试可讲清「自研轻量 Trace」。
 */
public final class TraceRecorder {

    /** 摘要最大字符，避免把整篇上下文塞进 Trace。 */
    public static final int SUMMARY_MAX = 500;

    private final ConcurrentHashMap<String, TraceRecord> traces = new ConcurrentHashMap<>();

    /**
     * 开始一条新 Trace（可重复调用，已存在则忽略）。
     */
    public void start(String traceId, String kind) {
        Objects.requireNonNull(traceId, "traceId");
        traces.computeIfAbsent(traceId, id -> new TraceRecord(id, kind == null ? "ask" : kind));
    }

    /**
     * 追加事件；若 trace 不存在则自动 start。
     */
    public void record(String traceId, TraceEvent event) {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(event, "event");
        TraceRecord record = traces.computeIfAbsent(traceId, id -> new TraceRecord(id, "ask"));
        record.add(event);
    }

    /**
     * 便捷：记录 Agent 一步。
     */
    public void recordAgentStep(String traceId, int stepIndex, String thought, String action,
                                String actionInput, String observation, long durationMs,
                                boolean failed, boolean timedOut) {
        record(traceId, new TraceEvent(
                "agent_step",
                System.currentTimeMillis(),
                durationMs,
                action,
                summarize(thought + " | input=" + actionInput),
                summarize(observation),
                null,
                null,
                Map.of(
                        "step", stepIndex,
                        "failed", failed,
                        "timedOut", timedOut
                )
        ));
    }

    public Optional<TraceRecord> find(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }

    /**
     * 最近 N 条 Trace（按 startedAt 倒序），供 Admin 列表（ADM-10）。
     */
    public List<TraceRecord> listRecent(int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        return traces.values().stream()
                .map(TraceRecord::snapshot)
                .sorted((a, b) -> Long.compare(b.startedAtEpochMs(), a.startedAtEpochMs()))
                .limit(lim)
                .toList();
    }

    /** 截断摘要，去掉换行噪音。 */
    public static String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= SUMMARY_MAX ? flat : flat.substring(0, SUMMARY_MAX) + "…";
    }

    /**
     * 一条完整 Trace。
     *
     * @param traceId  追踪 ID
     * @param kind     ask / agent 等
     * @param events   有序事件列表
     * @param startedAtEpochMs 创建时间
     */
    public record TraceRecord(String traceId, String kind, List<TraceEvent> events, long startedAtEpochMs) {
        TraceRecord(String traceId, String kind) {
            this(traceId, kind, new ArrayList<>(), System.currentTimeMillis());
        }

        synchronized void add(TraceEvent event) {
            events.add(event);
        }

        /** 对外返回不可变快照。 */
        public synchronized TraceRecord snapshot() {
            return new TraceRecord(traceId, kind, List.copyOf(events), startedAtEpochMs);
        }
    }
}
