package com.ragpilot.eval.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 黄金集 JSONL 加载器：逐行解析，不依赖 Jackson（eval 保持轻依赖）。
 *
 * <p>链路位置：评测数据入口。支持 {@code v0.1.jsonl} / {@code v1.0.jsonl} 字段。
 */
public final class GoldenSetLoader {

    private static final Pattern FIELD_STRING = Pattern.compile(
            "\"(id|question|reference_answer|difficulty)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern FIELD_ARRAY = Pattern.compile(
            "\"(expected_doc_ids|expected_chunk_ids|tags)\"\\s*:\\s*\\[(.*?)]");

    /**
     * 从 classpath 加载，例如 {@code golden/v1.0.jsonl}。
     */
    public List<GoldenQuestion> loadClasspath(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        InputStream in = GoldenSetLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("golden set not found on classpath: " + resourcePath);
        }
        try (in) {
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 从输入流加载 JSONL。
     */
    public List<GoldenQuestion> load(InputStream in) {
        Objects.requireNonNull(in, "in");
        List<GoldenQuestion> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(parseLine(line));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                            "invalid golden JSONL at line " + lineNo + ": " + e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }

    static GoldenQuestion parseLine(String line) {
        String id = requiredString(line, "id");
        String question = requiredString(line, "question");
        String ref = optionalString(line, "reference_answer");
        String difficulty = optionalString(line, "difficulty");
        List<String> docs = stringArray(line, "expected_doc_ids");
        List<String> chunks = stringArray(line, "expected_chunk_ids");
        List<String> tags = stringArray(line, "tags");
        return new GoldenQuestion(id, unescape(question), unescape(ref), docs, chunks, tags, difficulty);
    }

    private static String requiredString(String line, String key) {
        String v = optionalString(line, key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return v;
    }

    private static String optionalString(String line, String key) {
        Matcher m = FIELD_STRING.matcher(line);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                return m.group(2);
            }
        }
        return "";
    }

    private static List<String> stringArray(String line, String key) {
        Matcher m = FIELD_ARRAY.matcher(line);
        while (m.find()) {
            if (!key.equals(m.group(1))) {
                continue;
            }
            String body = m.group(2).strip();
            if (body.isEmpty()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            Matcher sm = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(body);
            while (sm.find()) {
                values.add(unescape(sm.group(1)));
            }
            return values;
        }
        return List.of();
    }

    private static String unescape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }
}
