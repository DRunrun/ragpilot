package com.ragpilot.bootstrap.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置白名单单测（ADM-0.6）。
 */
class RuntimeConfigKeysTest {

    @Test
    void 允许的键通过() {
        assertTrue(RuntimeConfigKeys.isAllowed(RuntimeConfigKeys.CHUNK_STRATEGY));
        assertTrue(RuntimeConfigKeys.isAllowed(RuntimeConfigKeys.DEFAULT_KB));
        assertTrue(RuntimeConfigKeys.isAllowed(RuntimeConfigKeys.LLM_BASE_URL));
        assertTrue(RuntimeConfigKeys.isAllowed(RuntimeConfigKeys.TOKEN_PROMPT_PRICE));
        assertTrue(RuntimeConfigKeys.isAllowed(RuntimeConfigKeys.MCP_ENABLED));
        assertTrue(RuntimeConfigKeys.isDangerous(RuntimeConfigKeys.EMBEDDING_DIM));
    }

    @Test
    void 未知键拒绝() {
        assertFalse(RuntimeConfigKeys.isAllowed("ragpilot.hack"));
        assertFalse(RuntimeConfigKeys.isAllowed(null));
    }
}
