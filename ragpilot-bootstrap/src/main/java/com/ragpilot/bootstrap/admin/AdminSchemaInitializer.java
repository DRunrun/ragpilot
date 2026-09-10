package com.ragpilot.bootstrap.admin;

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
 * 启动时初始化 Admin 元数据表与默认知识库（ADM-0.5）。
 *
 * <p>链路位置：bootstrap 启动后置；失败只 warn，避免拖死纯网关排查。
 */
@Component
@Order(80)
public class AdminSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSchemaInitializer.class);
    private static final String SCRIPT = "db/admin-schema.sql";
    private static final String STATEMENT_SEPARATOR = "-- ###STMT###";

    private final JdbcTemplate jdbcTemplate;

    public AdminSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String sql = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
            Arrays.stream(sql.split(STATEMENT_SEPARATOR))
                    .map(String::strip)
                    .map(this::stripLeadingComments)
                    .filter(s -> !s.isEmpty())
                    .forEach(jdbcTemplate::execute);
            log.info("Applied Admin schema (runtime_config / knowledge_base / knowledge_document)");
        } catch (Exception e) {
            log.warn("Skip Admin schema init: {}", e.toString());
        }
    }

    private String stripLeadingComments(String stmt) {
        StringBuilder sb = new StringBuilder();
        for (String line : stmt.split("\n")) {
            String t = line.strip();
            if (t.startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }
}
