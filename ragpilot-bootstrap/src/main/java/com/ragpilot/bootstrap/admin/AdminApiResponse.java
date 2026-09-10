package com.ragpilot.bootstrap.admin;

import java.util.Map;

/**
 * Admin API 统一响应信封（见 docs/admin-console-spec.md §6.1）。
 *
 * <p>链路位置：所有 {@code /api/admin/v1/**} 的对外 JSON 契约。
 */
public record AdminApiResponse<T>(boolean ok, T data, ErrorBody error) {

    public record ErrorBody(String code, String message, Map<String, Object> details) {
    }

    public static <T> AdminApiResponse<T> success(T data) {
        return new AdminApiResponse<>(true, data, null);
    }

    public static <T> AdminApiResponse<T> fail(String code, String message) {
        return new AdminApiResponse<>(false, null, new ErrorBody(code, message, Map.of()));
    }

    public static <T> AdminApiResponse<T> fail(String code, String message, Map<String, Object> details) {
        return new AdminApiResponse<>(false, null, new ErrorBody(code, message, details == null ? Map.of() : details));
    }
}
