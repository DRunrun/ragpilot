package com.ragpilot.bootstrap.agent;

import com.ragpilot.core.agent.AnswerSynthesizer;
import com.ragpilot.core.agent.KnowledgeFirstDecisionMaker;
import com.ragpilot.core.domain.RetrievedChunk;
import com.ragpilot.core.generation.Generator;
import com.ragpilot.core.generation.PromptBuilder;
import com.ragpilot.core.retrieval.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Agent 答案合成：复用 RAG 的 PromptBuilder + Generator，把检索证据变成自然语言回答。
 *
 * <p>链路位置：Agent finish 前（bootstrap 适配器）；core 只认 {@link AnswerSynthesizer}。
 * <p>为什么再查一次库：工具 Observation 是给人读的 HITS 摘要，没有结构化 {@link RetrievedChunk}；
 * 再 retrieve 一次可拼与 {@code /ask} 一致的带引用约束 Prompt。代价是多一次检索，Demo 可接受。
 */
public final class RagPromptAnswerSynthesizer implements AnswerSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(RagPromptAnswerSynthesizer.class);

    private final Retriever retriever;
    private final PromptBuilder promptBuilder;
    private final Generator generator;
    private final int topK;

    /**
     * @param retriever     与 knowledge_search 同源检索器
     * @param promptBuilder 当前激活 Prompt 模板
     * @param generator     流式生成（含韧性包装）
     * @param topK          再检索条数，应与 ragpilot.retrieval.top-k 一致
     */
    public RagPromptAnswerSynthesizer(
            Retriever retriever,
            PromptBuilder promptBuilder,
            Generator generator,
            int topK
    ) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.generator = Objects.requireNonNull(generator, "generator");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }
        this.topK = topK;
    }

    @Override
    public String synthesize(String question, String evidence) {
        if (evidence == null || evidence.isBlank() || evidence.startsWith("NO_HITS")) {
            return KnowledgeFirstDecisionMaker.NO_EVIDENCE_ANSWER;
        }

        List<RetrievedChunk> hits = retriever.retrieve(question == null ? "" : question, topK);
        if (hits == null || hits.isEmpty()) {
            log.info("Agent synthesize: re-retrieve empty, refuse");
            return KnowledgeFirstDecisionMaker.NO_EVIDENCE_ANSWER;
        }

        return promptBuilder.build(question, hits)
                .map(this::generateBlocking)
                .filter(s -> s != null && !s.isBlank())
                .orElse(KnowledgeFirstDecisionMaker.NO_EVIDENCE_ANSWER);
    }

    /** Agent API 是同步的，这里把流式 token 收成整段文本。 */
    private String generateBlocking(String prompt) {
        log.info("Agent synthesize: invoking generator, promptVersion={}", promptBuilder.version());
        List<String> tokens = generator.stream(prompt).collectList().block();
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }
        return tokens.stream().collect(Collectors.joining());
    }
}
