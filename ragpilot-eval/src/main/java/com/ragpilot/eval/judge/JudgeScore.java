package com.ragpilot.eval.judge;

/**
 * Judge 单次打分结果。
 *
 * @param score   0.0～1.0，越高越好
 * @param reason  模型给出的简短理由（解析失败时为错误摘要）
 * @param promptVersion Judge Prompt 版本号，变更 Prompt 必须升版本
 */
public record JudgeScore(double score, String reason, String promptVersion) {

    public JudgeScore {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in [0,1], got " + score);
        }
        reason = reason == null ? "" : reason;
        promptVersion = promptVersion == null ? "" : promptVersion;
    }
}
