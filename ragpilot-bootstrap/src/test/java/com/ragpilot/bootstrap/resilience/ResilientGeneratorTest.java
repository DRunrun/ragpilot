package com.ragpilot.bootstrap.resilience;

import com.ragpilot.core.generation.Generator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4.2 验收：超时与下游失败转为 GenerationDegradedException，不裸抛。
 */
class ResilientGeneratorTest {

    @Test
    void 超时转为降级异常() {
        Generator slow = new Generator() {
            @Override
            public Flux<String> stream(String prompt) {
                return Flux.just("a").delayElements(Duration.ofSeconds(2));
            }

            @Override
            public AskTokenUsage lastUsage() {
                return AskTokenUsage.ZERO;
            }
        };
        ResilientGenerator resilient = new ResilientGenerator(slow, Duration.ofMillis(50));
        StepVerifier.create(resilient.stream("q"))
                .expectErrorSatisfies(err -> {
                    assertInstanceOf(GenerationDegradedException.class, err);
                    assertEquals(GenerationDegradedException.REASON_TIMEOUT,
                            ((GenerationDegradedException) err).reasonCode());
                })
                .verify();
    }

    @Test
    void 下游失败转为降级异常() {
        Generator bad = new Generator() {
            @Override
            public Flux<String> stream(String prompt) {
                return Flux.error(new IllegalStateException("boom"));
            }

            @Override
            public AskTokenUsage lastUsage() {
                return AskTokenUsage.ZERO;
            }
        };
        ResilientGenerator resilient = new ResilientGenerator(bad, Duration.ofSeconds(5));
        StepVerifier.create(resilient.stream("q"))
                .expectErrorSatisfies(err -> {
                    assertInstanceOf(GenerationDegradedException.class, err);
                    GenerationDegradedException g = (GenerationDegradedException) err;
                    assertEquals(GenerationDegradedException.REASON_ERROR, g.reasonCode());
                    assertTrue(g.getMessage().contains("boom"));
                })
                .verify();
    }

    @Test
    void 正常流透传() {
        AtomicInteger usageCalls = new AtomicInteger();
        Generator ok = new Generator() {
            @Override
            public Flux<String> stream(String prompt) {
                return Flux.just("hello", " ", "world");
            }

            @Override
            public AskTokenUsage lastUsage() {
                usageCalls.incrementAndGet();
                return new AskTokenUsage(1, 2);
            }
        };
        ResilientGenerator resilient = new ResilientGenerator(ok, Duration.ofSeconds(5));
        StepVerifier.create(resilient.stream("q"))
                .expectNext("hello", " ", "world")
                .verifyComplete();
        assertEquals(new Generator.AskTokenUsage(1, 2), resilient.lastUsage());
    }
}
