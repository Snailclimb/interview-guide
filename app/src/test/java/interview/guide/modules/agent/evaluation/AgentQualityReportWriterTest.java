package interview.guide.modules.agent.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AgentQualityReportWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesJsonAndReadableMarkdownReport() throws Exception {
        Map<String, Object> report = Map.of(
            "runId", "smoke",
            "evaluatorVersion", "agent-quality-v1",
            "datasetVersion", "agent-eval/dataset-v1.jsonl",
            "timestamp", "2026-10-25T00:00:00Z",
            "summary", Map.of("samples", 1, "passed", 1, "passRate", 1.0),
            "environment", Map.of("provider", "pixel", "model", "gpt-5.6-luna"),
            "samples", List.of(Map.of(
                "sampleId", "review-completeness-001",
                "split", "dev",
                "provider", "pixel",
                "model", "gpt-5.6-luna",
                "passed", true,
                "overallScore", 1.0,
                "violations", List.of()
            ))
        );

        AgentQualityReportWriter.write(temporaryDirectory, "smoke", report);

        Path json = temporaryDirectory.resolve("smoke.json");
        Path markdown = temporaryDirectory.resolve("smoke.md");
        assertThat(json).isRegularFile();
        assertThat(markdown).isRegularFile();
        assertThat(Files.readString(json)).contains("review-completeness-001");
        assertThat(Files.readString(markdown))
            .contains("# Agent 质量评测报告：smoke")
            .contains("| sampleId | split | provider | model | passed | score | violations |");
    }
}
