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
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 面试教练 Agent 编排服务。
 *
 * <p>当前采用可观察的确定性工作流：读取面试上下文 -> 组织分析任务 -> 流式生成建议。
 * 这条链路先复用现有面试报告和模型注册中心，后续可在本模块内增加工具调用和记忆。</p>
 */
@Slf4j
@Service
public class InterviewAgentService {
    /** 最多等待并重试 6 次；退避时间合计约 90 秒，并通过 SSE 持续告知前端。 */
    private static final int MODEL_RETRY_ATTEMPTS = 6;

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

            Supplier<Flux<String>> modelStream = () -> {
                var promptSpec = chatClient.prompt().system(systemPrompt);
                if (!history.isEmpty()) {
                    promptSpec = promptSpec.messages(history);
                }
                return promptSpec.user(userPrompt).stream().content();
            };
            Flux<AgentStreamEvent> response = streamModelWithRetry(
                modelStream, request.providerId(), sessionId
            );

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
            return Flux.just(AgentStreamEvent.error(toAgentErrorMessage(error)));
        });
    }

    private Flux<AgentStreamEvent> streamModelWithRetry(
        Supplier<Flux<String>> modelStream,
        String providerId,
        String sessionId
    ) {
        AtomicBoolean modelOutputStarted = new AtomicBoolean();
        return streamModelAttempt(modelStream, providerId, sessionId, modelOutputStarted, 0)
            .concatWith(Flux.just(
                AgentStreamEvent.step("insight", "completed"),
                AgentStreamEvent.step("action", "completed"),
                AgentStreamEvent.done()
            ));
    }

    private Flux<AgentStreamEvent> streamModelAttempt(
        Supplier<Flux<String>> modelStream,
        String providerId,
        String sessionId,
        AtomicBoolean modelOutputStarted,
        int attempt
    ) {
        return Flux.defer(modelStream)
            .doOnNext(content -> modelOutputStarted.set(true))
            .map(AgentStreamEvent::delta)
            .onErrorResume(error -> {
                if (!modelOutputStarted.get()
                    && isTransientModelError(error)
                    && attempt < MODEL_RETRY_ATTEMPTS) {
                    Duration delay = retryDelay(attempt);
                    int retryNumber = attempt + 1;
                    log.warn(
                        "Agent 模型服务暂时繁忙，将在 {} 秒后进行第 {} 次重试: providerId={}, sessionId={}",
                        delay.toSeconds(), retryNumber, providerId, sessionId
                    );
                    return Flux.concat(
                        Flux.just(AgentStreamEvent.waiting(
                            "insight",
                            "模型服务暂时繁忙，正在等待资源（第 " + retryNumber + "/"
                                + MODEL_RETRY_ATTEMPTS + " 次重试）"
                        )),
                        Mono.delay(delay).thenMany(Flux.concat(
                            Flux.just(AgentStreamEvent.step("insight", "running")),
                            streamModelAttempt(
                                modelStream, providerId, sessionId, modelOutputStarted, retryNumber
                            )
                        ))
                    );
                }
                return Flux.error(error);
            });
    }

    private Duration retryDelay(int attempt) {
        return Duration.ofSeconds(Math.min(30, 2L << attempt));
    }

    private String toAgentErrorMessage(Throwable error) {
        if (isProviderAccountUnavailable(error)) {
            return "当前模型账号不可用，请在模型设置中检查 API Key 或切换其他模型";
        }
        if (isTransientModelError(error)) {
            return "当前模型服务繁忙，请稍后重试；也可以在模型设置中切换其他模型";
        }
        return "AI 面试教练暂时不可用，请稍后重试";
    }

    private boolean isTransientModelError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("overloaded")
                || normalized.contains("temporarily unavailable")
                || normalized.contains("service unavailable")
                || normalized.contains("try again later")
                || normalized.contains("concurrency limit")
                || normalized.contains("rate limit")
                || normalized.contains("too many requests")
                || normalized.contains("429")) {
                return true;
            }
        }
        return false;
    }

    private boolean isProviderAccountUnavailable(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("selected account") && normalized.contains("no longer available")) {
                return true;
            }
        }
        return false;
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
