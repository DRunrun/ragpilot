package com.ragpilot.bootstrap.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端健康检查（ADM-0.7）。
 *
 * <p>链路位置：Admin 探活；不依赖向量库，便于前端壳子联调。
 */
@RestController
@RequestMapping("/api/admin/v1")
public class AdminHealthController {

    @GetMapping("/health")
    public AdminApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "ragpilot-admin");
        return AdminApiResponse.success(body);
    }
}
