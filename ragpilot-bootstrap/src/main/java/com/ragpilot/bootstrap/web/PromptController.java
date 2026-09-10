package com.ragpilot.bootstrap.web;

import com.ragpilot.ops.prompt.PromptRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 版本管理 API：查看 / 切换 / 回滚（F4.1）。
 *
 * <p>链路位置：运维调试入口；切换后下一次 /ask 使用新模板，done 事件带 promptVersion。
 */
@RestController
@RequestMapping("/api/v1/prompt")
public class PromptController {

    private final PromptRegistry promptRegistry;

    public PromptController(PromptRegistry promptRegistry) {
        this.promptRegistry = promptRegistry;
    }

    @GetMapping
    public Map<String, Object> current() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", promptRegistry.activeVersion());
        body.put("versions", promptRegistry.versions());
        body.put("historySize", promptRegistry.historySize());
        return body;
    }

    /**
     * 激活指定版本。body: {@code {"version":"rag-v2"}}
     */
    @PostMapping("/activate")
    public Map<String, Object> activate(@RequestBody Map<String, String> body) {
        String version = body == null ? null : body.get("version");
        if (version == null || version.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version is required");
        }
        try {
            promptRegistry.activate(version.strip());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return current();
    }

    /** 回滚到上一版本。 */
    @PostMapping("/rollback")
    public Map<String, Object> rollback() {
        try {
            promptRegistry.rollback();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return current();
    }
}
