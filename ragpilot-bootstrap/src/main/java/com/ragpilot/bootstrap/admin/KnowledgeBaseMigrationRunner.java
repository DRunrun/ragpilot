package com.ragpilot.bootstrap.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把缺失 knowledgeBaseId 的旧向量归入 default（ADM-3.6）。
 */
@Component
@Order(90)
public class KnowledgeBaseMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseMigrationRunner.class);

    private final VectorChunkStore vectorChunkStore;

    public KnowledgeBaseMigrationRunner(VectorChunkStore vectorChunkStore) {
        this.vectorChunkStore = vectorChunkStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = vectorChunkStore.migrateMissingKbToDefault();
            if (n > 0) {
                log.info("Migrated {} vector rows → knowledgeBaseId=default", n);
            }
        } catch (Exception e) {
            log.warn("Skip vector KB migration: {}", e.toString());
        }
    }
}
