package com.ragpilot.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable ask outcome used across modules (M1 contract preview).
 */
public record AskResult(
        String answer,
        boolean refused,
        String refuseReason,
        List<Citation> citations,
        String traceId,
        TokenUsage tokenUsage
) {
    public AskResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
        answer = answer == null ? "" : answer;
    }

    public static AskResult refused(String reason, String message, String traceId) {
        return new AskResult(message, true, reason, List.of(), traceId, TokenUsage.ZERO);
    }

    public static AskResult answered(String answer, List<Citation> citations, String traceId, TokenUsage usage) {
        Objects.requireNonNull(citations, "citations");
        return new AskResult(answer, false, null, citations, traceId, usage == null ? TokenUsage.ZERO : usage);
    }

    public record Citation(int index, String docId, String chunkId, String snippet) {}

    public record TokenUsage(int prompt, int completion) {
        public static final TokenUsage ZERO = new TokenUsage(0, 0);
    }

    /** Placeholder metadata bag for future filters — prefer typed fields over growing this map. */
    public record AskRequest(String question, Integer topK, Map<String, String> filters) {
        public AskRequest {
            filters = filters == null ? Map.of() : Map.copyOf(filters);
        }
    }
}
