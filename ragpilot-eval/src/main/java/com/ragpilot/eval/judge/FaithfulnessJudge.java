package com.ragpilot.eval.judge;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faithfulness（忠实度）Judge：答案陈述是否被检索上下文支持。
 *
 * <p>链路位置：评测体系的生成质量指标（M2）；temperature=0 的本地模型打结构化 JSON 分。
 * <p>失败（空响应 / 无法解析）时重试 1 次；仍失败则抛 {@link IllegalStateException}。
 */
public final class FaithfulnessJudge {

    /** Prompt 版本：改量表或指令必须递增，便于报告对齐。 */
    public static final String PROMPT_VERSION = "faithfulness-v1";

    private static final Pattern SCORE = Pattern.compile(
            "\"score\"\\s*:\\s*([01](?:\\.\\d+)?|\\.\\d+)");
    private static final Pattern REASON = Pattern.compile(
            "\"reason\"\\s*:\\s*\"([^\"]*)\"");

    private final JudgeLlm llm;

    public FaithfulnessJudge(JudgeLlm llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    /**
     * 对答案做忠实度打分。
     *
     * @param question 用户问题（提供语境，不直接打分）
     * @param answer   模型生成的答案
     * @param contexts 检索到的上下文块文本；空列表时仍可打分（通常应很低）
     * @return 结构化分数
     */
    public JudgeScore score(String question, String answer, List<String> contexts) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(contexts, "contexts");

        String prompt = buildPrompt(question, answer, contexts);
        return completeWithRetry(prompt);
    }

    String buildPrompt(String question, String answer, List<String> contexts) {
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < contexts.size(); i++) {
            ctx.append('[').append(i + 1).append("] ").append(contexts.get(i)).append('\n');
        }
        return """
                You are a strict faithfulness judge for a RAG system.
                Score how well the ANSWER is supported by the CONTEXT only.
                Ignore world knowledge. Hallucinations must score low.
                Output ONLY one JSON object: {"score":0.0-1.0,"reason":"..."}
                Prompt-Version: %s

                QUESTION:
                %s

                CONTEXT:
                %s
                ANSWER:
                %s
                """.formatted(PROMPT_VERSION, question, ctx, answer);
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
                "FaithfulnessJudge failed after 1 retry: " + (last == null ? "" : last.getMessage()),
                last);
    }

    static JudgeScore parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("empty judge response");
        }
        // 容错：模型偶发包裹 ```json ... ```
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
