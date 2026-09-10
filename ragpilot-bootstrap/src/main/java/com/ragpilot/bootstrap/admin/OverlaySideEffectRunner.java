package com.ragpilot.bootstrap.admin;

import com.ragpilot.bootstrap.agent.HttpGetTool;
import com.ragpilot.ops.token.TokenMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把 Overlay 单价 / HTTP allowlist 同步到运行时 Bean（ADM-7 / ADM-9）。
 */
@Component
@Order(95)
public class OverlaySideEffectRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OverlaySideEffectRunner.class);

    private final RuntimeConfigService runtimeConfigService;
    private final TokenMeter tokenMeter;
    private final HttpGetTool httpGetTool;

    public OverlaySideEffectRunner(
            RuntimeConfigService runtimeConfigService,
            TokenMeter tokenMeter,
            HttpGetTool httpGetTool
    ) {
        this.runtimeConfigService = runtimeConfigService;
        this.tokenMeter = tokenMeter;
        this.httpGetTool = httpGetTool;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            tokenMeter.updatePrices(
                    runtimeConfigService.promptPricePer1k(),
                    runtimeConfigService.completionPricePer1k());
            httpGetTool.updateAllowedHosts(runtimeConfigService.agentHttpAllowlistSet());
            log.info("Synced overlay → TokenMeter prices & HttpGetTool allowlist");
        } catch (Exception e) {
            log.warn("Overlay side-effect sync skipped: {}", e.toString());
        }
    }
}
