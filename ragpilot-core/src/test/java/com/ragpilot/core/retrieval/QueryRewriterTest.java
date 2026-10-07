package com.ragpilot.core.retrieval;

import com.ragpilot.core.generation.GenerationEvent;
import com.ragpilot.core.generation.Generator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多轮检索查询改写：成功改写、各类失败回退原文、首轮不发 LLM 调用。
 */
class QueryRewriterTest {

    private static final Duration TINY = Duration.ofMillis(200);

    /** 固定输出的假生成器，并记录调用次数。 */
    private static Generator stub(String output, AtomicInteger calls) {
        return prompt -> {
            calls.incrementAndGet();
            return Flux.just(output).map(text -> (GenerationEvent) new GenerationEvent.Token(text));
        };
    }

    @Test
    void 改写成功时返回独立问句() {
        AtomicInteger calls = new AtomicInteger();
        QueryRewriter rewriter = new QueryRewriter(
                stub("Spring Bean 的缓存是怎么配置的", calls), TINY);

        QueryRewriter.RewriteResult result = rewriter.rewrite(
                "user: Spring Bean 生命周期有哪些阶段？\nassistant: 实例化、填充、销毁",
                "它怎么配置缓存");

        assertEquals(1, calls.get());
        assertTrue(result.rewritten());
        assertEquals("Spring Bean 的缓存是怎么配置的", result.query());
        assertNull(result.fallbackReason());
    }

    @Test
    void 剥掉前缀与引号噪声() {
        AtomicInteger calls = new AtomicInteger();
        QueryRewriter rewriter = new QueryRewriter(
                stub("「改写后的问题：」Bean销毁回调有哪些\n多余解释", calls), TINY);

        QueryRewriter.RewriteResult result = rewriter.rewrite("user: a\nassistant: b", "它呢");

        // 第一行剥前缀与「」引号；后续解释行被丢弃
        assertEquals("Bean销毁回调有哪些", result.query());
    }

    @Test
    void 无历史不发LLM调用() {
        AtomicInteger calls = new AtomicInteger();
        Generator boom = prompt -> {
            calls.incrementAndGet();
            return Flux.error(new IllegalStateException("不应调用"));
        };
        QueryRewriter rewriter = new QueryRewriter(boom, TINY);

        QueryRewriter.RewriteResult result = rewriter.rewrite("", "Spring Bean 生命周期？");

        assertEquals(0, calls.get(), "单轮场景不应烧一次 LLM 调用");
        assertFalse(result.rewritten());
        assertEquals(QueryRewriter.FALLBACK_NO_HISTORY, result.fallbackReason());
        assertEquals("Spring Bean 生命周期？", result.query());
    }

    @Test
    void 生成异常回退原文() {
        Generator failing = prompt -> Flux.error(new IllegalStateException("gateway down"));
        QueryRewriter rewriter = new QueryRewriter(failing, TINY);

        QueryRewriter.RewriteResult result = rewriter.rewrite("user: a", "它呢");

        assertFalse(result.rewritten());
        assertEquals("它呢", result.query());
        assertTrue(result.fallbackReason().startsWith(QueryRewriter.FALLBACK_ERROR),
                "异常应回退并带 ERROR 原因，实际=" + result.fallbackReason());
    }

    @Test
    void 超时回退原文() {
        // 流永不完成：命中 Flux.timeout 后走异常回退路径
        Generator slow = prompt -> Flux.<GenerationEvent>never();
        QueryRewriter rewriter = new QueryRewriter(slow, Duration.ofMillis(80));

        QueryRewriter.RewriteResult result = rewriter.rewrite("user: a", "它的缓存呢");

        assertFalse(result.rewritten());
        assertEquals("它的缓存呢", result.query());
        assertTrue(result.fallbackReason().startsWith(QueryRewriter.FALLBACK_ERROR));
    }

    @Test
    void 空输出与超长输出都回退() {
        QueryRewriter empty = new QueryRewriter(stub("   ", new AtomicInteger()), TINY);
        QueryRewriter.RewriteResult r1 = empty.rewrite("user: a", "它呢");
        assertEquals(QueryRewriter.FALLBACK_EMPTY_OUTPUT, r1.fallbackReason());
        assertEquals("它呢", r1.query());

        String tooLong = "问".repeat(QueryRewriter.MAX_REWRITE_LENGTH + 1);
        QueryRewriter longOut = new QueryRewriter(stub(tooLong, new AtomicInteger()), TINY);
        QueryRewriter.RewriteResult r2 = longOut.rewrite("user: a", "它呢");
        assertEquals(QueryRewriter.FALLBACK_TOO_LONG, r2.fallbackReason());
        assertEquals("它呢", r2.query());
    }

    @Test
    void 输出与原问题一致视为未改写() {
        QueryRewriter rewriter = new QueryRewriter(
                stub("Spring Bean 生命周期？", new AtomicInteger()), TINY);

        QueryRewriter.RewriteResult result = rewriter.rewrite("user: a", "Spring Bean 生命周期？");

        assertFalse(result.rewritten());
        assertEquals(QueryRewriter.FALLBACK_UNCHANGED, result.fallbackReason());
        assertEquals("Spring Bean 生命周期？", result.query());
    }
}
