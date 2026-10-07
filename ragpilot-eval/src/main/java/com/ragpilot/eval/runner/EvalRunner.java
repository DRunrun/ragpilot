package com.ragpilot.eval.runner;

import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.retrieval.RetrievalMode;
import com.ragpilot.eval.dataset.GoldenQuestion;
import com.ragpilot.eval.judge.JudgeScore;
import com.ragpilot.eval.metrics.Mrr;
import com.ragpilot.eval.metrics.RecallAtK;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 评测 Runner：按检索模式跑黄金集，聚合 Recall / MRR /（可选）Judge 分数。
 *
 * <p>链路位置：评测体系编排中枢（M2）。通过端口依赖检索与生成，eval 不碰 Spring。
 */
public final class EvalRunner {

    /** 检索端口：bootstrap 把 ModeAwareRetriever 适配进来。 */
    @FunctionalInterface
    public interface RetrievalPort {
        List<RetrievedChunk> retrieve(String query, int topK, RetrievalMode mode);
    }

    /**
     * 生成端口：返回答案文本；拒答时 empty。
     * 实现方负责 Prompt 组装与 RefusalPolicy。
     */
    @FunctionalInterface
    public interface GenerationPort {
        Optional<String> answer(String question, List<RetrievedChunk> hits);
    }

    /** Judge 端口：对单题打 Faithfulness / Relevancy。 */
    public interface JudgePort {
        JudgeScore faithfulness(String question, String answer, List<String> contexts);

        JudgeScore relevancy(String question, String answer);
    }

    private final RetrievalPort retrieval;
    private final GenerationPort generation;
    private final JudgePort judge;

    /**
     * @param retrieval  必填
     * @param generation 可为 null（只跑检索指标）
     * @param judge      可为 null（跳过 LLM Judge）
     */
    public EvalRunner(RetrievalPort retrieval, GenerationPort generation, JudgePort judge) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.generation = generation;
        this.judge = judge;
    }

    /**
     * 跑全量黄金集。
     *
     * @param mode   检索模式
     * @param golden 题目列表
     * @param topK   Recall@k 的 k，同时作为检索 topK
     * @return 结构化报告（可再序列化为 JSON）
     */
    public EvalReport run(RetrievalMode mode, List<GoldenQuestion> golden, int topK) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(golden, "golden");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }

        List<EvalCaseResult> cases = new ArrayList<>(golden.size());
        List<Double> recalls = new ArrayList<>();
        List<Double> rrs = new ArrayList<>();
        List<Double> faiths = new ArrayList<>();
        List<Double> relevances = new ArrayList<>();
        int refuseExpected = 0;
        int refuseCorrect = 0;

        for (GoldenQuestion q : golden) {
            List<RetrievedChunk> hits = retrieval.retrieve(q.question(), topK, mode);
            List<String> rankedDocs = hits.stream().map(h -> h.chunk().docId()).toList();

            Double recall = null;
            Double rr = null;
            if (!q.expectedDocIds().isEmpty()) {
                double r = RecallAtK.of(q.expectedDocIds(), rankedDocs, topK);
                double m = Mrr.reciprocalRank(q.expectedDocIds(), rankedDocs);
                recall = r;
                rr = m;
                recalls.add(r);
                rrs.add(m);
            }

            boolean refused = false;
            String answer = null;
            Double faith = null;
            Double relevancy = null;
            String faithReason = null;
            String relevancyReason = null;

            if (generation != null) {
                Optional<String> ans = generation.answer(q.question(), hits);
                if (ans.isEmpty()) {
                    refused = true;
                } else {
                    answer = ans.get();
                    if (judge != null) {
                        List<String> ctx = hits.stream().map(h -> h.chunk().content()).toList();
                        JudgeScore fs = judge.faithfulness(q.question(), answer, ctx);
                        JudgeScore rs = judge.relevancy(q.question(), answer);
                        faith = fs.score();
                        relevancy = rs.score();
                        faithReason = fs.reason();
                        relevancyReason = rs.reason();
                        faiths.add(faith);
                        relevances.add(relevancy);
                    }
                }
            }

            if (q.shouldRefuse()) {
                refuseExpected++;
                // 检索为空或生成拒答，都算拒答正确（无证据路径）
                if (hits.isEmpty() || refused) {
                    refuseCorrect++;
                }
            }

            cases.add(new EvalCaseResult(
                    q.id(),
                    q.question(),
                    rankedDocs,
                    hits.stream().map(h -> h.chunk().id()).toList(),
                    recall,
                    rr,
                    refused,
                    answer,
                    faith,
                    relevancy,
                    faithReason,
                    relevancyReason
            ));
        }

        return new EvalReport(
                mode.name(),
                topK,
                Instant.now().toString(),
                golden.size(),
                mean(recalls),
                Mrr.mean(rrs),
                mean(faiths),
                mean(relevances),
                refuseExpected == 0 ? null : (double) refuseCorrect / refuseExpected,
                List.copyOf(cases),
                null
        );
    }

    private static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /**
     * 全量报告摘要 + 逐题明细。
     *
     * <p>label：消融臂标识（如 {@code chunk=HEADING/512/64}）。同一检索模式下
     * 换分块参数重灌再评，两个臂的 mode 相同、靠 label 区分，否则报告无法归因。
     * 旧 JSON 报告无此字段，反序列化后为 null，向后兼容。
     */
    public record EvalReport(
            String mode,
            int topK,
            String generatedAt,
            int questionCount,
            Double recallAtK,
            Double mrr,
            Double faithfulness,
            Double answerRelevancy,
            Double refusalAccuracy,
            List<EvalCaseResult> cases,
            String label
    ) {
        /** 复制并贴上消融臂标签（CLI 重灌场景在 runner 外部打标，不动评测核心逻辑）。 */
        public EvalReport withLabel(String newLabel) {
            return new EvalReport(mode, topK, generatedAt, questionCount, recallAtK, mrr,
                    faithfulness, answerRelevancy, refusalAccuracy, cases, newLabel);
        }
    }

    /**
     * 单题结果。
     */
    public record EvalCaseResult(
            String id,
            String question,
            List<String> retrievedDocIds,
            List<String> retrievedChunkIds,
            Double recallAtK,
            Double mrr,
            boolean refused,
            String answer,
            Double faithfulness,
            Double answerRelevancy,
            String faithfulnessReason,
            String answerRelevancyReason
    ) {
    }
}
