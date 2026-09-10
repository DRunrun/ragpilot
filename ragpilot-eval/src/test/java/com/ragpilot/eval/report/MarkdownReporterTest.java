package com.ragpilot.eval.report;

import com.ragpilot.eval.runner.EvalRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarkdownReporter 单测：表头固定、含变化百分比。
 */
class MarkdownReporterTest {

    @Test
    void 生成含固定表头与百分比的报告() {
        EvalRunner.EvalReport vector = new EvalRunner.EvalReport(
                "VECTOR", 5, "t0", 50, 0.80, 0.75, 0.70, 0.72, 0.90, List.of());
        EvalRunner.EvalReport hybrid = new EvalRunner.EvalReport(
                "HYBRID", 5, "t1", 50, 0.88, 0.82, 0.74, 0.76, 0.91, List.of());

        String md = new MarkdownReporter().render(List.of(
                new MarkdownReporter.SetupRow("VECTOR", vector, "baseline"),
                new MarkdownReporter.SetupRow("HYBRID", hybrid, "BM25+RRF")
        ));

        assertTrue(md.contains("| setup | Recall@5 | Faithfulness | AnswerRelevancy | notes |"));
        assertTrue(md.contains("| VECTOR |"));
        assertTrue(md.contains("| HYBRID |"));
        assertTrue(md.contains("+10.0%")); // Recall 0.80 → 0.88
        assertTrue(md.contains("## Conclusion"));
    }
}
