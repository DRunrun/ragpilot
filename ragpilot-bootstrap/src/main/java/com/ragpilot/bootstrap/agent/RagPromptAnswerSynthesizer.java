package com.ragpilot.bootstrap.agent;

import com.ragpilot.core.agent.AnswerSynthesizer;
import com.ragpilot.core.agent.KnowledgeFirstDecisionMaker;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.generation.Generator;
import com.ragpilot.core.generation.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Agent 答案合成：复用 RAG 的 PromptBuilder + Generator，把检索证据变成自然语言回答。
 *
 * <p>链路位置：Agent finish 前（bootstrap 适配器）；core 只认 {@link AnswerSynthesizer}。
 *
 * <p>证据来源：直接消费 knowledge_search <b>首次检索</b>沿 AgentStep 传回的结构化块，
 * 不再自己持 Retriever 重查一遍——旧版同一问题检索两次，延迟翻倍，
 * 且 Admin 热切换检索模式时两次结果可能不一致（证据与答案对不上）。
 * 无证据（空列表）时保持拒答口径，与 core 侧 {@link KnowledgeFirstDecisionMaker} 的
 * {@code NO_EVIDENCE_ANSWER} 一致。
 */
public final class RagPromptAnswerSynthesizer implements AnswerSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(RagPromptAnswerSynthesizer.class);

    private final PromptBuilder promptBuilder;
    private final Generator generator;

    /**
     * @param promptBuilder 当前激活 Prompt 模板
     * @param generator     流式生成（含韧性包装）
     */
    public RagPromptAnswerSynthesizer(
            PromptBuilder promptBuilder,
            Generator generator
    ) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    @Override
    public String synthesize(String question, List<RetrievedChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return KnowledgeFirstDecisionMaker.NO_EVIDENCE_ANSWER;
        }

        return promptBuilder.build(question == null ? "" : question, evidence)
                .map(this::generateBlocking)
                .filter(s -> s != null && !s.isBlank())
                .orElse(KnowledgeFirstDecisionMaker.NO_EVIDENCE_ANSWER);
    }

    /** Agent API 是同步的，这里把流式 token 收成整段文本。 */
    private String generateBlocking(String prompt) {
        log.info("Agent synthesize: invoking generator, promptVersion={}", promptBuilder.version());
        List<String> tokens = generator.streamTokens(prompt).collectList().block();
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }
        return tokens.stream().collect(Collectors.joining());
    }
}
