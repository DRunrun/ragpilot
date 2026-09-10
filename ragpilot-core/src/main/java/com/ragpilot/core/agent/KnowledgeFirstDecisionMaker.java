package com.ragpilot.core.agent;

import com.ragpilot.core.agent.tools.KnowledgeSearchTool;

import java.util.List;
import java.util.Objects;

/**
 * 轻量决策器：先自主调用知识库检索，再调用 {@link AnswerSynthesizer} 用模型组织答案后 finish。
 *
 * <p>链路位置：Agent 默认 DecisionMaker（bootstrap 组装）；生产可整段换成 LLM 规划器，端口不变。
 * <p>策略：
 * <ol>
 *   <li>尚未检索且注册了 {@code knowledge_search} → 先检索</li>
 *   <li>已有检索 Observation → 交给合成器生成自然语言答案并 finish</li>
 * </ol>
 * 不再把 HITS 原文直接当答案（避免「只回片段、不调模型」）。
 */
public final class KnowledgeFirstDecisionMaker implements ReActAgent.DecisionMaker {

    /** 无证据时的统一拒答文案（与 RAG RefusalPolicy 口径对齐）。 */
    public static final String NO_EVIDENCE_ANSWER = "根据现有资料无法回答";

    private final ToolRegistry registry;
    private final AnswerSynthesizer synthesizer;

    /**
     * @param registry    工具注册表（至少应含 knowledge_search）
     * @param synthesizer 检索后的答案合成（通常接 Generator）；不得为 null
     */
    public KnowledgeFirstDecisionMaker(ToolRegistry registry, AnswerSynthesizer synthesizer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
    }

    /**
     * 兼容旧单测：无合成器时把证据截断后当答案（仅测试/降级，生产勿用）。
     *
     * @deprecated 请注入 {@link AnswerSynthesizer}
     */
    @Deprecated
    public KnowledgeFirstDecisionMaker(ToolRegistry registry) {
        this(registry, (question, evidence) -> {
            if (evidence == null || evidence.isBlank() || evidence.startsWith("NO_HITS")) {
                return NO_EVIDENCE_ANSWER;
            }
            return "基于检索结果：\n" + truncate(evidence, 800);
        });
    }

    @Override
    public ReActAgent.Decision decide(String question, List<AgentStep> history) {
        boolean alreadySearched = history.stream()
                .anyMatch(s -> KnowledgeSearchTool.NAME.equalsIgnoreCase(s.action()));

        if (!alreadySearched && registry.find(KnowledgeSearchTool.NAME).isPresent()) {
            Tool search = registry.find(KnowledgeSearchTool.NAME).orElseThrow();
            String thought = "Available tool '" + search.name() + "': " + search.description()
                    + " — question looks factual, search first.";
            String input = "{\"query\":\"" + escape(question) + "\"}";
            return new ReActAgent.Decision(thought, KnowledgeSearchTool.NAME, input);
        }

        String evidence = history.isEmpty() ? ""
                : history.get(history.size() - 1).observation();
        String answer;
        if (evidence == null || evidence.isBlank() || evidence.startsWith("NO_HITS")) {
            answer = NO_EVIDENCE_ANSWER;
        } else {
            // 有命中：调合成器（bootstrap 侧会再检索 + Prompt + LLM），不再原文甩 HITS
            try {
                String synthesized = synthesizer.synthesize(
                        question == null ? "" : question, evidence);
                answer = (synthesized == null || synthesized.isBlank())
                        ? NO_EVIDENCE_ANSWER
                        : synthesized.strip();
            } catch (RuntimeException ex) {
                // 合成失败时给出可读降级，避免 Agent 整请求 500
                answer = "生成答案失败：" + (ex.getMessage() == null
                        ? ex.getClass().getSimpleName()
                        : ex.getMessage());
            }
        }
        return new ReActAgent.Decision(
                "检索已完成，调用模型组织答案",
                AgentStep.FINISH,
                answer
        );
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
