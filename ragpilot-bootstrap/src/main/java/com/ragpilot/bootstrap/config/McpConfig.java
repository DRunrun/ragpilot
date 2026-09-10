package com.ragpilot.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.bootstrap.mcp.McpClient;
import com.ragpilot.bootstrap.mcp.McpProxyTool;
import com.ragpilot.core.agent.Tool;
import com.ragpilot.core.agent.ToolRegistry;
import com.ragpilot.core.agent.tools.KnowledgeSearchTool;
import com.ragpilot.bootstrap.agent.HttpGetTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * F3.7 MCP 可选装配：仅当 {@code ragpilot.mcp.enabled=true} 时注册客户端与代理工具。
 */
@Configuration
public class McpConfig {

    @Bean
    @ConditionalOnProperty(prefix = "ragpilot.mcp", name = "enabled", havingValue = "true")
    McpClient mcpClient(
            @Value("${ragpilot.mcp.endpoint:http://127.0.0.1:3100/mcp}") String endpoint,
            @Value("${ragpilot.mcp.timeout-ms:5000}") long timeoutMs,
            ObjectMapper objectMapper
    ) {
        return new McpClient(endpoint, Duration.ofMillis(timeoutMs), objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ragpilot.mcp", name = "enabled", havingValue = "true")
    McpProxyTool mcpProxyTool(McpClient mcpClient, ObjectMapper objectMapper) {
        return new McpProxyTool(mcpClient, objectMapper);
    }

    /**
     * 覆盖默认 ToolRegistry：在知识库 + HTTP 之外，可选挂上 MCP 代理。
     * 仅 mcp.enabled=true 时生效；否则仍用 AgentConfig 里的双工具注册表。
     */
    @Bean
    @ConditionalOnProperty(prefix = "ragpilot.mcp", name = "enabled", havingValue = "true")
    ToolRegistry toolRegistryWithMcp(
            KnowledgeSearchTool knowledgeSearchTool,
            HttpGetTool httpGetTool,
            McpProxyTool mcpProxyTool
    ) {
        List<Tool> tools = new ArrayList<>();
        tools.add(knowledgeSearchTool);
        tools.add(httpGetTool);
        tools.add(mcpProxyTool);
        return new ToolRegistry(tools);
    }
}
