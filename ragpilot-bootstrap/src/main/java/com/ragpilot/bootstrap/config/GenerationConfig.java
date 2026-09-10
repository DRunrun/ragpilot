package com.ragpilot.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.bootstrap.generation.LmStudioGenerator;
import com.ragpilot.bootstrap.resilience.ResilientGenerator;
import com.ragpilot.core.generation.CitationAssembler;
import com.ragpilot.core.generation.Generator;
import com.ragpilot.core.generation.PromptBuilder;
import com.ragpilot.core.generation.RefusalPolicy;
import com.ragpilot.ops.prompt.PromptRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 生成链路组装：PromptRegistry、PromptBuilder、ResilientGenerator。
 *
 * <p>链路位置：bootstrap 组装层（F4.1 / F4.2）。
 */
@Configuration
public class GenerationConfig {

    @Bean
    PromptRegistry promptRegistry(
            @Value("${ragpilot.prompt.active:rag-v1}") String activeVersion
    ) {
        return PromptRegistry.loadDefault(activeVersion);
    }

    /**
     * Prompt 构建器绑定 Registry：切换/回滚版本后下一次 build 即生效。
     */
    @Bean
    PromptBuilder promptBuilder(PromptRegistry promptRegistry) {
        return new PromptBuilder(promptRegistry::activeTemplate, promptRegistry::activeVersion);
    }

    @Bean
    CitationAssembler citationAssembler() {
        return new CitationAssembler();
    }

    @Bean
    RefusalPolicy refusalPolicy(RagPilotProperties props) {
        return new RefusalPolicy(props.refusal().emptyHits());
    }

    @Bean
    LmStudioGenerator lmStudioGenerator(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            RagPilotProperties props
    ) {
        return new LmStudioGenerator(
                webClientBuilder,
                objectMapper,
                baseUrl,
                apiKey,
                props.generation().model(),
                props.generation().temperature()
        );
    }

    /**
     * F4.2：对外暴露带超时与错误包装的 Generator，避免裸 WebClient 异常冲到 500。
     */
    @Bean
    Generator generator(
            LmStudioGenerator lmStudioGenerator,
            @Value("${ragpilot.generation.timeout-seconds:60}") long timeoutSeconds
    ) {
        return new ResilientGenerator(lmStudioGenerator, Duration.ofSeconds(timeoutSeconds));
    }
}
