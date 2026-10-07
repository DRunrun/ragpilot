package com.ragpilot.bootstrap.config;

import com.ragpilot.bootstrap.admin.RuntimeConfigService;
import com.ragpilot.bootstrap.ingest.SpringAiVectorStoreWriter;
import com.ragpilot.bootstrap.ingest.StrategySelectingChunker;
import com.ragpilot.core.ingestion.Chunker;
import com.ragpilot.core.ingestion.ChunkWriter;
import com.ragpilot.core.ingestion.IngestionPipeline;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ingestion 链路的组装配置：把 core 的端口接到 bootstrap 的基础设施实现上。
 *
 * <p>链路位置：bootstrap 组装层。core 的分块器/管线不依赖 Spring，
 * 由本类统一构造注入；分块策略读 {@link RuntimeConfigService}（yml 默认 + DB 覆盖）。
 */
@Configuration
public class IngestionConfig {

    /**
     * 分块器：按覆盖层 strategy 动态选择 FIXED / HEADING / RECURSIVE（ADM-2.3）。
     */
    @Bean
    StrategySelectingChunker strategySelectingChunker(RuntimeConfigService runtimeConfigService) {
        return new StrategySelectingChunker(runtimeConfigService);
    }

    @Bean
    Chunker chunker(StrategySelectingChunker strategySelectingChunker) {
        return strategySelectingChunker;
    }

    /**
     * 写入端口实现：Spring AI PGVector 适配器。
     * VectorStore 由 spring-ai-starter-vector-store-pgvector 自动装配。
     *
     * <p>返回类型用具体类而不是 ChunkWriter 端口：分块消融 CLI 需要
     * {@code deleteByDocId}（重灌前清旧块），端口契约不为此扩面。
     */
    @Bean
    SpringAiVectorStoreWriter chunkWriter(VectorStore vectorStore) {
        return new SpringAiVectorStoreWriter(vectorStore);
    }

    /**
     * 摄入管线：纯 core 编排逻辑，靠上面两个 Bean 完成端口注入。
     */
    @Bean
    IngestionPipeline ingestionPipeline(Chunker chunker, ChunkWriter chunkWriter) {
        return new IngestionPipeline(chunker, chunkWriter);
    }
}
