package com.ragpilot.bootstrap.config;

import com.ragpilot.bootstrap.agent.HttpGetTool;
import com.ragpilot.bootstrap.agent.RagPromptAnswerSynthesizer;
import com.ragpilot.bootstrap.retrieval.ModeAwareRetriever;
import com.ragpilot.core.agent.AnswerSynthesizer;
import com.ragpilot.core.agent.KnowledgeFirstDecisionMaker;
import com.ragpilot.core.agent.ReActAgent;
import com.ragpilot.core.agent.ToolRegistry;
import com.ragpilot.core.agent.tools.KnowledgeSearchTool;
import com.ragpilot.core.generation.Generator;
import com.ragpilot.core.generation.PromptBuilder;
import com.ragpilot.ops.token.TokenMeter;
import com.ragpilot.ops.trace.TraceRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M3 Agent / Trace / Token 组装。
 *
 * <p>链路位置：bootstrap 组装层；不把 Agent 逻辑堆进 Controller。
 * <p>检索后经 {@link RagPromptAnswerSynthesizer} 调 LLM 组织答案，与 /ask 共用 Prompt/Generator。
 */
@Configuration
public class AgentConfig {

    @Bean
    TraceRecorder traceRecorder() {
        return new TraceRecorder();
    }

    @Bean
    TokenMeter tokenMeter(
            @Value("${ragpilot.token.prompt-price-per-1k:0}") double promptPrice,
            @Value("${ragpilot.token.completion-price-per-1k:0}") double completionPrice
    ) {
        return new TokenMeter(promptPrice, completionPrice);
    }

    @Bean
    KnowledgeSearchTool knowledgeSearchTool(ModeAwareRetriever retriever, RagPilotProperties props) {
        return new KnowledgeSearchTool(retriever, props.retrieval().topK());
    }

    @Bean
    HttpGetTool httpGetTool(
            @Value("${ragpilot.agent.http-allowlist:docs.spring.io,127.0.0.1}") String allowlist,
            @Value("${ragpilot.agent.http-timeout-ms:5000}") long timeoutMs
    ) {
        Set<String> hosts = Arrays.stream(allowlist.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return new HttpGetTool(hosts, Duration.ofMillis(timeoutMs));
    }

    @Bean
    @ConditionalOnProperty(prefix = "ragpilot.mcp", name = "enabled", havingValue = "false", matchIfMissing = true)
    ToolRegistry toolRegistry(KnowledgeSearchTool knowledgeSearchTool, HttpGetTool httpGetTool) {
        return new ToolRegistry(List.of(knowledgeSearchTool, httpGetTool));
    }

    /**
     * Agent finish 前的答案合成：再检索 + PromptBuilder + Generator（会真实调用 LM Studio）。
     */
    @Bean
    AnswerSynthesizer answerSynthesizer(
            ModeAwareRetriever retriever,
            PromptBuilder promptBuilder,
            Generator generator,
            RagPilotProperties props
    ) {
        return new RagPromptAnswerSynthesizer(
                retriever, promptBuilder, generator, props.retrieval().topK());
    }

    @Bean
    KnowledgeFirstDecisionMaker knowledgeFirstDecisionMaker(
            ToolRegistry toolRegistry,
            AnswerSynthesizer answerSynthesizer
    ) {
        return new KnowledgeFirstDecisionMaker(toolRegistry, answerSynthesizer);
    }

    @Bean
    ReActAgent reActAgent(
            KnowledgeFirstDecisionMaker knowledgeFirstDecisionMaker,
            ToolRegistry toolRegistry,
            @Value("${ragpilot.agent.max-steps:5}") int maxSteps,
            @Value("${ragpilot.agent.step-timeout-ms:15000}") long stepTimeoutMs
    ) {
        return new ReActAgent(
                knowledgeFirstDecisionMaker,
                toolRegistry,
                maxSteps,
                Duration.ofMillis(stepTimeoutMs)
        );
    }
}
