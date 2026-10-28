package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.metrics.AgentRunBudgetExceededException;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentMessageRequest;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

/**
 * 受控 Agent Loop 的规划阶段。
 *
 * <p>规划器只产出短小的可执行步骤摘要，不展示隐藏推理；步骤由显式的
 * {@link AgentLoopEngine} 有限循环执行，最终回答仍由已有的 SkillsTool、ToolCallingAdvisor
 * 和流式回答阶段完成。规划失败时自动回退，
 * 不会让 Agent 因为新增阶段不可用而整体不可用。</p>
 */
@Slf4j
@Service
public class AgentLoopPlanner {
    private static final String TOOL_ACTION_PREFIX = "ACTION=";
    private static final String TOOL_READ_INTERVIEW_REPORT = "read_interview_report";
    private static final String TOOL_CONFIRM_AGENT_GOAL = "confirm_agent_goal";
    private static final String TOOL_VERIFY_SKILL_RAG_SOURCES = "verify_skill_rag_sources";
    private static final String TOOL_ORGANIZE_AGENT_CONTEXT = "organize_agent_context";

    public static final String DEFAULT_PLAN =
        "1. 读取本场面试报告并确认当前目标。\n"
            + "2. 结合 Skill 参考资料和可用知识库核对薄弱点。\n"
            + "3. 输出有证据、可执行且格式清晰的面试教练建议。";
    private static final int MAX_PLAN_CHARS = 1_200;

    private final LlmProviderRegistry providerRegistry;
    private final PromptSanitizer promptSanitizer;
    private final AgentRetryProperties retryProperties;
    private final AgentMetrics agentMetrics;

    @Autowired
    public AgentLoopPlanner(
        LlmProviderRegistry providerRegistry,
        PromptSanitizer promptSanitizer,
        AgentRetryProperties retryProperties
    ) {
        this(providerRegistry, promptSanitizer, retryProperties, null);
    }

    public AgentLoopPlanner(
        LlmProviderRegistry providerRegistry,
        PromptSanitizer promptSanitizer,
        AgentRetryProperties retryProperties,
        AgentMetrics agentMetrics
    ) {
        this.providerRegistry = providerRegistry;
        this.promptSanitizer = promptSanitizer;
        this.retryProperties = retryProperties;
        this.agentMetrics = agentMetrics;
    }

    public String plan(
        AgentContextService.AgentContext context,
        AgentMessageRequest request,
        String message,
        List<AgentHistoryMessage> history
    ) {
        return plan(context, request, message, history, null);
    }

    public String plan(
        AgentContextService.AgentContext context,
        AgentMessageRequest request,
        String message,
        List<AgentHistoryMessage> history,
        AgentMetrics.RunTracker runTracker
    ) {
        if (!retryProperties.isLoopEnabled()) {
            return DEFAULT_PLAN;
        }
        String prompt = """
            你是 AI 面试教练的规划器。请只输出最多 3 行动作，不要输出隐藏推理、长篇解释或 Markdown 标题。
            每一行必须优先使用 ACTION= 后的一个安全只读工具标识：
            read_interview_report、confirm_agent_goal、verify_skill_rag_sources、organize_agent_context。
            你可以根据当前请求和上下文自主选择需要的工具，但不能编造其他工具名、参数或写入动作。
            如果没有必要检索知识库，选择 organize_agent_context；没有来源时不要选择 verify_skill_rag_sources。
            只有相邻且互不依赖的只读步骤才可以在步骤开头加 [parallel] 标记；不要为了凑并行而标记有先后依赖的步骤。
            当前目标：%s
            当前请求：%s
            可用 Skill：%s
            可用知识库摘要：%s
            """.formatted(
            promptSanitizer.sanitize(request.goal()),
            promptSanitizer.sanitize(message),
            promptSanitizer.sanitize(context.skillId()),
            promptSanitizer.sanitize(context.retrievalQuery())
        );
        for (String providerId : providerRegistry.resolveChatProviderCandidates(request.providerId())) {
            AgentMetrics.ModelCallTracker modelCall = null;
            try {
                if (agentMetrics != null) {
                    modelCall = agentMetrics.startModelCall(providerId, runTracker);
                }
                ChatClient client = providerRegistry.getAgentChatClient(providerId);
                var promptSpec = client.prompt()
                    .system("你是一个严格受控的 Agent Loop planner，只返回执行计划，不要编造资料。");
                if (runTracker != null) {
                    promptSpec = promptSpec.toolContext(Map.of(
                        AgentMetrics.RUN_TRACKER_TOOL_CONTEXT_KEY, runTracker
                    ));
                }
                String content = promptSpec.user(prompt).call().content();
                if (agentMetrics != null) {
                    agentMetrics.finishModelCall(modelCall, "success");
                }
                String normalized = normalize(content);
                if (!normalized.isBlank()) {
                    return normalized;
                }
            } catch (Throwable error) {
                if (agentMetrics != null) {
                    agentMetrics.finishModelCall(modelCall, "failure");
                }
                if (error instanceof AgentRunBudgetExceededException) {
                    throw (AgentRunBudgetExceededException) error;
                }
                log.debug("Agent Loop 规划阶段失败，将尝试下一个 Provider: providerId={}", providerId, error);
            }
        }
        return DEFAULT_PLAN;
    }

