package com.ragpilot.bootstrap.generation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LmStudioGenerator.SseLineBuffer} 单测：SSE 流按字节切行、跨帧拼接。
 *
 * <p>踩坑背景（本次修复动机）：DataBuffer 的边界可能落在多字节 UTF-8 字符中间，
 * 旧实现对每帧直接 toString(UTF_8) 会产出 U+FFFD 乱码——中文输出必踩。
 * 这里用「把一行汉字字节流逐字节喂进 buffer」模拟最恶劣的拆帧情况。
 */
class LmStudioGeneratorSseBufferTest {

    @Test
    void 多字节字符被拆到不同帧也能还原整行() {
        String line = "data: {\"content\":\"Bean 生命周期后处理\"}";
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);

        // 每 3 字节一帧：一定会把某些汉字的多字节序列劈开
        LmStudioGenerator.SseLineBuffer buffer = new LmStudioGenerator.SseLineBuffer();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < bytes.length; i += 3) {
            int end = Math.min(i + 3, bytes.length);
            byte[] frame = new byte[end - i];
            System.arraycopy(bytes, i, frame, 0, end - i);
            lines.addAll(buffer.consume(frame));
        }
        lines.addAll(buffer.flush());

        assertEquals(1, lines.size());
        assertEquals(line, lines.get(0));
        // 解码后不允许出现替换字符 U+FFFD，等于断言「无乱码」
        assertTrue(lines.get(0).indexOf('\uFFFD') < 0, "不应出现 U+FFFD 乱码");
    }

    @Test
    void 一帧多行与半行残段() {
        LmStudioGenerator.SseLineBuffer buffer = new LmStudioGenerator.SseLineBuffer();
        // 帧 1：两个完整行（CRLF）+ 半个行
        List<String> first = buffer.consume(
                "a\r\nb\ndata: partial".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("a", "b"), first);

        // 帧 2：残行补完
        List<String> second = buffer.consume("line\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("data: partialline"), second);

        // flush 无残行时为空
        assertTrue(buffer.flush().isEmpty());
    }

    @Test
    void 无换行收尾的残行由冲刷兜底() {
        LmStudioGenerator.SseLineBuffer buffer = new LmStudioGenerator.SseLineBuffer();
        assertTrue(buffer.consume("tail: 没有换行".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertEquals(List.of("tail: 没有换行"), buffer.flush());
        // 冲刷后缓冲已清空
        assertTrue(buffer.flush().isEmpty());
    }
}
