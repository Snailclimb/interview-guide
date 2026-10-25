package interview.guide.modules.agent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Agent 真实样本评测报告写入器：JSON 供 diff，Markdown 供验收阅读。 */
public final class AgentQualityReportWriter {

    private AgentQualityReportWriter() {
    }

    public static void write(Path directory, String runId, Map<String, Object> report) throws IOException {
        Files.createDirectories(directory);
        new ObjectMapper().writeValue(directory.resolve(runId + ".json").toFile(), report);
        Files.writeString(directory.resolve(runId + ".md"), toMarkdown(report), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static String toMarkdown(Map<String, Object> report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Agent 质量评测报告：").append(report.get("runId")).append("\n\n");
        markdown.append("- evaluatorVersion: ").append(report.get("evaluatorVersion")).append("\n");
        markdown.append("- datasetVersion: ").append(report.get("datasetVersion")).append("\n");
        markdown.append("- timestamp: ").append(report.get("timestamp")).append("\n\n");
        markdown.append("## 汇总\n\n");
        Map<String, Object> summary = (Map<String, Object>) report.getOrDefault("summary", Map.of());
        summary.forEach((key, value) -> markdown.append("- ").append(key).append(": ").append(value).append("\n"));
        markdown.append("\n## 运行版本\n\n");
        Map<String, Object> environment = (Map<String, Object>) report.getOrDefault("environment", Map.of());
        environment.forEach((key, value) -> markdown.append("- ").append(key).append(": ").append(value).append("\n"));
        markdown.append("\n## 逐样本结果\n\n");
        markdown.append("| sampleId | split | provider | model | passed | score | violations |\n");
        markdown.append("|---|---|---|---|---|---:|---|\n");
        List<Map<String, Object>> samples = (List<Map<String, Object>>) report.getOrDefault("samples", List.of());
        for (Map<String, Object> sample : samples) {
            markdown.append("| ").append(sample.get("sampleId"))
                .append(" | ").append(sample.get("split"))
                .append(" | ").append(sample.get("provider"))
                .append(" | ").append(sample.get("model"))
                .append(" | ").append(sample.get("passed"))
                .append(" | ").append(sample.get("overallScore"))
                .append(" | ").append(sample.get("violations"))
                .append(" |\n");
        }
        return markdown.toString();
    }
}
