package com.ragpilot.bootstrap.admin;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * 运行时配置覆盖 API（ADM-0.6）。
 */
@RestController
@RequestMapping("/api/admin/v1/config")
public class RuntimeConfigController {

    private final RuntimeConfigService runtimeConfigService;

    public RuntimeConfigController(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping
    public AdminApiResponse<Map<String, Object>> effective() {
        return AdminApiResponse.success(runtimeConfigService.effectiveView());
    }

    /** {key:.+} 保留点分键（如 ragpilot.chunk.strategy），避免被截断。 */
    @PutMapping("/{key:.+}")
    public AdminApiResponse<Map<String, Object>> put(
            @PathVariable String key,
            @RequestBody Map<String, Object> body
    ) {
        Object value = body == null ? null : body.get("value");
        if (value == null) {
            throw new ResponseStatusException(BAD_REQUEST, "value is required");
        }
        try {
            var warnings = runtimeConfigService.put(key, String.valueOf(value));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("key", key);
            data.put("value", String.valueOf(value));
            data.put("warnings", warnings);
            return AdminApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return AdminApiResponse.fail("CONFIG_KEY_NOT_ALLOWED", e.getMessage());
        }
    }

    @DeleteMapping("/{key:.+}")
    public AdminApiResponse<Map<String, Object>> delete(@PathVariable String key) {
        try {
            runtimeConfigService.delete(key);
        } catch (IllegalArgumentException e) {
            return AdminApiResponse.fail("CONFIG_KEY_NOT_ALLOWED", e.getMessage());
        }
        return AdminApiResponse.success(Map.of("key", key, "deleted", true));
    }
}
