package com.ragpilot.core.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-size character chunker (M1 default strategy). Token-accurate splitting comes later.
 */
public final class FixedSizeChunker {

    private final int size;
    private final int overlap;

    public FixedSizeChunker(int size, int overlap) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("overlap must be in [0, size)");
        }
        this.size = size;
        this.overlap = overlap;
    }

    public List<String> chunk(String text) {
        Objects.requireNonNull(text, "text");
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + size, normalized.length());
            parts.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
            start = end - overlap;
        }
        return List.copyOf(parts);
    }
}
