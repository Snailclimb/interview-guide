package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentMessageRequest;
import java.util.List;
import java.util.Arrays;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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
    public static final String DEFAULT_PLAN =
        "1. 读取本场面试报告并确认当前目标。\n"
            + "2. 结合 Skill 参考资料和可用知识库核对薄弱点。\n"
            + "3. 输出有证据、可执行且格式清晰的面试教练建议。";
    private static final int MAX_PLAN_CHARS = 1_200;

    private final LlmProviderRegistry providerRegistry;
    private final PromptSanitizer promptSanitizer;
    private final AgentRetryProperties retryProperties;

    public AgentLoopPlanner(
        LlmProviderRegistry providerRegistry,
        PromptSanitizer promptSanitizer,
        AgentRetryProperties retryProperties
    ) {
        this.providerRegistry = providerRegistry;
        this.promptSanitizer = promptSanitizer;
        this.retryProperties = retryProperties;
    }

    public String plan(
        AgentContextService.AgentContext context,
        AgentMessageRequest request,
        String message,
        List<AgentHistoryMessage> history
    ) {
        if (!retryProperties.isLoopEnabled()) {
            return DEFAULT_PLAN;
        }
        String prompt = """
            你是 AI 面试教练的规划器。请只输出最多 3 条短步骤，不要输出隐藏推理、长篇解释或 Markdown 标题。
            规划必须围绕：读取面试报告、必要时使用只读 Skill/RAG 资料、给出可执行回答。
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
            try {
                ChatClient client = providerRegistry.getAgentChatClient(providerId);
                String content = client.prompt()
                    .system("你是一个严格受控的 Agent Loop planner，只返回执行计划，不要编造资料。")
                    .user(prompt)
                    .call()
                    .content();
                String normalized = normalize(content);
                if (!normalized.isBlank()) {
                    return normalized;
                }
            } catch (Throwable error) {
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
        if (plan == null || plan.isBlank()) {
            return List.of("读取面试报告并确认当前目标");
        }
        int maxSteps = Math.max(1, retryProperties.getLoopMaxSteps());
        List<String> result = Arrays.stream(plan.split("\\R"))
            .map(String::trim)
            .map(value -> value.replaceFirst("^(?:[-*]\\s*|\\d+[.)]\\s*)", ""))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .limit(maxSteps)
            .toList();
        return result.isEmpty() ? List.of("读取面试报告并确认当前目标") : result;
    }
}
