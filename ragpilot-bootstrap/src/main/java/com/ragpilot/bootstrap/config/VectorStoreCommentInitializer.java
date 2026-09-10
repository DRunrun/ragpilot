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
 * 为 PGVector 的 {@code vector_store} 表补中文 COMMENT。
 *
 * <p>链路位置：启动后置步骤。Spring AI 的 {@code initialize-schema} 只建表/索引，
 * 不写业务语义注释；IDEA/DataGrip 里表结构「Description」为空会很难读。
 * 本类在应用启动后执行 classpath 下的 SQL，可重复执行（COMMENT ON 幂等覆盖）。
 *
 * <p>失败只打 warn 不阻断启动：库未就绪、表尚未创建时，首次真正摄入建表后
 * 下次启动会再补注释。
 */
@Component
@Order(100)
public class VectorStoreCommentInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreCommentInitializer.class);

    /** 注释脚本在 classpath 中的位置。 */
    private static final String SCRIPT = "db/vector-store-comments.sql";

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreCommentInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 表还不存在时跳过，避免 initialize-schema 未跑时启动报错
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_name = 'vector_store')",
                    Boolean.class);
            if (!Boolean.TRUE.equals(exists)) {
                log.info("Skip vector_store comments: table not created yet");
                return;
            }

            String sql = new ClassPathResource(SCRIPT)
                    .getContentAsString(StandardCharsets.UTF_8);
            // 去掉纯注释行后按分号拆语句执行
            Arrays.stream(sql.split(";"))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .filter(s -> !s.lines().allMatch(line -> line.strip().startsWith("--")))
                    .forEach(jdbcTemplate::execute);

            log.info("Applied Chinese COMMENT on vector_store table/columns");
        } catch (Exception e) {
            log.warn("Failed to apply vector_store comments: {}", e.getMessage());
        }
    }
}
