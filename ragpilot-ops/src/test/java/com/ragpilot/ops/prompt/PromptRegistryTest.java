package com.ragpilot.ops.prompt;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4.1 验收：版本切换与回滚。
 */
class PromptRegistryTest {

    @Test
    void 激活与回滚() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("rag-v1", "v1 {context} {question}");
        map.put("rag-v2", "v2 {context} {question}");
        PromptRegistry registry = new PromptRegistry(map, "rag-v1");
        assertEquals("rag-v1", registry.activeVersion());

        registry.activate("rag-v2");
        assertEquals("rag-v2", registry.activeVersion());
        assertTrue(registry.activeTemplate().startsWith("v2"));
        assertEquals(1, registry.historySize());

        assertEquals("rag-v1", registry.rollback());
        assertEquals("rag-v1", registry.activeVersion());
    }

    @Test
    void 从classpath加载默认模板() {
        PromptRegistry registry = PromptRegistry.loadDefault("rag-v1");
        assertTrue(registry.versions().contains("rag-v1"));
        assertTrue(registry.versions().contains("rag-v2"));
        assertTrue(registry.activeTemplate().contains("{context}"));
    }

    @Test
    void 未知版本与空历史回滚失败() {
        Map<String, String> map = Map.of("rag-v1", "t {context} {question}");
        PromptRegistry registry = new PromptRegistry(map, "rag-v1");
        assertThrows(IllegalArgumentException.class, () -> registry.activate("nope"));
        assertThrows(IllegalStateException.class, registry::rollback);
    }
}
