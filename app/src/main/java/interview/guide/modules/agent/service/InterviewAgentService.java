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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 面试教练 Agent 编排服务。
 *
 * <p>当前采用可观察的确定性工作流：读取面试上下文 -> 组织分析任务 -> 流式生成建议。
 * 对话记录由独立的 Agent 会话服务持久化，不改变既有面试和 RAG 会话。</p>
 */
@Slf4j
@Service
public class InterviewAgentService {
    /** 最多等待并重试 6 次；退避时间合计约 90 秒，并通过 SSE 持续告知前端。 */
    private static final int MODEL_RETRY_ATTEMPTS = 6;

    private final AgentContextService agentContextService;
    private final AgentConversationService agentConversationService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate fixedContextPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    public InterviewAgentService(
        AgentContextService agentContextService,
        AgentConversationService agentConversationService,
        LlmProviderRegistry llmProviderRegistry,
        PromptSanitizer promptSanitizer,
        ResourceLoader resourceLoader
    ) throws IOException {
        this.agentContextService = agentContextService;
        this.agentConversationService = agentConversationService;
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
            if (!activeSessions.add(sessionId)) {
                return Flux.just(AgentStreamEvent.error("当前面试已有一个 Agent 任务正在执行，请等待完成后再试"));
            }
            try {
                AgentContextService.AgentContext context = agentContextService.build(sessionId, request);
            String message = promptSanitizer.sanitize(request.message());
            List<AgentHistoryMessage> persistedHistory = agentConversationService.loadHistory(sessionId);
            List<AgentHistoryMessage> historyMessages = AgentHistoryPolicy.normalize(
                persistedHistory.isEmpty() ? request.history() : persistedHistory
            );

            AgentConversationService.PreparedMessage preparedMessage = agentConversationService.prepareMessage(
                sessionId,
                promptSanitizer.sanitize(request.goal()),
                request.providerId(),
                message
            );

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
            Flux<AgentStreamEvent> response = streamModelWithRetry(
                providerId -> {
                    ChatClient chatClient = llmProviderRegistry.getAgentChatClient(providerId);
                    return () -> {
                        var promptSpec = chatClient.prompt().system(systemPrompt);
                        if (!history.isEmpty()) {
                            promptSpec = promptSpec.messages(history);
                        }
                        return promptSpec.user(userPrompt).stream().content();
                    };
                },
                request.providerId(),
                sessionId,
                preparedMessage.assistantMessageId(),
                context.sources()
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
            ).doFinally(signal -> activeSessions.remove(sessionId));
            } catch (Throwable error) {
                activeSessions.remove(sessionId);
                return Flux.error(error);
            }
        })
        ).onErrorResume(BusinessException.class, error -> Flux.just(
            AgentStreamEvent.error(error.getMessage())
        )).onErrorResume(Exception.class, error -> {
            log.error("面试教练 Agent 执行失败: sessionId={}", sessionId, error);
            return Flux.just(AgentStreamEvent.error(toAgentErrorMessage(error)));
        });
    }

    private Flux<AgentStreamEvent> streamModelWithRetry(
        java.util.function.Function<String, Supplier<Flux<String>>> modelStreamFactory,
        String providerId,
        String sessionId,
        Long assistantMessageId,
        List<interview.guide.modules.agent.model.AgentSource> sources
    ) {
        AtomicBoolean modelOutputStarted = new AtomicBoolean();
        AtomicReference<StringBuilder> output = new AtomicReference<>(new StringBuilder());
        List<String> providers = llmProviderRegistry.resolveChatProviderCandidates(providerId);
        Flux<AgentStreamEvent> providerStream = providers.isEmpty()
            ? Flux.error(new IllegalStateException("未配置可用的聊天模型 Provider"))
            : streamProviderAttempt(
            modelStreamFactory,
            providers,
            0,
            sessionId,
            assistantMessageId,
            modelOutputStarted,
            output
        );
        return providerStream
            .concatWith(Flux.defer(() -> {
                String notice = AgentAnswerGroundingPolicy.buildNotice(output.get().toString(), sources);
                if (notice.isBlank()) {
                    return Flux.empty();
                }
                output.get().append(notice);
                return Flux.just(AgentStreamEvent.delta(notice));
            }))
            .concatWith(Flux.just(
                AgentStreamEvent.step("insight", "completed"),
                AgentStreamEvent.step("action", "completed"),
                AgentStreamEvent.done()
            ))
            .doOnComplete(() -> agentConversationService.completeMessage(
                assistantMessageId,
                output.get().toString()
            ))
            .doOnError(error -> agentConversationService.failMessage(
                assistantMessageId,
                output.get().toString(),
                toAgentErrorMessage(error)
            ))
            .doOnCancel(() -> agentConversationService.cancelMessage(
                assistantMessageId,
                output.get().toString()
            ));
    }

    private Flux<AgentStreamEvent> streamProviderAttempt(
        java.util.function.Function<String, Supplier<Flux<String>>> modelStreamFactory,
        List<String> providers,
        int providerIndex,
        String sessionId,
        Long assistantMessageId,
        AtomicBoolean modelOutputStarted,
        AtomicReference<StringBuilder> output
    ) {
        String currentProvider = providers.get(providerIndex);
        return Flux.defer(() -> streamModelAttempt(
                modelStreamFactory.apply(currentProvider),
                currentProvider,
                sessionId,
                modelOutputStarted,
                output,
                0
            )).onErrorResume(error -> {
            if (!modelOutputStarted.get()
                && isProviderSwitchableError(error)
                && providerIndex < providers.size() - 1) {
                String nextProvider = providers.get(providerIndex + 1);
                log.warn(
                    "Agent 当前 Provider 不可用，切换备用 Provider: current={}, next={}, sessionId={}",
                    currentProvider,
                    nextProvider,
                    sessionId
                );
                return Flux.concat(
                    Flux.just(AgentStreamEvent.waiting(
                        "insight",
                        "当前模型暂时不可用，正在切换备用模型（" + nextProvider + "）"
                    )),
                    streamProviderAttempt(
                        modelStreamFactory,
                        providers,
                        providerIndex + 1,
                        sessionId,
                        assistantMessageId,
                        modelOutputStarted,
                        output
                    )
                );
            }
            return Flux.error(error);
        });
    }

    private Flux<AgentStreamEvent> streamModelAttempt(
        Supplier<Flux<String>> modelStream,
        String providerId,
        String sessionId,
        AtomicBoolean modelOutputStarted,
        AtomicReference<StringBuilder> output,
        int attempt
    ) {
        return Flux.defer(modelStream)
            .doOnNext(content -> {
                modelOutputStarted.set(true);
                if (content != null) {
                    output.get().append(content);
                }
            })
            .doOnComplete(() -> llmProviderRegistry.markChatProviderSuccess(providerId))
            .map(AgentStreamEvent::delta)
            .onErrorResume(error -> {
                if (isProviderSwitchableError(error)) {
                    llmProviderRegistry.markChatProviderFailure(providerId);
                }
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
                                modelStream, providerId, sessionId, modelOutputStarted, output, retryNumber
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

    private boolean isProviderSwitchableError(Throwable error) {
        if (isTransientModelError(error) || isProviderAccountUnavailable(error)) {
            return true;
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("unknown llm provider")
                || normalized.contains("unauthorized")
                || normalized.contains("invalid api key")
                || normalized.contains("invalid_api_key")
                || normalized.contains("401")
                || normalized.contains("403")) {
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
