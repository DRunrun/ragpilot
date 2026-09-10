package com.ragpilot.eval.judge;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answer Relevancy Judge：答案是否针对问题（相关性）。
 *
 * <p>链路位置：评测体系的生成质量指标（M2）；与 Faithfulness 互补——
 * 忠实但答非所问 → Faithfulness 高、Relevancy 低。
 * <p>失败重试 1 次；输出结构化 JSON {@code {"score":0-1,"reason":"..."}}。
 */
public final class RelevancyJudge {

    /** Prompt 版本：改量表或指令必须递增。 */
    public static final String PROMPT_VERSION = "relevancy-v1";

    private static final Pattern SCORE = Pattern.compile(
            "\"score\"\\s*:\\s*([01](?:\\.\\d+)?|\\.\\d+)");
    private static final Pattern REASON = Pattern.compile(
            "\"reason\"\\s*:\\s*\"([^\"]*)\"");

    private final JudgeLlm llm;

    public RelevancyJudge(JudgeLlm llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    /**
     * 对答案做相关性打分。
     *
     * @param question 用户问题
     * @param answer   模型生成的答案
     * @return 结构化分数
     */
    public JudgeScore score(String question, String answer) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(answer, "answer");
        String prompt = buildPrompt(question, answer);
        return completeWithRetry(prompt);
    }

    String buildPrompt(String question, String answer) {
        return """
                You are a strict answer-relevancy judge.
                Score how directly the ANSWER addresses the QUESTION.
                Off-topic or empty answers must score low.
                Output ONLY one JSON object: {"score":0.0-1.0,"reason":"..."}
                Prompt-Version: %s

                QUESTION:
                %s

                ANSWER:
                %s
                """.formatted(PROMPT_VERSION, question, answer);
    }

    private JudgeScore completeWithRetry(String prompt) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String raw = llm.complete(prompt);
                return parse(raw);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw new IllegalStateException(
                "RelevancyJudge failed after 1 retry: " + (last == null ? "" : last.getMessage()),
                last);
    }

    static JudgeScore parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("empty judge response");
        }
        String text = raw.replace("```json", "").replace("```", "").strip();
        Matcher sm = SCORE.matcher(text);
        if (!sm.find()) {
            throw new IllegalStateException("missing score in: " + abbreviate(text));
        }
        double score = Double.parseDouble(sm.group(1));
        if (score < 0.0 || score > 1.0) {
            throw new IllegalStateException("score out of range: " + score);
        }
        String reason = "";
        Matcher rm = REASON.matcher(text);
        if (rm.find()) {
            reason = rm.group(1);
        }
        return new JudgeScore(score, reason, PROMPT_VERSION);
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
