package com.ragpilot.bootstrap.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 进程内日志环形缓冲（ADM-10）：供 Admin 简易 tail，不落盘、不引入外部 APM。
 */
public final class LogRingBuffer {

    private final int capacity;
    private final ConcurrentLinkedDeque<String> lines = new ConcurrentLinkedDeque<>();

    public LogRingBuffer(int capacity) {
        this.capacity = Math.max(capacity, 50);
    }

    public void append(String line) {
        if (line == null) {
            return;
        }
        lines.addLast(Instant.now() + " " + line);
        while (lines.size() > capacity) {
            lines.pollFirst();
        }
    }

    public List<String> tail(int n) {
        int lim = Math.min(Math.max(n, 1), capacity);
        List<String> all = new ArrayList<>(lines);
        if (all.size() <= lim) {
            return all;
        }
        return all.subList(all.size() - lim, all.size());
    }

    public int size() {
        return lines.size();
    }
}
