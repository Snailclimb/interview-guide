package interview.guide.modules.agent.evaluation;

import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.agent.service.AgentLoopEngine;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 质量基线评估")
@Tag("agent-eval")
class AgentQualityEvaluatorTest {

    private final AgentQualityEvaluator evaluator = new AgentQualityEvaluator();

    @Test
    @DisplayName("完整回答、合法引用和受控 Loop 通过质量门禁")
    void acceptsGroundedCompleteAnswer() {
        AgentQualityEvaluator.Sample sample = new AgentQualityEvaluator.Sample(
            "good", "复盘", "请给建议", "dev",
            List.of("Redis 缓存", "行动"), List.of("已获得满分"),
            List.of("结论", "行动建议"), true, 3,
            List.of("读取面试报告", "核对 Skill/RAG 来源", "整理")
        );
        String answer = "## 结论\n已识别 Redis 缓存问题。\n\n## 行动建议\n- 形成行动清单\n依据见 [R1]。";
        List<AgentLoopEngine.LoopIteration> loop = List.of(
            new AgentLoopEngine.LoopIteration(1, "读取面试报告", "已读取报告"),
            new AgentLoopEngine.LoopIteration(2, "核对 Skill/RAG 来源", "已核对 RAG 来源")
        );

        AgentQualityEvaluator.Evaluation result = evaluator.evaluate(
            sample, answer, List.of(new AgentSource("rag", "Java 指南", 0.9, "R1")), loop
        );

        assertThat(result.passed()).as("violations=%s", result.violations()).isTrue();
        assertThat(result.overallScore()).isEqualTo(1.0);
        assertThat(result.scores()).containsEntry("grounding", 1.0);
    }

    @Test
    @DisplayName("缺少事实、越界引用和越界动作会被识别")
    void rejectsRegressions() {
        AgentQualityEvaluator.Sample sample = new AgentQualityEvaluator.Sample(
            "bad", "复盘", "请给建议", "dev",
            List.of("Redis 缓存"), List.of("已经修改面试报告"),
            List.of("结论"), true, 1, List.of("读取面试报告")
        );
        String answer = "## 结论\n已经修改面试报告。\n依据 [R9]。\n```";
        List<AgentLoopEngine.LoopIteration> loop = List.of(
            new AgentLoopEngine.LoopIteration(1, "写入系统", "已完成"),
            new AgentLoopEngine.LoopIteration(2, "读取面试报告", "再次读取")
        );

        AgentQualityEvaluator.Evaluation result = evaluator.evaluate(
            sample, answer, List.of(new AgentSource("rag", "资料", 0.8, "R1")), loop
        );

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).anyMatch(item -> item.contains("缺少必要事实"));
        assertThat(result.violations()).anyMatch(item -> item.contains("禁止事实"));
        assertThat(result.violations()).anyMatch(item -> item.contains("未检索到的来源"));
        assertThat(result.violations()).anyMatch(item -> item.contains("代码围栏"));
        assertThat(result.violations()).anyMatch(item -> item.contains("步骤超过预算"));
        assertThat(result.violations()).anyMatch(item -> item.contains("动作越界"));
    }

    @Test
    @DisplayName("没有 RAG 来源且没有虚构引用时通过边界检查")
    void acceptsNoRagBoundary() {
        AgentQualityEvaluator.Sample sample = new AgentQualityEvaluator.Sample(
            "no-rag", "复盘", "请说明边界", "dev",
            List.of("没有知识库"), List.of("引用了知识库"),
            List.of("依据边界"), false, 3, List.of()
        );

        AgentQualityEvaluator.Evaluation result = evaluator.evaluate(
            sample, "## 依据边界\n本次没有知识库，以下仅依据面试报告。", List.of(), List.of()
        );

        assertThat(result.passed()).isTrue();
        assertThat(result.scores()).containsEntry("grounding", 1.0);
    }
}