    private String normalize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        List<String> normalizedSteps = steps(content);
        String value = IntStream.range(0, normalizedSteps.size())
            .mapToObj(index -> (index + 1) + ". " + normalizedSteps.get(index))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
        return value.substring(0, Math.min(MAX_PLAN_CHARS, value.length()));
    }

    /**
     * 把模型返回的计划规整成有限步骤；这里是 Loop 的边界，不允许模型通过计划文本
     * 无限扩展后续工作量。ToolCallingAdvisor 的工具调用仍在最终回答阶段自行完成。
     */
    public List<String> steps(String plan) {
        return steps(plan, null);
    }

    /**
     * 根据本轮真实上下文裁剪只读资料动作；没有 RAG 来源时，不把“核对 RAG”写进执行轨迹，
     * 避免轨迹暗示 Agent 使用了不存在的外部资料。
     */
    public List<String> steps(String plan, AgentContextService.AgentContext context) {
        if (plan == null || plan.isBlank()) {
            return List.of("读取面试报告并确认当前目标");
        }
        int maxSteps = Math.max(1, retryProperties.getLoopMaxSteps());
        boolean hasRagSource = context == null || context.sources().stream()
            .anyMatch(source -> source != null && "rag".equalsIgnoreCase(source.kind()));
        List<String> result = Arrays.stream(plan.split("\\R"))
            .map(String::trim)
            .map(value -> value.replaceFirst("^(?:[-*]\\s*|\\d+[.)]\\s*)", ""))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .limit(maxSteps)
            .map(value -> canonicalizeStep(value, hasRagSource))
            .toList();
        return result.isEmpty() ? List.of("读取面试报告并确认当前目标") : result;
    }

    /**
     * 规划模型可以用自然语言描述动作，但对外的 Loop 轨迹必须使用有限的审计动作集合。
     * 这样既不会把模型的自由文本误当成工具调用，也能让恢复、评测和前端展示使用稳定标签。
     */
    private String canonicalizeStep(String value, boolean hasRagSource) {
        boolean parallel = value.startsWith("[parallel]");
        String action = parallel ? value.substring("[parallel]".length()).trim() : value;
        String explicitTool = explicitTool(action);
        if (explicitTool != null) {
            String selected = switch (explicitTool) {
                case TOOL_READ_INTERVIEW_REPORT -> "读取面试报告";
                case TOOL_CONFIRM_AGENT_GOAL -> "确认当前目标";
                case TOOL_VERIFY_SKILL_RAG_SOURCES -> hasRagSource
                    ? "核对 Skill/RAG 来源" : "整理";
                case TOOL_ORGANIZE_AGENT_CONTEXT -> "整理";
                default -> "整理";
            };
            return parallel ? "[parallel] " + selected : selected;
        }
        String normalized = action.toLowerCase(java.util.Locale.ROOT);
        String canonical = action;
        if (action.contains("读取") && action.contains("报告")) {
            canonical = "读取面试报告";
        } else if (action.contains("确认") && (action.contains("目标") || action.contains("边界"))) {
            canonical = "确认当前目标";
        } else if (hasRagSource && (normalized.contains("skill") || normalized.contains("rag")
            || action.contains("资料") || action.contains("知识库") || action.contains("检索")
            || action.contains("来源"))) {
            canonical = "核对 Skill/RAG 来源";
        } else if (action.contains("整理") || action.contains("输出") || action.contains("建议")
            || action.contains("行动")) {
            canonical = "整理";
        } else {
            canonical = "整理";
        }
        return parallel ? "[parallel] " + canonical : canonical;
    }

    /**
     * 只接受完整的 ACTION= 工具标识。其余模型输出继续走兼容的自然语言规整逻辑，
     * 但不会把未知标识直接传给 Loop 执行器。
     */
    private String explicitTool(String action) {
        if (action == null || !action.startsWith(TOOL_ACTION_PREFIX)) {
            return null;
        }
        String value = action.substring(TOOL_ACTION_PREFIX.length()).trim();
        if (value.isBlank() || value.contains(" ") || value.contains("\t")) {
            return "__invalid__";
        }
        return value;
    }
}
