package interview.guide.modules.agent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 质量评测语料契约")
@Tag("agent-eval")
class AgentQualityCorpusTest {

    private static final String DATASET = "agent-eval/dataset-v1.jsonl";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("样本结构、ID、分组和质量维度完整")
    void validatesDatasetContract() throws IOException {
        List<AgentQualityEvaluator.Sample> samples = samples();

        assertThat(samples).hasSize(6);
        assertThat(samples.stream().filter(item -> "dev".equals(item.split()))).hasSize(4);
        assertThat(samples.stream().filter(item -> "holdout".equals(item.split()))).hasSize(2);
        Set<String> ids = new HashSet<>();
        for (AgentQualityEvaluator.Sample sample : samples) {
            assertThat(sample.id()).isNotBlank();
            assertThat(ids.add(sample.id())).as("样本 ID 重复: %s", sample.id()).isTrue();
            assertThat(sample.goal()).isNotBlank();
            assertThat(sample.message()).isNotBlank();
            assertThat(sample.requiredFacts()).isNotEmpty();
            assertThat(sample.requiredSections()).isNotEmpty();
            assertThat(sample.maxLoopSteps()).isBetween(1, 3);
        }
        assertThat(samples).anyMatch(AgentQualityEvaluator.Sample::ragRequired);
        assertThat(samples).anyMatch(sample -> !sample.ragRequired());
    }

    private List<AgentQualityEvaluator.Sample> samples() throws IOException {
        String jsonl = new String(
            new ClassPathResource(DATASET).getInputStream().readAllBytes(), StandardCharsets.UTF_8
        );
        return jsonl.lines().filter(line -> !line.isBlank()).map(line -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = objectMapper.readValue(line, Map.class);
                return AgentQualityEvaluator.Sample.fromMap(raw);
            } catch (Exception error) {
                throw new IllegalStateException("Agent 质量样本解析失败: " + line, error);
            }
        }).toList();
    }
}
