package com.ragpilot.eval.judge;

/**
 * Judge 用的 LLM 补全端口：eval 模块不依赖 Spring / WebClient，由 bootstrap 注入实现。
 *
 * <p>链路位置：LLM-as-Judge 的基础设施边界。调用方必须保证 temperature=0（可复现）。
 */
@FunctionalInterface
public interface JudgeLlm {

    /**
     * 同步补全一次。
     *
     * @param prompt 完整 Judge Prompt（含量表与待评文本）
     * @return 模型原始文本；实现方不得返回 null（可返回空串，由上层判失败重试）
     */
    String complete(String prompt);
}
