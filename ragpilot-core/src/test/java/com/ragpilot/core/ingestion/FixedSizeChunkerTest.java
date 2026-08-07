package com.ragpilot.core.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedSizeChunkerTest {

    @Test
    void chunksWithOverlap() {
        FixedSizeChunker chunker = new FixedSizeChunker(5, 2);
        List<String> parts = chunker.chunk("abcdefghij");
        assertEquals(List.of("abcde", "defgh", "ghij"), parts);
    }

    @Test
    void emptyInput() {
        assertTrue(new FixedSizeChunker(8, 2).chunk("   ").isEmpty());
    }
}
