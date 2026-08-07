package com.ragpilot.ops.trace;

import java.util.UUID;

public final class TraceIds {
    private TraceIds() {}

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
