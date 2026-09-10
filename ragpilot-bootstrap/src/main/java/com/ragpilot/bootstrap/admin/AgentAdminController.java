package com.ragpilot.bootstrap.admin;

import com.ragpilot.bootstrap.agent.HttpGetTool;
import com.ragpilot.bootstrap.mcp.McpClient;
import com.ragpilot.core.agent.Tool;
import com.ragpilot.core.agent.ToolRegistry;
import com.ragpilot.core.agent.tools.KnowledgeSearchTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent / 工具 / MCP 管理 API（ADM-9）：配置 Overlay + 工具试跑。
 */
@RestController
@RequestMapping("/api/admin/v1/agent")
public class AgentAdminController {

    private final RuntimeConfigService runtimeConfigService;
    private final ToolRegistry toolRegistry;
    private final HttpGetTool httpGetTool;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final ObjectProvider<McpClient> mcpClient;

    public AgentAdminController(
            RuntimeConfigService runtimeConfigService,
            ToolRegistry toolRegistry,
            HttpGetTool httpGetTool,
            KnowledgeSearchTool knowledgeSearchTool,
            ObjectProvider<McpClient> mcpClient
    ) {
        this.runtimeConfigService = runtimeConfigService;
        this.toolRegistry = toolRegistry;
        this.httpGetTool = httpGetTool;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.mcpClient = mcpClient;
    }

    public record AgentSettingsRequest(
            Integer maxSteps,
            Long stepTimeoutMs,
            String httpAllowlist,
            Long httpTimeoutMs,
            Boolean mcpEnabled,
            String mcpEndpoint,
            Long mcpTimeoutMs
    ) {
    }

    public record ToolTryRequest(String tool, String input) {
    }

    @GetMapping("/settings")
    public Mono<AdminApiResponse<Map<String, Object>>> settings() {
        return Mono.fromCallable(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("maxSteps", runtimeConfigService.agentMaxSteps());
            data.put("stepTimeoutMs", runtimeConfigService.agentStepTimeoutMs());
            data.put("httpAllowlist", runtimeConfigService.agentHttpAllowlist());
            data.put("httpTimeoutMs", runtimeConfigService.agentHttpTimeoutMs());
            data.put("mcpEnabled", runtimeConfigService.mcpEnabled());
            data.put("mcpEndpoint", runtimeConfigService.mcpEndpoint());
            data.put("mcpTimeoutMs", runtimeConfigService.mcpTimeoutMs());
            data.put("mcpBeanPresent", mcpClient.getIfAvailable() != null);
            data.put("runtimeAllowlist", httpGetTool.allowedHosts());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/settings")
    public Mono<AdminApiResponse<Map<String, Object>>> save(@RequestBody AgentSettingsRequest body) {
        return Mono.fromCallable(() -> {
            List<String> warnings = new ArrayList<>();
            if (body == null) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", "body 不能为空");
            }
            if (body.maxSteps() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.AGENT_MAX_STEPS, String.valueOf(body.maxSteps())));
            }
            if (body.stepTimeoutMs() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.AGENT_STEP_TIMEOUT_MS, String.valueOf(body.stepTimeoutMs())));
            }
            if (body.httpAllowlist() != null && !body.httpAllowlist().isBlank()) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.AGENT_HTTP_ALLOWLIST, body.httpAllowlist()));
                httpGetTool.updateAllowedHosts(runtimeConfigService.agentHttpAllowlistSet());
            }
            if (body.httpTimeoutMs() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.AGENT_HTTP_TIMEOUT_MS, String.valueOf(body.httpTimeoutMs())));
            }
            if (body.mcpEnabled() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.MCP_ENABLED, String.valueOf(body.mcpEnabled())));
            }
            if (body.mcpEndpoint() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.MCP_ENDPOINT, body.mcpEndpoint()));
            }
            if (body.mcpTimeoutMs() != null) {
                warnings.addAll(runtimeConfigService.put(
                        RuntimeConfigKeys.MCP_TIMEOUT_MS, String.valueOf(body.mcpTimeoutMs())));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("warnings", warnings.stream().distinct().toList());
            data.put("settings", Map.of(
                    "maxSteps", runtimeConfigService.agentMaxSteps(),
                    "httpAllowlist", runtimeConfigService.agentHttpAllowlist(),
                    "mcpEnabled", runtimeConfigService.mcpEnabled()
            ));
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/tools")
    public Mono<AdminApiResponse<Map<String, Object>>> tools() {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Tool tool : toolRegistry.tools()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", tool.name());
                one.put("description", tool.description());
                one.put("inputSchema", tool.inputSchema());
                items.add(one);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("total", items.size());
            return AdminApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/tools/try")
    public Mono<AdminApiResponse<Map<String, Object>>> tryTool(@RequestBody ToolTryRequest body) {
        return Mono.fromCallable(() -> {
            if (body == null || body.tool() == null || body.tool().isBlank()) {
                return AdminApiResponse.<Map<String, Object>>fail("BAD_REQUEST", "tool 不能为空");
            }
            String name = body.tool().strip();
            String input = body.input() == null ? "" : body.input();
            try {
                String output;
                if (HttpGetTool.NAME.equals(name)) {
                    output = httpGetTool.execute(input);
                } else if (KnowledgeSearchTool.NAME.equals(name)) {
                    output = knowledgeSearchTool.execute(input);
                } else {
                    Tool tool = toolRegistry.find(name)
                            .orElseThrow(() -> new IllegalArgumentException("未知工具: " + name));
                    output = tool.execute(input);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("tool", name);
                data.put("output", output == null ? ""
                        : output.substring(0, Math.min(output.length(), 4000)));
                return AdminApiResponse.success(data);
            } catch (Exception e) {
                return AdminApiResponse.<Map<String, Object>>fail(
                        "TOOL_FAILED", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/mcp/ping")
    public Mono<AdminApiResponse<Map<String, Object>>> mcpPing() {
        return Mono.fromCallable(() -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("configuredEnabled", runtimeConfigService.mcpEnabled());
            data.put("endpoint", runtimeConfigService.mcpEndpoint());
            McpClient client = mcpClient.getIfAvailable();
            data.put("beanPresent", client != null);
            if (client == null) {
                data.put("ok", false);
                data.put("message", "MCP Bean 未装配（需 ragpilot.mcp.enabled=true 并重启）");
                return AdminApiResponse.success(data);
            }
            try {
                // 轻量探测：列出工具；失败则返回错误
                var tools = client.listTools();
                data.put("ok", true);
                data.put("toolCount", tools == null ? 0 : tools.size());
                data.put("tools", tools);
                return AdminApiResponse.success(data);
            } catch (Exception e) {
                data.put("ok", false);
                data.put("message", e.getMessage());
                return AdminApiResponse.success(data);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
