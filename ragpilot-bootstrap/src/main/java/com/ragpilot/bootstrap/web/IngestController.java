package com.ragpilot.bootstrap.web;

import com.ragpilot.core.domain.SourceDocument;
import com.ragpilot.core.ingestion.DocumentParser;
import com.ragpilot.core.ingestion.IngestReport;
import com.ragpilot.core.ingestion.IngestionPipeline;
import com.ragpilot.core.ingestion.MarkdownParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 摄入接口：把本地语料文件灌进向量库。
 *
 * <p>链路位置：HTTP 入口 → 解析 → 分块 → 写入（IngestionPipeline 全链路）。
 *
 * <p>写入会阻塞调用 Embedding + JDBC，因此整条管线在 {@code boundedElastic} 上执行。
 *
 * <p>安全边界：M1 演示用途，只允许摄入应用工作目录下的相对路径，
 * 拒绝 .. 跳出与绝对路径，防止把任意系统文件读进库。
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final IngestionPipeline pipeline;
    private final DocumentParser parser;

    public IngestController(IngestionPipeline pipeline) {
        this.pipeline = pipeline;
        // M1 只有 Markdown/纯文本一种解析器，直接实例化；
        // 将来支持多格式时改为注入 List<DocumentParser> 按 supports 分发
        this.parser = new MarkdownParser();
    }

    /** 单文件摄入请求体。 */
    public record IngestRequest(String path) {}

    /**
     * 摄入单个文件。
     *
     * @param request body：{"path":"samples/xxx.md"}
     * @return 摄入回执 {"docId":"...","chunkCount":12}
     */
    @PostMapping
    public Mono<IngestReport> ingest(@RequestBody IngestRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        return Mono.fromCallable(() -> ingestOne(resolveSafePath(request.path())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 一键灌入 samples/ 目录下全部支持的文件（M1 演示语料入口）。
     *
     * @return 每个文件的摄入回执列表；单个文件失败记日志但不中断其余文件
     */
    @PostMapping("/samples")
    public Mono<List<IngestReport>> ingestSamples() {
        return Mono.fromCallable(this::ingestSamplesSync)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<IngestReport> ingestSamplesSync() {
        Path samplesDir = Path.of("samples");
        if (!Files.isDirectory(samplesDir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "samples/ directory not found");
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(samplesDir)) {
            files = stream.filter(parser::supports).sorted().toList();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to list samples/: " + e.getMessage());
        }

        List<IngestReport> reports = new ArrayList<>();
        for (Path file : files) {
            try {
                reports.add(ingestOne(file));
            } catch (Exception e) {
                log.warn("Ingest failed for {}: {}", file, e.getMessage());
            }
        }
        return List.copyOf(reports);
    }

    /**
     * 路径安全校验：只接受工作目录内的相对路径。
     *
     * <p>防两类越权：绝对路径（读系统文件）、{@code ..} 跳出（读仓库外文件）。
     */
    private Path resolveSafePath(String raw) {
        Path path = Path.of(raw);
        if (path.isAbsolute() || raw.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "path must be relative within the working directory");
        }
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file not found: " + raw);
        }
        return path;
    }

    /** 解析 + 走管线；扩展名不支持等情况转成 4xx 而不是 500。 */
    private IngestReport ingestOne(Path file) {
        try {
            if (!parser.supports(file)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "unsupported file type: " + file);
            }
            SourceDocument doc = parser.parse(file);
            IngestReport report = pipeline.ingest(doc);
            log.info("Ingest ok: {} → {}", file, report);
            return report;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to read file: " + e.getMessage());
        }
    }
}
