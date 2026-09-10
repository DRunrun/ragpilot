package com.ragpilot.core.ingestion;

import com.ragpilot.core.domain.SourceDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarkdownParser} 单元测试。
 *
 * <p>验收口径（execution-plan F1.7）：解析 samples/spring-bean-lifecycle.md
 * 得到非空 text 且 metadata 含 title。样例文件用例依赖仓库目录结构，
 * 其余用例用临时目录自造文件，保证纯离线可跑。
 */
class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    /**
     * samples 目录在仓库根，单测工作目录是 ragpilot-core 模块目录，所以向上跳一级。
     */
    private static Path sampleFile() {
        return Path.of("..", "samples", "spring-bean-lifecycle.md");
    }

    @Test
    @DisplayName("F1.7 验收：解析 samples 样例 → text 非空且 metadata 含 title")
    void parsesSampleDocument() throws IOException {
        assertTrue(Files.exists(sampleFile()), "样例文件缺失：" + sampleFile().toAbsolutePath());

        SourceDocument doc = parser.parse(sampleFile());

        assertFalse(doc.rawText().isBlank(), "剥离后的正文不应为空");
        assertTrue(doc.metadata().containsKey("title"), "metadata 必须含 title");
        assertFalse(doc.metadata().get("title").isBlank());
        assertEquals("spring-bean-lifecycle", doc.id());
        assertEquals("text/markdown", doc.mime());
        // 正文关键词应保留（含标题文字），否则召回会受损
        assertTrue(doc.rawText().contains("Instantiation"), "列表内容应保留在正文中");
        // Markdown 井号标记必须被剥掉
        assertFalse(doc.rawText().contains("#"), "正文不应残留标题井号");
    }

    @Test
    @DisplayName("标题层级写入 metadata；行内标记被剥离")
    void stripsInlineMarkupAndRecordsHeadings(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("demo.md");
        Files.writeString(file, """
                # 主标题
                正文带 [链接文字](https://example.com) 和 **加粗**。
                ## 二级标题
                - 列表项一
                """, StandardCharsets.UTF_8);

        SourceDocument doc = parser.parse(file);

        assertEquals("主标题", doc.metadata().get("title"));
        assertTrue(doc.metadata().get("headings").contains("二级标题"));
        assertTrue(doc.rawText().contains("链接文字"), "链接应保留文字部分");
        assertFalse(doc.rawText().contains("https://"), "链接 url 应被剥离");
        assertFalse(doc.rawText().contains("**"), "加粗标记应被剥离");
    }

    @Test
    @DisplayName("代码围栏内的内容原样保留，不误剥 # * 等符号")
    void keepsCodeFenceContent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("code.md");
        Files.writeString(file, """
                # 标题
                ```java
                // # 这不是标题，* 这不是斜体
                int x = 1;
                ```
                """, StandardCharsets.UTF_8);

        SourceDocument doc = parser.parse(file);

        assertTrue(doc.rawText().contains("// # 这不是标题"), "代码块内容必须原样保留");
        assertTrue(doc.rawText().contains("int x = 1;"));
    }

    @Test
    @DisplayName("不支持的扩展名与不存在的文件 → IllegalArgumentException")
    void rejectsUnsupportedInput() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(Path.of("a.pdf")));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(Path.of("not-exist.md")));
        assertFalse(parser.supports(Path.of("a.pdf")));
        assertTrue(parser.supports(Path.of("a.md")));
        assertTrue(parser.supports(Path.of("a.TXT")));
    }
}
