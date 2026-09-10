package com.ragpilot.ops.trace;

import java.util.UUID;

/**
 * 追踪 ID 生成器，可观测性的起点。
 *
 * <p>链路位置：每次 ask 请求入口生成一个 traceId，贯穿检索、生成、token 计量与日志，
 * 出问题时能凭一个 ID 把整条链路的日志串起来。
 *
 * <p>M1 用 UUID 即可满足「能串起来」的需求。若后续要按时间排序 Trace，
 * 可换成 ULID（前缀有序），届时只改这一个方法，调用方无感。
 */
public final class TraceIds {

    /** 工具类，禁止实例化。 */
    private TraceIds() {}

    /**
     * 生成一个新的追踪 ID。
     *
     * @return 32 位十六进制字符串（去掉 UUID 的连字符，方便在日志与 URL 中传递）
     */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
