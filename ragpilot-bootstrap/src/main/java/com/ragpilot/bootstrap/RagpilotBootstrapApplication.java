package com.ragpilot.bootstrap;

import com.ragpilot.bootstrap.config.RagPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * RagPilot 启动入口。
 *
 * <p>模块职责：bootstrap 只负责「组装」——读配置、装配 Bean、暴露 HTTP/CLI，
 * 以及托管最小调试 UI（{@code static/index.html}）。
 * 分块、检索、生成等领域逻辑一律放在 {@code ragpilot-core}，本模块不得堆业务代码。
 *
 * <p>依赖方向（单向，不可逆）：bootstrap → ops → eval → core。
 */
@SpringBootApplication
@EnableConfigurationProperties(RagPilotProperties.class)
public class RagpilotBootstrapApplication {

    private static final Logger log = LoggerFactory.getLogger(RagpilotBootstrapApplication.class);

    public static void main(String[] args) {
        // CLI「eval / report」批处理不需要 Web 端口
        if (args.length > 0) {
            String cmd = args[0];
            if ("eval".equalsIgnoreCase(cmd) || "report".equalsIgnoreCase(cmd)) {
                System.setProperty("spring.main.web-application-type", "none");
            }
        }
        SpringApplication.run(RagpilotBootstrapApplication.class, args);
    }

    /**
     * 启动即打印关键配置，改 yml 后第一眼就能确认是否生效，
     * 不用等到实际提问才发现参数没绑上（F1.2 验收点）。
     */
    @Bean
    ApplicationRunner logRagPilotConfig(RagPilotProperties props) {
        return args -> log.info(
                "RagPilot config: chunk.size={}, chunk.overlap={}, retrieval.topK={}, "
                        + "retrieval.minScore={}, generation.model={}, embedding.model={}, refusal.emptyHits={}",
                props.chunk().size(), props.chunk().overlap(),
                props.retrieval().topK(), props.retrieval().minScore(),
                props.generation().model(), props.embedding().model(),
                props.refusal().emptyHits()
        );
    }
}
