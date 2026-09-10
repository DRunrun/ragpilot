package com.ragpilot.ops.token;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TokenMeter 单测：累计与成本估算。
 */
class TokenMeterTest {

    @Test
    void 累计与成本估算() {
        // prompt $0.01/1K，completion $0.03/1K
        TokenMeter meter = new TokenMeter(0.01, 0.03);
        meter.add("r1", 1000, 500);
        meter.add("r1", 500, 500);
        assertEquals(1500, meter.usage("r1").prompt());
        assertEquals(1000, meter.usage("r1").completion());
        // 1.5*0.01 + 1.0*0.03 = 0.045
        assertEquals(0.045, meter.estimateCostUsd("r1"), 1e-9);
        assertEquals(TokenMeter.Usage.ZERO, meter.usage("missing"));
    }
}
