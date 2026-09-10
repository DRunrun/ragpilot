package com.ragpilot.bootstrap.admin;

import com.ragpilot.ops.token.TokenMeter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 统计与单价 Overlay（ADM-7）。
 *
 * <p>聚合来自 {@code chat_message.token_count}；实时请求成本仍走 {@link TokenMeter}。
 */
@RestController
@RequestMapping("/api/admin/v1/token")
public class TokenAdminController {

    private final JdbcTemplate jdbcTemplate;
    private final RuntimeConfigService runtimeConfigService;
    private final TokenMeter tokenMeter;

    public TokenAdminController(
            JdbcTemplate jdbcTemplate,
            RuntimeConfigService runtimeConfigService,
            TokenMeter tokenMeter
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeConfigService = runtimeConfigService;
        this.tokenMeter = tokenMeter;
    }

    public record PriceRequest(Double promptPricePer1k, Double completionPricePer1k) {
    }

    @GetMapping("/summary")
    public Mono<AdminApiResponse<Map<String, Object>>> summary(
            @RequestParam(defaultValue = "14") int days
    ) {
        return Mono.fromCallable(() -> {
            int d = Math.min(Math.max(days, 1), 90);
            double pp = runtimeConfigService.promptPricePer1k();
            double cp = runtimeConfigService.completionPricePer1k();

            List<Map<String, Object>> byDay = jdbcTemplate.queryForList(
                    """
                            SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                                   COUNT(*)::bigint AS messages,
                                   COALESCE(SUM(token_count), 0)::bigint AS tokens
                            FROM chat_message
                            WHERE created_at >= NOW() - (? * INTERVAL '1 day')
                              AND token_count IS NOT NULL
                            GROUP BY 1
                            ORDER BY 1 DESC
                            """,
                    d);

            List<Map<String, Object>> bySession = jdbcTemplate.queryForList(
                    """
                            SELECT m.session_id,
                                   s.title,
                                   COUNT(*)::bigint AS messages,
                                   COALESCE(SUM(m.token_count), 0)::bigint AS tokens
                            FROM chat_message m
                            LEFT JOIN chat_session s ON s.id = m.session_id
                            WHERE m.token_count IS NOT NULL
                            GROUP BY m.session_id, s.title
                            ORDER BY tokens DESC
                            LIMIT 30
                            """);

            long totalTokens = byDay.stream()
                    .mapToLong(r -> ((Number) r.get("tokens")).longValue())
                    .sum();
            // 会话表未区分 prompt/completion，按 prompt 单价粗估
            double estimatedCost = totalTokens / 1000.0 * pp;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("promptPricePer1k", pp);
            data.put("completionPricePer1k", cp);
            data.put("days", d);
            data.put("totalTokens", totalTokens);
            data.put("estimatedCostUsd", estimatedCost);
            data.put("byDay", byDay);
            data.put("bySession", bySession);
            data.put("note", "聚合基于 chat_message.token_count；未记用量的旧消息不计入。");
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/prices")
    public Mono<AdminApiResponse<Map<String, Object>>> prices(@RequestBody PriceRequest body) {
        return Mono.fromCallable(() -> {
            if (body == null) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", "body 不能为空");
            }
            if (body.promptPricePer1k() != null) {
                runtimeConfigService.put(
                        RuntimeConfigKeys.TOKEN_PROMPT_PRICE,
                        String.valueOf(body.promptPricePer1k()));
            }
            if (body.completionPricePer1k() != null) {
                runtimeConfigService.put(
                        RuntimeConfigKeys.TOKEN_COMPLETION_PRICE,
                        String.valueOf(body.completionPricePer1k()));
            }
            tokenMeter.updatePrices(
                    runtimeConfigService.promptPricePer1k(),
                    runtimeConfigService.completionPricePer1k());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("promptPricePer1k", runtimeConfigService.promptPricePer1k());
            data.put("completionPricePer1k", runtimeConfigService.completionPricePer1k());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
