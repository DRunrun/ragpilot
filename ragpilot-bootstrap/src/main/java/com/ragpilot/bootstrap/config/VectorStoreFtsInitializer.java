package com.ragpilot.bootstrap.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 为 {@code vector_store} 补全文检索列（content_tsv）与 GIN 索引。
 *
 * <p>链路位置：启动后置步骤（F2.1）。Spring AI 只建向量列，不建 tsvector；
 * 关键词召回依赖本脚本。Order=90，先于中文 COMMENT 初始化（100）。
 *
 * <p>失败只 warn：表未创建时跳过，首次摄入建表后下次启动会再执行。
 */
@Component
@Order(90)
public class VectorStoreFtsInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreFtsInitializer.class);

    private static final String SCRIPT = "db/vector-store-fts.sql";

    /** 脚本内语句分隔标记（必须独占一行；勿把该字符串写进普通注释）。 */
    private static final String STATEMENT_SEPARATOR = "-- ###STMT###";

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreFtsInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = 'vector_store')",
                    Boolean.class);
            if (!Boolean.TRUE.equals(exists)) {
                log.info("Skip vector_store FTS: table not created yet");
                return;
            }

            String sql = new ClassPathResource(SCRIPT)
                    .getContentAsString(StandardCharsets.UTF_8);
            Arrays.stream(sql.split(STATEMENT_SEPARATOR))
                    .map(String::strip)
                    .map(this::stripLeadingComments)
                    .filter(s -> !s.isEmpty())
                    .forEach(jdbcTemplate::execute);

            log.info("Applied vector_store FTS column/index (content_tsv + GIN)");
        } catch (Exception e) {
            log.warn("Failed to apply vector_store FTS DDL: {}", e.getMessage());
        }
    }

    /** 去掉语句开头的连续 -- 注释行，保留真正的 DDL。 */
    private String stripLeadingComments(String statement) {
        StringBuilder kept = new StringBuilder();
        boolean started = false;
        for (String line : statement.split("\\R")) {
            String trimmed = line.strip();
            if (!started && (trimmed.isEmpty() || trimmed.startsWith("--"))) {
                continue;
            }
            started = true;
            kept.append(line).append('\n');
        }
        return kept.toString().strip();
    }
}
