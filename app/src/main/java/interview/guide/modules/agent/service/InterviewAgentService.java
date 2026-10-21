package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 面试教练 Agent 编排服务。
 *
 * <p>当前采用可观察的确定性工作流：读取面试上下文 -> 组织分析任务 -> 流式生成建议。
 * 这条链路先复用现有面试报告和模型注册中心，后续可在本模块内增加工具调用和记忆。</p>
 */
@Slf4j
@Service
public class InterviewAgentService {
    private final AgentContextService agentContextService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate fixedContextPromptTemplate;
    private final PromptTemplate userPromptTemplate;

    public InterviewAgentService(
        AgentContextService agentContextService,
        LlmProviderRegistry llmProviderRegistry,
        PromptSanitizer promptSanitizer,
        ResourceLoader resourceLoader
    ) throws IOException {
        this.agentContextService = agentContextService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        this.systemPromptTemplate = new PromptTemplate(resourceLoader
            .getResource("classpath:prompts/agent-interview-coach-system.st")
            .getContentAsString(StandardCharsets.UTF_8));
        this.fixedContextPromptTemplate = new PromptTemplate(resourceLoader
            .getResource("classpath:prompts/agent-interview-coach-context.st")
            .getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(resourceLoader
            .getResource("classpath:prompts/agent-interview-coach-user.st")
            .getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 执行一次面试教练任务并以 SSE 事件流返回结果。
     */
    public Flux<AgentStreamEvent> stream(String sessionId, AgentMessageRequest request) {
        return Flux.concat(
            Flux.just(AgentStreamEvent.step("context", "running")),
            Flux.defer(() -> {
            AgentContextService.AgentContext context = agentContextService.build(sessionId, request);
            List<AgentHistoryMessage> historyMessages = AgentHistoryPolicy.normalize(request.history());
            String message = promptSanitizer.sanitize(request.message());

            Map<String, Object> fixedContextVariables = Map.of(
                "context", promptSanitizer.wrapWithStableDelimiters(
                    "interview-report", context.interviewReport()),
                "skill", promptSanitizer.wrapWithStableDelimiters(
                    "skill-reference", context.skillReference())
            );
            Map<String, Object> variables = Map.of(
                "goal", promptSanitizer.sanitize(request.goal()),
                "question", promptSanitizer.wrapWithDelimiters("user-message", message),
                "rag", promptSanitizer.wrapWithDelimiters("rag-context", context.ragContext())
            );

            String systemPrompt = systemPromptTemplate.render()
                + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION
                + "\n\n"
                + fixedContextPromptTemplate.render(fixedContextVariables);
            String userPrompt = userPromptTemplate.render(variables);
            List<Message> history = buildModelHistory(historyMessages);
            ChatClient chatClient = llmProviderRegistry.getPlainChatClient(request.providerId());

            var promptSpec = chatClient.prompt().system(systemPrompt);
            if (!history.isEmpty()) {
                promptSpec = promptSpec.messages(history);
            }

            Flux<AgentStreamEvent> response = promptSpec
                .user(userPrompt)
                .stream()
                .content()
                .map(AgentStreamEvent::delta)
                .concatWith(Flux.just(
                    AgentStreamEvent.step("insight", "completed"),
                    AgentStreamEvent.step("action", "completed"),
                    AgentStreamEvent.done()
                ));

            return Flux.concat(
                Flux.just(
                    AgentStreamEvent.step("context", "completed"),
                    AgentStreamEvent.step("insight", "running"),
                    AgentStreamEvent.step("retrieval", "running"),
                    AgentStreamEvent.step("retrieval", "completed"),
                    AgentStreamEvent.sources(context.sources()),
                    AgentStreamEvent.step("action", "running")
                ),
                response
            );
        })
        ).onErrorResume(BusinessException.class, error -> Flux.just(
            AgentStreamEvent.error(error.getMessage())
        )).onErrorResume(Exception.class, error -> {
            log.error("面试教练 Agent 执行失败: sessionId={}", sessionId, error);
            return Flux.just(AgentStreamEvent.error("AI 面试教练暂时不可用，请稍后重试"));
        });
    }

    private List<Message> buildModelHistory(List<AgentHistoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>();
        for (AgentHistoryMessage message : messages) {
            String content = promptSanitizer.sanitize(message.content());
            if ("assistant".equals(message.role())) {
                result.add(new AssistantMessage(content));
            } else {
                result.add(new UserMessage(content));
            }
        }
        return result;
    }
}
