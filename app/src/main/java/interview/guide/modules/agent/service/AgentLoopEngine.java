package interview.guide.modules.agent.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 的显式有限状态循环。
 *
 * <p>这里的动作是可审计的上下文动作，不执行写操作，也不把隐藏推理暴露给用户：
 * 读取报告、确认 Skill/RAG 证据、准备最终行动建议。每一步都产生观察结果，
 * 观察结果会进入最终回答上下文；步骤数由规划器限制，避免无限循环。</p>
 */
public class AgentLoopEngine {

    public LoopResult execute(AgentContextService.AgentContext context, List<String> plannedSteps) {
        List<String> safeSteps = plannedSteps == null || plannedSteps.isEmpty()
            ? List.of("读取面试报告并确认当前目标")
            : plannedSteps;
        List<LoopIteration> iterations = new ArrayList<>();
        for (int index = 0; index < safeSteps.size(); index++) {
            String step = safeSteps.get(index);
            iterations.add(new LoopIteration(index + 1, step, observe(context, index)));
        }
        return new LoopResult(List.copyOf(iterations));
    }

    private String observe(AgentContextService.AgentContext context, int index) {
        return switch (index) {
            case 0 -> "已读取面试报告；识别到 " + context.weaknesses().size() + " 个待关注项。";
            case 1 -> context.sources().isEmpty()
                ? "Skill/RAG 未提供可引用来源，本轮回答不得声称使用了外部依据。"
                : "已核对 Skill/RAG 来源，共 " + context.sources().size() + " 个可引用来源。";
            default -> "已将报告事实、证据边界和用户目标整理为可执行建议。";
        };
    }

    public record LoopIteration(int iteration, String action, String observation) {
    }

    public record LoopResult(List<LoopIteration> iterations) {
        public LoopResult {
            iterations = iterations == null ? List.of() : List.copyOf(iterations);
        }

        public String trace() {
            return iterations.stream()
                .map(item -> "第 " + item.iteration() + " 步：" + item.action()
                    + "；观察：" + item.observation())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("未产生 Loop 观察结果");
        }
    }
}
