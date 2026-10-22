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
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.core.Disposable;

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
    /** 保护 SSE、内存和数据库；正常面试复盘远低于此上限。 */
    private static final int MAX_AGENT_OUTPUT_CHARS = 32_000;
    private static final String OUTPUT_TRUNCATION_NOTICE = "\n\n[回答已达到展示上限，后续内容已截断]";

    private final AgentContextService agentContextService;
    private final AgentConversationService agentConversationService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate fixedContextPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Sinks.One<Void>> cancellationSignals = new ConcurrentHashMap<>();
    private final Map<String, AgentTaskHandle> activeTasks = new ConcurrentHashMap<>();

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
            Sinks.One<Void> cancellationSignal = Sinks.one();
            cancellationSignals.put(sessionId, cancellationSignal);
            Long preparedAssistantMessageId = null;
            AtomicReference<AgentTaskHandle> taskHandleRef = new AtomicReference<>();
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
                preparedAssistantMessageId = preparedMessage.assistantMessageId();
                AgentTaskHandle taskHandle = new AgentTaskHandle(
                    preparedMessage.assistantMessageId(), cancellationSignal
                );
                taskHandleRef.set(taskHandle);
                activeTasks.put(sessionId, taskHandle);

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
                Flux<Boolean> remoteCancellation = monitorRemoteCancellation(sessionId, preparedMessage.runId());

                Flux<AgentStreamEvent> workflow = Flux.concat(
                    Flux.just(
                        AgentStreamEvent.step("context", "completed"),
                        AgentStreamEvent.step("insight", "running"),
                        AgentStreamEvent.step("retrieval", "running"),
                        AgentStreamEvent.step("retrieval", "completed"),
                        AgentStreamEvent.sources(context.sources()),
                        AgentStreamEvent.step("action", "running")
                    ),
                    response
                )
                    .takeUntilOther(Flux.merge(cancellationSignal.asMono(), remoteCancellation))
                    .doFinally(signal -> {
                        activeSessions.remove(sessionId);
                        cancellationSignals.remove(sessionId, cancellationSignal);
                        activeTasks.remove(sessionId, taskHandle);
                    });
                // SSE 断开后不缓存全部 token；最终答案由 AgentConversationService 持久化。
                return Flux.create(emitter -> {
                    taskHandle.subscription = workflow.subscribe(
                        emitter::next,
                        error -> {
                            log.error("面试教练 Agent 后台任务失败: sessionId={}", sessionId, error);
                            if (!emitter.isCancelled()) {
                                emitter.next(AgentStreamEvent.error(toAgentErrorMessage(error)));
                            }
                            emitter.complete();
                        },
                        emitter::complete
                    );
                }, FluxSink.OverflowStrategy.DROP);
            } catch (Throwable error) {
                if (preparedAssistantMessageId != null) {
                    failPreparedMessage(preparedAssistantMessageId, error);
                }
                activeSessions.remove(sessionId);
                cancellationSignals.remove(sessionId, cancellationSignal);
                AgentTaskHandle taskHandle = taskHandleRef.get();
                if (taskHandle == null) {
                    activeTasks.remove(sessionId);
                } else {
                    activeTasks.remove(sessionId, taskHandle);
                }
                return Flux.error(error);
            }
        })
        ).onErrorResume(AgentTaskBusyException.class, error -> Flux.just(
            AgentStreamEvent.error(error.getMessage())
        )).onErrorResume(BusinessException.class, error -> Flux.just(
            AgentStreamEvent.error(error.getMessage())
        )).onErrorResume(Exception.class, error -> {
            log.error("面试教练 Agent 执行失败: sessionId={}", sessionId, error);
            return Flux.just(AgentStreamEvent.error(toAgentErrorMessage(error)));
        });
    }

    /** 发出服务端取消信号；如果任务不在当前实例，数据库取消仍由会话服务负责。 */
    public boolean cancel(String sessionId) {
        AgentTaskHandle taskHandle = activeTasks.get(sessionId);
        if (taskHandle != null) {
            // 已订阅的模型流会通过 doOnCancel 持久化；尚未完成订阅时由这里兜底写状态。
            if (!taskHandle.cancel()) {
                agentConversationService.cancelMessage(taskHandle.assistantMessageId, "");
            }
            return true;
        }
        Sinks.One<Void> signal = cancellationSignals.get(sessionId);
        return signal != null && signal.tryEmitEmpty().isSuccess();
    }

    private static final class AgentTaskHandle {
        private final Long assistantMessageId;
        private final Sinks.One<Void> cancellationSignal;
        private volatile Disposable subscription;

        private AgentTaskHandle(Long assistantMessageId, Sinks.One<Void> cancellationSignal) {
            this.assistantMessageId = assistantMessageId;
            this.cancellationSignal = cancellationSignal;
        }

        private boolean cancel() {
            cancellationSignal.tryEmitEmpty();
            Disposable current = subscription;
            if (current != null) {
                current.dispose();
                return true;
            }
            return false;
        }
    }

    private void failPreparedMessage(Long messageId, Throwable error) {
        try {
            agentConversationService.failMessage(messageId, "", toAgentErrorMessage(error));
        } catch (Throwable persistenceError) {
            log.warn("Agent 任务失败状态持久化失败: messageId={}", messageId, persistenceError);
        }
    }

    private Flux<Boolean> monitorRemoteCancellation(String sessionId, String runId) {
        return Flux.interval(Duration.ofSeconds(2))
            .flatMap(ignored -> Mono.fromCallable(() ->
                    agentConversationService.isRunActive(sessionId, runId)
                )
                .subscribeOn(Schedulers.boundedElastic())
                // 数据库瞬时异常时继续执行，避免监测故障误杀模型任务。
                .onErrorReturn(true)
            )
            .filter(active -> !active);
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
                String emittedNotice = appendOutputChunk(notice, output);
                if (emittedNotice.isBlank()) {
                    return Flux.empty();
                }
                return Flux.just(AgentStreamEvent.delta(emittedNotice));
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
            })
            .doOnComplete(() -> llmProviderRegistry.markChatProviderSuccess(providerId))
            .map(content -> appendOutputChunk(content, output))
            // 达到展示上限后立即取消上游模型流，避免继续消耗模型额度。
            .takeUntil(ignored -> output.get().length() >= MAX_AGENT_OUTPUT_CHARS)
            .filter(content -> !content.isEmpty())
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

    private String appendOutputChunk(String content, AtomicReference<StringBuilder> output) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder target = output.get();
        int contentLimit = MAX_AGENT_OUTPUT_CHARS - OUTPUT_TRUNCATION_NOTICE.length();
        if (target.length() >= contentLimit) {
            if (!target.toString().endsWith(OUTPUT_TRUNCATION_NOTICE)) {
                target.append(OUTPUT_TRUNCATION_NOTICE);
            }
            return "";
        }

        int remaining = contentLimit - target.length();
        if (content.length() <= remaining) {
            target.append(content);
            if (target.length() >= contentLimit) {
                target.append(OUTPUT_TRUNCATION_NOTICE);
                return content + OUTPUT_TRUNCATION_NOTICE;
            }
            return content;
        }

        String visible = content.substring(0, remaining);
        target.append(visible).append(OUTPUT_TRUNCATION_NOTICE);
        return visible + OUTPUT_TRUNCATION_NOTICE;
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
