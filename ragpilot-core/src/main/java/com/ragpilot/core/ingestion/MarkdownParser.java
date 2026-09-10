package com.ragpilot.core.ingestion;

import com.ragpilot.core.domain.SourceDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown/纯文本解析器：M1 唯一的 {@link DocumentParser} 实现。
 *
 * <p>链路位置：ingestion 第一步。吃 {@code .md}/{@code .txt}，吐 {@link SourceDocument}。
 *
 * <p>剥离策略（为什么这么剥）：
 * <ul>
 *   <li>标题井号、粗斜体、行内代码、链接语法都只影响排版，对 embedding 是噪声，剥掉</li>
 *   <li>标题文字必须保留进正文——「Bean 生命周期」这类检索关键词往往就在标题里</li>
 *   <li>标题层级单独抄进 metadata（title/headings），citation 展示与后续结构化分块要用</li>
 * </ul>
 *
 * <p>已知局限：不解析代码块内语义、不处理表格对齐符，M1 语料（Spring 文档摘录）够用。
 */
public final class MarkdownParser implements DocumentParser {

    /** 支持的扩展名：M1 只有 Markdown 与纯文本。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "markdown", "txt");

    /** 标题行：#{1~6} + 空格 + 标题文字。 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /** 图片：![alt](url) → 整段丢弃（url 对检索无意义，alt 多为文件名噪声）。 */
    private static final Pattern IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");

    /** 链接：[text](url) → 只留 text。 */
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");

    /** 粗斜体标记：**、__、*、_、` 逐个剥，保守起见不区分成对与否。 */
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*|__|[*_`])");

    /** 水平分割线：--- / *** / ___ 独占一行。 */
    private static final Pattern HR = Pattern.compile("^\\s*([-*_])\\s*(\\1\\s*){2,}$");

    /** 代码围栏：``` 或 ~~~。 */
    private static final Pattern CODE_FENCE = Pattern.compile("^\\s*(```|~~~)");

    /** metadata 里标题列表的截断上限，防止超长文档把元数据撑爆。 */
    private static final int MAX_HEADINGS_IN_METADATA = 50;

    @Override
    public boolean supports(Path path) {
        return SUPPORTED_EXTENSIONS.contains(extensionOf(path));
    }

    @Override
    public SourceDocument parse(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("file not found or not a regular file: " + path);
        }
        if (!supports(path)) {
            throw new IllegalArgumentException("unsupported extension: " + path);
        }

        String raw = Files.readString(path, StandardCharsets.UTF_8);
        Parsed parsed = stripMarkdown(raw);

        String fileName = path.getFileName().toString();
        String docId = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        Map<String, String> metadata = new LinkedHashMap<>();
        // title 优先取第一个一级标题；没有标题时退化为文件名，保证 metadata 永不为空集
        metadata.put("title", parsed.title.isEmpty() ? docId : parsed.title);
        if (!parsed.headings.isEmpty()) {
            metadata.put("headings", String.join(" | ", parsed.headings));
        }

        String mime = "txt".equals(extensionOf(path)) ? "text/plain" : "text/markdown";
        return new SourceDocument(docId, path.toString(), mime, parsed.text, metadata);
    }

    /**
     * 逐行剥离 Markdown 标记，同时抄录标题层级。
     *
     * @param raw 原始文件内容
     * @return 纯文本 + 标题信息的中间结果
     */
    private Parsed stripMarkdown(String raw) {
        List<String> textLines = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        String title = "";
        boolean inCodeFence = false;

        for (String line : raw.split("\\R")) {
            // 代码围栏开合切换；围栏内的行原样保留（代码内容可能含 # * 等符号，不能误剥）
            if (CODE_FENCE.matcher(line).find()) {
                inCodeFence = !inCodeFence;
                continue;
            }
            if (inCodeFence) {
                textLines.add(line);
                continue;
            }
            if (line.isBlank() || HR.matcher(line).matches()) {
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                String headingText = cleanInline(heading.group(2));
                if (!headingText.isEmpty()) {
                    headings.add(headingText);
                    // 第一个标题作为文档 title（无论级别，样例文档多以一级标题开头）
                    if (title.isEmpty()) {
                        title = headingText;
                    }
                    // 标题文字同样进正文：它是高权重检索词，剥掉会伤召回
                    textLines.add(headingText);
                }
                continue;
            }
            textLines.add(cleanInline(line));
        }

        String text = String.join("\n", textLines).strip();
        return new Parsed(text, title, headings);
    }

    /**
     * 行内标记清洗：图片、链接、粗斜体、行内代码。
     */
    private String cleanInline(String line) {
        String result = IMAGE.matcher(line).replaceAll("");
        result = LINK.matcher(result).replaceAll("$1");
        result = EMPHASIS.matcher(result).replaceAll("");
        // 列表符号（-、*、1.）保留数字序号不剥：有序列表的序号本身承载语义（如生命周期阶段顺序）
        return result.replaceAll("^\\s*[-+]\\s+", "").strip();
    }

    /** 取小写扩展名（不含点），无扩展名返回空串。 */
    private static String extensionOf(Path path) {
        if (path == null) {
            return "";
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 解析中间结果：正文 + 文档标题 + 标题层级列表（超长截断，防止元数据膨胀）。 */
    private record Parsed(String text, String title, List<String> headings) {
        Parsed {
            List<String> safe = headings == null ? List.of() : headings;
            headings = safe.size() <= MAX_HEADINGS_IN_METADATA
                    ? List.copyOf(safe)
                    : List.copyOf(safe.subList(0, MAX_HEADINGS_IN_METADATA));
        }
    }
}
