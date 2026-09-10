package com.ragpilot.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.bootstrap.retrieval.BgeReranker;
import com.ragpilot.bootstrap.retrieval.ModeAwareRetriever;
import com.ragpilot.bootstrap.retrieval.PgFullTextRetriever;
import com.ragpilot.bootstrap.retrieval.VectorRetriever;
import com.ragpilot.core.retrieval.NoOpReranker;
import com.ragpilot.core.retrieval.Reranker;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 检索链路的组装配置：向量 / 关键词 / Rerank 组装为模式感知主 Retriever。
 *
 * <p>链路位置：bootstrap 组装层。
 * <ul>
 *   <li>主检索 = {@link ModeAwareRetriever}（{@code @Primary}），按 mode 切换</li>
 *   <li>默认 mode=VECTOR → 行为与 M1 单路一致</li>
 *   <li>向量路、关键词路仍以具体类型暴露，供调试 API 单路验收</li>
 * </ul>
 */
@Configuration
public class RetrievalConfig {

    /** 向量单路实现：调试 API 与 ModeAware 内部复用。 */
    @Bean
    VectorRetriever vectorRetriever(VectorStore vectorStore, RagPilotProperties props) {
        return new VectorRetriever(vectorStore, props.retrieval().minScore());
    }

    /** F2.1 关键词召回：独立类型暴露。 */
    @Bean
    PgFullTextRetriever pgFullTextRetriever(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new PgFullTextRetriever(jdbcTemplate, objectMapper);
    }

    /**
     * F2.3 可插拔重排：关 → 恒等 NoOp；开 → BGE HTTP。
     */
    @Bean
    Reranker reranker(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.api-key:local}") String apiKey,
            RagPilotProperties props
    ) {
        RagPilotProperties.Rerank cfg = props.retrieval().rerank();
        if (Boolean.TRUE.equals(cfg.enabled())) {
            return new BgeReranker(
                    webClientBuilder,
                    objectMapper,
                    cfg.baseUrl(),
                    apiKey,
                    cfg.model()
            );
        }
        return new NoOpReranker();
    }

    /**
     * F2.4 主检索：按 ragpilot.retrieval.mode 编排。
     * 同时实现 Retriever，AskController 按端口注入即可。
     */
    @Bean
    @Primary
    ModeAwareRetriever modeAwareRetriever(
            VectorRetriever vectorRetriever,
            PgFullTextRetriever keywordRetriever,
            Reranker reranker,
            RagPilotProperties props
    ) {
        return new ModeAwareRetriever(
                vectorRetriever,
                keywordRetriever,
                reranker,
                props.retrieval().mode()
        );
    }
}
