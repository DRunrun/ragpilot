package com.ragpilot.bootstrap.resilience;

import com.ragpilot.core.generation.GenerationEvent;
import com.ragpilot.core.generation.Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * 带超时的生成器装饰器：下游 LLM 超时/失败统一转为 {@link GenerationDegradedException}。
 *
 * <p>链路位置：bootstrap 生成适配层（F4.2）；AskController 捕获后发 refused + done，不 500。
 */
public final class ResilientGenerator implements Generator {

    private static final Logger log = LoggerFactory.getLogger(ResilientGenerator.class);

    private final Generator delegate;
    private final Duration timeout;

    /**
     * @param delegate 真实生成器（如 LmStudioGenerator）
     * @param timeout  整次流式生成的墙钟超时
     */
    public ResilientGenerator(Generator delegate, Duration timeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public Flux<GenerationEvent> stream(String prompt) {
        return delegate.stream(prompt)
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, e -> {
                    log.warn("Generation timed out after {}", timeout);
                    return new GenerationDegradedException(
                            GenerationDegradedException.REASON_TIMEOUT,
                            "生成超时（>" + timeout.toSeconds() + "s），请稍后重试",
                            e
                    );
                })
                .onErrorMap(err -> !(err instanceof GenerationDegradedException), err -> {
                    log.warn("Generation failed: {}", err.toString());
                    return new GenerationDegradedException(
                            GenerationDegradedException.REASON_ERROR,
                            "生成失败：" + (err.getMessage() == null
                                    ? err.getClass().getSimpleName()
                                    : err.getMessage()),
                            err
                    );
                });
    }

    /** 测试/诊断用。 */
    public Duration timeout() {
        return timeout;
    }
}
