package com.ragpilot.ops.token;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求级 Token 计量器：累计 prompt / completion，并按单价估算成本。
 *
 * <p>链路位置：ops 可观测（F3.5）；Ask / Agent 在生成结束后写入，done 事件读出。
 * <p>单价单位：USD / 1K tokens，来自配置，禁止魔法数散落业务代码。
 */
public final class TokenMeter {

    private final ConcurrentHashMap<String, Counters> byRequest = new ConcurrentHashMap<>();
    private volatile double promptPricePer1k;
    private volatile double completionPricePer1k;

    /**
     * @param promptPricePer1k     每 1K prompt token 美元单价
     * @param completionPricePer1k 每 1K completion token 美元单价
     */
    public TokenMeter(double promptPricePer1k, double completionPricePer1k) {
        if (promptPricePer1k < 0 || completionPricePer1k < 0) {
            throw new IllegalArgumentException("prices must be >= 0");
        }
        this.promptPricePer1k = promptPricePer1k;
        this.completionPricePer1k = completionPricePer1k;
    }

    /**
     * Admin Overlay 热更新单价（ADM-7）；无需重启。
     */
    public void updatePrices(double promptPricePer1k, double completionPricePer1k) {
        if (promptPricePer1k < 0 || completionPricePer1k < 0) {
            throw new IllegalArgumentException("prices must be >= 0");
        }
        this.promptPricePer1k = promptPricePer1k;
        this.completionPricePer1k = completionPricePer1k;
    }

    /** 本地免费模型默认单价为 0。 */
    public TokenMeter() {
        this(0.0, 0.0);
    }

    /**
     * 累加一次用量（同一 requestId 可多次，如多轮 Agent）。
     */
    public void add(String requestId, int promptTokens, int completionTokens) {
        Objects.requireNonNull(requestId, "requestId");
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts must be >= 0");
        }
        Counters c = byRequest.computeIfAbsent(requestId, id -> new Counters());
        c.prompt.addAndGet(promptTokens);
        c.completion.addAndGet(completionTokens);
    }

    public Usage usage(String requestId) {
        Counters c = byRequest.get(requestId);
        if (c == null) {
            return Usage.ZERO;
        }
        return new Usage(c.prompt.get(), c.completion.get());
    }

    /**
     * 估算美元成本：{@code prompt/1000*p + completion/1000*c}。
     */
    public double estimateCostUsd(String requestId) {
        Usage u = usage(requestId);
        return u.prompt() / 1000.0 * promptPricePer1k
                + u.completion() / 1000.0 * completionPricePer1k;
    }

    public double promptPricePer1k() {
        return promptPricePer1k;
    }

    public double completionPricePer1k() {
        return completionPricePer1k;
    }

    /**
     * @param prompt     累计 prompt tokens
     * @param completion 累计 completion tokens
     */
    public record Usage(int prompt, int completion) {
        public static final Usage ZERO = new Usage(0, 0);

        public int total() {
            return prompt + completion;
        }
    }

    private static final class Counters {
        private final AtomicInteger prompt = new AtomicInteger();
        private final AtomicInteger completion = new AtomicInteger();
    }
}
