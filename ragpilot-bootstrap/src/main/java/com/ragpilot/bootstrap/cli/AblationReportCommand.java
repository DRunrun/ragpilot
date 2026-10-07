package com.ragpilot.bootstrap.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragpilot.eval.report.MarkdownReporter;
import com.ragpilot.eval.runner.EvalRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 消融报告 CLI：{@code java -jar ... report --inputs=a.json,b.json --out=reports/ablation-....md}。
 *
 * <p>链路位置：F2.9；读取 F2.8 产出的 eval JSON，调用 {@link MarkdownReporter} 生成可贴 README 的表。
 */
@Component
@Order(0)
public class AblationReportCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AblationReportCommand.class);

    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext context;

    public AblationReportCommand(ObjectMapper objectMapper, ConfigurableApplicationContext context) {
        this.objectMapper = objectMapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> nonOption = args.getNonOptionArgs();
        if (nonOption.isEmpty() || !"report".equalsIgnoreCase(nonOption.get(0))) {
            return;
        }

        String inputs = firstOption(args, "inputs", null);
        if (inputs == null || inputs.isBlank()) {
            throw new IllegalArgumentException(
                    "report requires --inputs=reports/eval-VECTOR.json,reports/eval-HYBRID.json,...");
        }
        Path out = Path.of(firstOption(args, "out", MarkdownReporter.defaultFileName()));

        List<MarkdownReporter.SetupRow> rows = new ArrayList<>();
        for (String part : inputs.split(",")) {
            Path path = Path.of(part.strip());
            EvalRunner.EvalReport report = objectMapper.readValue(path.toFile(), EvalRunner.EvalReport.class);
            String mode = report.mode() == null ? path.getFileName().toString() : report.mode();
            // 分块消融臂：同 mode 不同分块参数的两个臂靠 label 区分，否则表里两行无法归因
            String setup = report.label() == null || report.label().isBlank()
                    ? mode : mode + " · " + report.label();
            String notes = switch (mode) {
                case "VECTOR" -> "baseline vector-only";
                case "HYBRID" -> "BM25 + vector + RRF";
                case "HYBRID_RERANK" -> "hybrid + rerank";
                default -> "";
            };
            rows.add(new MarkdownReporter.SetupRow(setup, report, notes));
        }

        // 稳定排序：VECTOR → HYBRID → HYBRID_RERANK → 其它
        rows.sort((a, b) -> Integer.compare(order(a.setup()), order(b.setup())));

        String md = new MarkdownReporter().render(rows);
        Files.createDirectories(out.getParent() == null ? Path.of(".") : out.getParent());
        Files.writeString(out, md);
        log.info("Ablation report written: {}", out.toAbsolutePath());

        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }

    private static int order(String setup) {
        return switch (setup) {
            case "VECTOR" -> 0;
            case "HYBRID" -> 1;
            case "HYBRID_RERANK" -> 2;
            default -> 9;
        };
    }

    private static String firstOption(ApplicationArguments args, String name, String defaultValue) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return values.get(0);
    }
}
