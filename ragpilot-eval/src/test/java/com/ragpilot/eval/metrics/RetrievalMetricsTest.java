package com.ragpilot.eval.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecallAtK} / {@link Mrr} 公式单测：构造数据手算期望。
 */
class RetrievalMetricsTest {

    @Test
    void recallAtK_全部命中为1() {
        // top-3 含 docA、docB，期望二者 → 2/2=1
        double r = RecallAtK.of(
                Set.of("docA", "docB"),
                List.of("docA", "x", "docB", "y"),
                3
        );
        assertEquals(1.0, r, 1e-12);
    }

    @Test
    void recallAtK_部分命中按比例() {
        // top-2 只有 docA，期望 {A,B} → 1/2
        double r = RecallAtK.of(
                List.of("docA", "docB"),
                List.of("docA", "noise", "docB"),
                2
        );
        assertEquals(0.5, r, 1e-12);
    }

    @Test
    void recallAtK_期望落在k之外为0() {
        double r = RecallAtK.of(
                Set.of("docB"),
                List.of("docA", "noise", "docB"),
                2
        );
        assertEquals(0.0, r, 1e-12);
    }

    @Test
    void recallAtK_空期望返回NaN() {
        assertTrue(Double.isNaN(RecallAtK.of(Set.of(), List.of("a"), 5)));
    }

    @Test
    void recallAtK_非法k抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> RecallAtK.of(Set.of("a"), List.of("a"), 0));
    }

    @Test
    void mrr_首个相关在第2位() {
        // 相关 docB 在去重序的第 2 位 → 1/2
        double rr = Mrr.reciprocalRank(
                Set.of("docB"),
                List.of("docA", "docB", "docC")
        );
        assertEquals(0.5, rr, 1e-12);
    }

    @Test
    void mrr_第一位命中为1() {
        assertEquals(1.0, Mrr.reciprocalRank(Set.of("docA"), List.of("docA", "docB")), 1e-12);
    }

    @Test
    void mrr_未命中为0() {
        assertEquals(0.0, Mrr.reciprocalRank(Set.of("docZ"), List.of("docA", "docB")), 1e-12);
    }

    @Test
    void mrr_重复docId不重复占位() {
        // 列表 docA, docA, docB → 去重序 A,B；相关 B → rank=2
        assertEquals(0.5, Mrr.reciprocalRank(Set.of("docB"), List.of("docA", "docA", "docB")), 1e-12);
    }

    @Test
    void mrr_mean跳过NaN() {
        double mean = Mrr.mean(List.of(1.0, Double.NaN, 0.5));
        assertEquals(0.75, mean, 1e-12);
    }
}
