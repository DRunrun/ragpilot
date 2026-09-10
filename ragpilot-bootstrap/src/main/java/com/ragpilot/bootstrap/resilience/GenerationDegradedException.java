package com.ragpilot.bootstrap.resilience;

/**
 * 生成降级异常：LLM 超时或调用失败时抛出，由 Ask 编排转成 refused SSE，避免 500 白屏。
 *
 * <p>链路位置：F4.2 稳定性兜底；属于业务可恢复失败，不是容器级错误。
 */
public final class GenerationDegradedException extends RuntimeException {

    /** 超时。 */
    public static final String REASON_TIMEOUT = "GENERATION_TIMEOUT";

    /** 其它调用失败。 */
    public static final String REASON_ERROR = "GENERATION_ERROR";

    private final String reasonCode;

    public GenerationDegradedException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode == null ? REASON_ERROR : reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
