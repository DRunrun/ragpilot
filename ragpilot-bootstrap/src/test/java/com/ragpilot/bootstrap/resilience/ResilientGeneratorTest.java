package com.ragpilot.bootstrap.resilience;

import com.ragpilot.core.generation.GenerationEvent;
import com.ragpilot.core.generation.Generator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4.2 验收：超时与下游失败转为 GenerationDegradedException，不裸抛。
 * <p>Generator 契约改为事件流后同步更新：fake 直接产出 {@link GenerationEvent}。
 */
class ResilientGeneratorTest {

    @Test
    void 超时转为降级异常() {
        Generator slow = prompt ->
                Flux.just((GenerationEvent) new GenerationEvent.Token("a"))
                        .delayElements(Duration.ofSeconds(2));
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
        Generator bad = prompt -> Flux.error(new IllegalStateException("boom"));
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
    void 正常流透传且用量随流返回() {
        Generator ok = prompt -> Flux.just(
                new GenerationEvent.Token("hello"),
                new GenerationEvent.Token(" "),
                new GenerationEvent.Token("world"),
                new GenerationEvent.Completed(new Generator.AskTokenUsage(1, 2)));
        ResilientGenerator resilient = new ResilientGenerator(ok, Duration.ofSeconds(5));

        // 事件全量透传：4 个元素，最后一个是 Completed
        StepVerifier.create(resilient.stream("q").collectList())
                .assertNext(list -> {
                    assertEquals(4, list.size());
                    GenerationEvent last = list.get(list.size() - 1);
                    assertInstanceOf(GenerationEvent.Completed.class, last);
                    assertEquals(new Generator.AskTokenUsage(1, 2),
                            ((GenerationEvent.Completed) last).usage());
                })
                .verifyComplete();

        // 便捷视图只留正文
        List<String> tokens = resilient.streamTokens("q").collectList().block();
        assertEquals(List.of("hello", " ", "world"), tokens);
    }
}
