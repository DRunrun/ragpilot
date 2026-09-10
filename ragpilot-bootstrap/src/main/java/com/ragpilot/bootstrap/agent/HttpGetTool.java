package com.ragpilot.bootstrap.agent;

import com.ragpilot.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * HTTP GET 工具：Agent 的第二个工具，拉取白名单域名上的公开文本。
 *
 * <p>链路位置：bootstrap 基础设施（需要 JDK HttpClient）；core 只认 {@link Tool} 端口。
 * <p>安全：仅允许配置的 host；超时硬限制，防止 Agent 被慢站点拖死。
 */
public final class HttpGetTool implements Tool {

    public static final String NAME = "http_get";

    private static final Logger log = LoggerFactory.getLogger(HttpGetTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "url": { "type": "string", "description": "http(s) URL，host 必须在白名单内" }
              },
              "required": ["url"]
            }
            """;

    private static final Pattern URL_JSON = Pattern.compile(
            "\"url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /** 可被 Admin Overlay 热更新（ADM-9）。 */
    private volatile Set<String> allowedHosts;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final int maxBodyChars;

    /**
     * @param allowedHosts 允许的主机名（小写比较），如 {@code docs.spring.io}
     * @param timeout      单次请求超时
     */
    public HttpGetTool(Set<String> allowedHosts, Duration timeout) {
        this(allowedHosts, timeout, 8000);
    }

    public HttpGetTool(Set<String> allowedHosts, Duration timeout, int maxBodyChars) {
        Objects.requireNonNull(allowedHosts, "allowedHosts");
        if (allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("allowedHosts must not be empty");
        }
        this.allowedHosts = normalizeHosts(allowedHosts);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.maxBodyChars = Math.max(maxBodyChars, 256);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Admin 改 allowlist 后即时生效，无需重启。 */
    public void updateAllowedHosts(Set<String> hosts) {
        Objects.requireNonNull(hosts, "hosts");
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("allowedHosts must not be empty");
        }
        this.allowedHosts = normalizeHosts(hosts);
    }

    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        return hosts.stream()
                .filter(Objects::nonNull)
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "HTTP GET a URL on the allowlisted hosts and return response text. "
                + "Use for fetching public documentation pages. Forbidden hosts are rejected.";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    /**
     * @param input JSON {@code {"url":"https://..."}} 或纯 URL 字符串
     * @return 响应正文摘要；越权域名抛 {@link SecurityException}
     */
    @Override
    public String execute(String input) throws Exception {
        String url = parseUrl(input);
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("URL missing host: " + url);
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (!isAllowed(normalized)) {
            // 越权：明确拒绝，不发起请求
            throw new SecurityException("host not allowlisted: " + host);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("only http(s) allowed: " + uri.getScheme());
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .GET()
                .header("User-Agent", "RagPilot-HttpGetTool/0.1")
                .build();
        log.info("HttpGetTool GET {}", uri);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (body.length() > maxBodyChars) {
            body = body.substring(0, maxBodyChars) + "…(truncated)";
        }
        return "status=" + response.statusCode() + "\n" + body;
    }

    private boolean isAllowed(String host) {
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private static String parseUrl(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("http_get input must not be blank");
        }
        String stripped = input.strip();
        if (stripped.startsWith("{")) {
            Matcher m = URL_JSON.matcher(stripped);
            if (!m.find()) {
                throw new IllegalArgumentException("http_get JSON missing url");
            }
            return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return stripped;
    }
}
