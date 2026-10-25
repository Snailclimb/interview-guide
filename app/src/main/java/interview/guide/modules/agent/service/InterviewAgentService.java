package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentStreamEvent;
import interview.guide.modules.agent.metrics.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * 面试教练 Agent 编排服务。
 *
 * <p>采用有边界的 Agent Loop：读取上下文 -> 规划 -> 通过只读 Skill/RAG 工具观察 ->
 * 流式执行最终回答。对话记录由独立的 Agent 会话服务持久化，不改变既有面试和 RAG 会话。</p>
 */
@Slf4j
@Service
public class InterviewAgentService {
    /** 重试次数和等待策略由 AgentRetryProperties 控制，并通过 SSE 持续告知前端。 */
    /** 保护 SSE、内存和数据库；正常面试复盘远低于此上限。 */
    private static final int MAX_AGENT_OUTPUT_CHARS = 32_000;
    private static final int CONTEXT_RETRY_HISTORY_CHARS = 8_000;
    private static final int CONTEXT_RETRY_MESSAGE_CHARS = 4_000;
    private static final int CONTEXT_RETRY_REPORT_CHARS = 6_000;
    private static final int CONTEXT_RETRY_SKILL_CHARS = 3_000;
    private static final int CONTEXT_RETRY_RAG_CHARS = 4_000;
    private static final String OUTPUT_TRUNCATION_NOTICE = "\n\n[回答已达到展示上限，后续内容已截断]";

    private final AgentContextService agentContextService;
    private final AgentConversationService agentConversationService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate fixedContextPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final AgentMetrics agentMetrics;
    private final AgentProgressWriter agentProgressWriter;
    private final AgentRetryProperties retryProperties;
    private final AgentLoopPlanner agentLoopPlanner;
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Sinks.One<Void>> cancellationSignals = new ConcurrentHashMap<>();
    private final Map<String, AgentTaskHandle> activeTasks = new ConcurrentHashMap<>();

    public InterviewAgentService(
        AgentContextService agentContextService,
        AgentConversationService agentConversationService,
        LlmProviderRegistry llmProviderRegistry,
        PromptSanitizer promptSanitizer,
        ResourceLoader resourceLoader,
        AgentMetrics agentMetrics,
        AgentProgressWriter agentProgressWriter,
        AgentRetryProperties retryProperties
    ) throws IOException {
        this(
            agentContextService, agentConversationService, llmProviderRegistry, promptSanitizer,
            resourceLoader, agentMetrics, agentProgressWriter, retryProperties, null
        );
    }

    @Autowired
    public InterviewAgentService(
        AgentContextService agentContextService,
        AgentConversationService agentConversationService,
        LlmProviderRegistry llmProviderRegistry,
        PromptSanitizer promptSanitizer,
        ResourceLoader resourceLoader,
        AgentMetrics agentMetrics,
        AgentProgressWriter agentProgressWriter,
        AgentRetryProperties retryProperties,
        AgentLoopPlanner agentLoopPlanner
    ) throws IOException {
        this.agentContextService = agentContextService;
        this.agentConversationService = agentConversationService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        this.agentMetrics = agentMetrics;
        this.agentProgressWriter = agentProgressWriter;
        this.retryProperties = retryProperties;
        this.agentLoopPlanner = agentLoopPlanner;
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
        return stream(sessionId, request, null);
    }

    /** API 调用必须带身份键；旧的内部测试/调用方仍可使用上面的兼容重载。 */
    public Flux<AgentStreamEvent> stream(
        String sessionId, AgentMessageRequest request, String ownerKey
    ) {
        String dataOwnerKey = AuthUserContext.currentUserId().orElse(null);
        if (ownerKey != null && !ownerKey.isBlank()) {
            agentConversationService.assertAccess(sessionId, request.agentSessionId(), ownerKey);
            request = request.withMemoryKey(ownerKey);
        }
        return streamInternal(sessionId, request, dataOwnerKey);
    }

    private Flux<AgentStreamEvent> streamInternal(
        String sessionId, AgentMessageRequest request, String dataOwnerKey
    ) {
        return Flux.concat(
            Flux.just(AgentStreamEvent.step("context", "running")),
            Flux.defer(() -> {
            String activeKey = request.agentSessionId() == null || request.agentSessionId().isBlank()
                ? sessionId : request.agentSessionId();
            if (!activeSessions.add(activeKey)) {
                return Flux.just(AgentStreamEvent.error("当前面试已有一个 Agent 任务正在执行，请等待完成后再试"));
            }
            Sinks.One<Void> cancellationSignal = Sinks.one();
            cancellationSignals.put(activeKey, cancellationSignal);
            Long preparedAssistantMessageId = null;
            AgentMetrics.RunTracker runTracker = agentMetrics.startRun();
            AtomicReference<AgentTaskHandle> taskHandleRef = new AtomicReference<>();
            AtomicReference<String> activeAgentSessionKeyRef = new AtomicReference<>(activeKey);
            try {
                String message = promptSanitizer.sanitize(request.message());
                List<AgentHistoryMessage> persistedHistory = request.agentSessionId() == null
                    || request.agentSessionId().isBlank()
                    ? agentConversationService.loadHistory(sessionId)
                    : agentConversationService.loadHistory(sessionId, request.agentSessionId());
                List<AgentHistoryMessage> historyMessages = AgentHistoryPolicy.normalize(
                    persistedHistory.isEmpty() ? request.history() : persistedHistory
                );
                AgentContextService.AgentContext context;
                AuthUserContext.set(dataOwnerKey);
                try {
                    context = agentContextService.build(sessionId, request, historyMessages);
                } finally {
                    AuthUserContext.clear();
                }

                AgentConversationService.PreparedMessage preparedMessage = prepareMessage(
                    sessionId, request, message
                );
                preparedAssistantMessageId = preparedMessage.assistantMessageId();
                AgentTaskHandle taskHandle = new AgentTaskHandle(
                    preparedMessage.assistantMessageId(), cancellationSignal
                );
                taskHandleRef.set(taskHandle);
                String resolvedAgentSessionId = preparedMessage.agentSessionId() == null
                    ? activeKey : preparedMessage.agentSessionId();
                activeAgentSessionKeyRef.set(resolvedAgentSessionId);
                activeTasks.put(resolvedAgentSessionId, taskHandle);
                persistRunProgress(
                    preparedMessage.assistantMessageId(), "context", "running", null
                );

                Flux<AgentStreamEvent> workflow = buildWorkflow(
                    sessionId, resolvedAgentSessionId, request, message, historyMessages, context, preparedMessage,
                    cancellationSignal, taskHandle, runTracker
                );
                // SSE 断开后不缓存全部 token；最终答案由 AgentConversationService 持久化。
                return Flux.create(emitter -> {
                    taskHandle.subscription = workflow.subscribe(
                        emitter::next,
                        error -> {
                            log.error("面试教练 Agent 后台任务失败: sessionId={}, agentSessionId={}",
                                sessionId, resolvedAgentSessionId, error);
                            if (!emitter.isCancelled()) {
                                emitter.next(AgentStreamEvent.error(toAgentErrorMessage(error)));
                            }
                            emitter.complete();
                        },
                        emitter::complete
                    );
                }, FluxSink.OverflowStrategy.BUFFER);
            } catch (Throwable error) {
                if (preparedAssistantMessageId != null) {
                    failPreparedMessage(preparedAssistantMessageId, error);
                    agentProgressWriter.finish(preparedAssistantMessageId);
                }
                agentMetrics.finishRun(runTracker, "failure");
                activeSessions.remove(activeKey);
                cancellationSignals.remove(activeKey, cancellationSignal);
                AgentTaskHandle taskHandle = taskHandleRef.get();
                String cleanupKey = activeAgentSessionKeyRef.get();
                if (taskHandle == null) {
                    activeTasks.remove(cleanupKey);
                } else {
                    activeTasks.remove(cleanupKey, taskHandle);
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

    /** 启动恢复任务复用同一条 Agent Loop，不新建重复的用户/助手消息。 */
    public void recoverRun(AgentConversationService.RecoveredRun recoveredRun) {
        String activeKey = recoveredRun == null || recoveredRun.agentSessionId() == null
            ? recoveredRun == null ? null : recoveredRun.interviewSessionId()
            : recoveredRun.agentSessionId();
        if (recoveredRun == null || !activeSessions.add(activeKey)) {
            return;
        }
        Mono.fromRunnable(() -> {
            Sinks.One<Void> cancellationSignal = Sinks.one();
            cancellationSignals.put(activeKey, cancellationSignal);
            AgentMetrics.RunTracker runTracker = agentMetrics.startRun();
            AgentTaskHandle taskHandle = new AgentTaskHandle(recoveredRun.assistantMessageId(), cancellationSignal);
            activeTasks.put(activeKey, taskHandle);
            try {
                String message = promptSanitizer.sanitize(recoveredRun.userContent());
                List<AgentHistoryMessage> historyMessages = AgentHistoryPolicy.normalize(
                    removeCurrentUser(agentConversationService.loadHistory(
                        recoveredRun.interviewSessionId(), recoveredRun.agentSessionId()
                    ))
                );
                AgentMessageRequest request = new AgentMessageRequest(
                    recoveredRun.goal() == null || recoveredRun.goal().isBlank() ? "review" : recoveredRun.goal(),
                    message, historyMessages, recoveredRun.providerId(), recoveredRun.memoryKey(),
                    recoveredRun.agentSessionId()
                );
                AgentContextService.AgentContext context;
                AuthUserContext.set(recoveredRun.ownerKey());
                try {
                    context = agentContextService.build(
                        recoveredRun.interviewSessionId(), request, historyMessages
                    );
                } finally {
                    AuthUserContext.clear();
                }
                AgentConversationService.PreparedMessage prepared = new AgentConversationService.PreparedMessage(
                    recoveredRun.assistantMessageId(), null, recoveredRun.runId(), recoveredRun.agentSessionId()
                );
                persistRunProgress(recoveredRun.assistantMessageId(), "context", "running", "应用重启后自动恢复中");
                Flux<AgentStreamEvent> workflow = buildWorkflow(
                    recoveredRun.interviewSessionId(), activeKey, request, message, historyMessages, context,
                    prepared, cancellationSignal, taskHandle, runTracker
                );
                taskHandle.subscription = workflow.subscribe(
                    ignored -> { },
                    error -> log.error("Agent 启动恢复任务失败: sessionId={}", recoveredRun.interviewSessionId(), error),
                    () -> log.info("Agent 启动恢复任务完成: sessionId={}", recoveredRun.interviewSessionId())
                );
            } catch (Throwable error) {
                failPreparedMessage(recoveredRun.assistantMessageId(), error);
                agentProgressWriter.finish(recoveredRun.assistantMessageId());
                agentMetrics.finishRun(runTracker, "failure");
                activeSessions.remove(activeKey);
                cancellationSignals.remove(activeKey, cancellationSignal);
                activeTasks.remove(activeKey, taskHandle);
                log.warn("Agent 启动恢复准备失败: sessionId={}", recoveredRun.interviewSessionId(), error);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private Flux<AgentStreamEvent> buildWorkflow(
        String sessionId,
        String agentSessionId,
        AgentMessageRequest request,
        String message,
        List<AgentHistoryMessage> historyMessages,
        AgentContextService.AgentContext context,
        AgentConversationService.PreparedMessage preparedMessage,
        Sinks.One<Void> cancellationSignal,
        AgentTaskHandle taskHandle,
        AgentMetrics.RunTracker runTracker
    ) {
        Flux<String> planFlux = agentLoopPlanner == null
            ? Flux.just(AgentLoopPlanner.DEFAULT_PLAN)
            : Mono.fromCallable(() -> agentLoopPlanner.plan(context, request, message, historyMessages))
                .subscribeOn(Schedulers.boundedElastic())
                .flux();
        return Flux.concat(
            Flux.just(
                AgentStreamEvent.step("context", "completed"),
                AgentStreamEvent.step("planning", "running")
            ),
            planFlux.flatMap(plan -> {
                    AgentLoopEngine.LoopResult loopResult = agentLoopPlanner == null
                        ? new AgentLoopEngine().execute(
                            context, List.of("读取面试报告并确认当前目标")
                        )
                        : new AgentLoopEngine().execute(context, agentLoopPlanner.steps(plan));
                    String loopTrace = loopResult.trace();
                    List<Message> history = buildModelHistory(historyMessages);
                    List<Message> compactHistory = buildModelHistory(AgentHistoryPolicy.normalize(
                        historyMessages, CONTEXT_RETRY_HISTORY_CHARS, CONTEXT_RETRY_MESSAGE_CHARS
                    ));
                    PromptVariant normalPrompt = buildPromptVariant(
                        context, request, message, history, plan, loopTrace, false
                    );
                    PromptVariant compactPrompt = buildPromptVariant(
                        context, request, message, compactHistory, plan, loopTrace, true
                    );
                    BiFunction<String, Boolean, Supplier<Flux<String>>> modelStreamFactory =
                        (providerId, compacted) -> {
                            PromptVariant prompt = compacted ? compactPrompt : normalPrompt;
                            return () -> {
                                ChatClient chatClient = llmProviderRegistry.getAgentChatClient(providerId);
                                var promptSpec = chatClient.prompt().system(prompt.systemPrompt());
                                if (!prompt.history().isEmpty()) {
                                    promptSpec = promptSpec.messages(prompt.history());
                                }
                                return promptSpec.user(prompt.userPrompt()).stream().content();
                            };
                        };
                    Flux<AgentStreamEvent> response = streamModelWithRetry(
                        modelStreamFactory, request.providerId(), sessionId,
                        preparedMessage.assistantMessageId(), context.sources(), runTracker, taskHandle
                    );
                    return Flux.concat(
                        Flux.just(
                            AgentStreamEvent.plan(plan),
                            AgentStreamEvent.step("planning", "completed"),
                            AgentStreamEvent.step("loop", "running")
                        ),
                        Flux.fromIterable(loopResult.iterations())
                            .concatMap(iteration -> Flux.just(
                                AgentStreamEvent.loop(iteration.iteration(), "running", iteration.action()),
                                AgentStreamEvent.loop(iteration.iteration(), "completed", iteration.observation())
                            )),
                        Flux.just(
                            AgentStreamEvent.step("insight", "running"),
                            AgentStreamEvent.step("retrieval", "running"),
                            AgentStreamEvent.step("retrieval", "completed"),
                            AgentStreamEvent.sources(context.sources()),
                            AgentStreamEvent.step("action", "running")
                        ),
                        response,
                        Flux.just(AgentStreamEvent.step("loop", "completed"))
                    );
                })
        )
            .doOnNext(event -> persistRunProgress(preparedMessage.assistantMessageId(), event))
            .doOnError(error -> failPreparedMessage(preparedMessage.assistantMessageId(), error))
            .takeUntilOther(Flux.merge(
                cancellationSignal.asMono(),
                monitorRemoteCancellation(sessionId, agentSessionId, preparedMessage.runId())
            ))
            .doFinally(signal -> {
                agentProgressWriter.finish(preparedMessage.assistantMessageId());
                activeSessions.remove(agentSessionId);
                cancellationSignals.remove(agentSessionId, cancellationSignal);
                activeTasks.remove(agentSessionId, taskHandle);
            });
    }

    private List<AgentHistoryMessage> removeCurrentUser(List<AgentHistoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        AgentHistoryMessage last = messages.getLast();
        if ("user".equals(last.role())) {
            return List.copyOf(messages.subList(0, messages.size() - 1));
        }
        return messages;
    }

    private AgentConversationService.PreparedMessage prepareMessage(
        String sessionId, AgentMessageRequest request, String message
    ) {
        String goal = promptSanitizer.sanitize(request.goal());
        if (request.memoryKey() == null || request.memoryKey().isBlank()) {
            // 兼容旧的嵌入式调用方和单元测试；有记忆键时才走新增重载。
            return request.agentSessionId() == null || request.agentSessionId().isBlank()
                ? agentConversationService.prepareMessage(sessionId, goal, request.providerId(), message)
                : agentConversationService.prepareMessage(
                    sessionId, request.agentSessionId(), goal, request.providerId(), message, null
                );
        }
        return agentConversationService.prepareMessage(
            sessionId, request.agentSessionId(), goal, request.providerId(), message, request.memoryKey()
        );
    }

    /** 发出服务端取消信号；如果任务不在当前实例，数据库取消仍由会话服务负责。 */
    public boolean cancel(String sessionId) {
        AgentTaskHandle taskHandle = activeTasks.get(sessionId);
        if (taskHandle != null) {
            // 规划阶段也可能尚未进入模型流，因此取消必须在这里持久化一次；
            // 会话服务会用运行状态栅栏忽略后续迟到的取消事件。
            boolean shouldPersistCancellation = taskHandle.markCancellationPersisted();
            taskHandle.cancel();
            if (shouldPersistCancellation) {
                agentConversationService.cancelMessage(taskHandle.assistantMessageId, "");
            }
            agentProgressWriter.finish(taskHandle.assistantMessageId);
            return true;
        }
        Sinks.One<Void> signal = cancellationSignals.get(sessionId);
        return signal != null && signal.tryEmitEmpty().isSuccess();
    }

    /** 恢复扫描前排除本实例仍在正常执行的任务，避免本地任务被自己换发 runId。 */
    public boolean isLocallyActive(String sessionId) {
        return sessionId != null && activeSessions.contains(sessionId);
    }

    public boolean isLocallyActive(String interviewSessionId, String agentSessionId) {
        return isLocallyActive(interviewSessionId) || isLocallyActive(agentSessionId);
    }

    private static final class AgentTaskHandle {
        private final Long assistantMessageId;
        private final Sinks.One<Void> cancellationSignal;
        private final AtomicBoolean cancellationPersisted = new AtomicBoolean();
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

        private boolean markCancellationPersisted() {
            return cancellationPersisted.compareAndSet(false, true);
        }
    }

    private void failPreparedMessage(Long messageId, Throwable error) {
        try {
            agentConversationService.failMessage(messageId, "", toAgentErrorMessage(error));
        } catch (Throwable persistenceError) {
            log.warn("Agent 任务失败状态持久化失败: messageId={}", messageId, persistenceError);
        }
    }

    /** 进度写库失败不能中断模型主流程，数据库状态仅用于断线后的展示恢复。 */
    private void persistRunProgress(Long assistantMessageId, AgentStreamEvent event) {
        if ("step".equals(event.type())) {
            agentProgressWriter.recordStep(assistantMessageId, event.step(), event.status(), event.message());
        } else if ("sources".equals(event.type())) {
            agentProgressWriter.recordSources(assistantMessageId, event.sources());
        }
    }

    private void persistRunProgress(Long assistantMessageId, String step, String status, String notice) {
        agentProgressWriter.recordStep(assistantMessageId, step, status, notice);
    }

    private Flux<Boolean> monitorRemoteCancellation(String sessionId, String agentSessionId, String runId) {
        return Flux.interval(Duration.ofSeconds(2))
            .flatMap(ignored -> Mono.fromCallable(() ->
                    agentConversationService.isRunActive(sessionId, agentSessionId, runId)
                )
                .subscribeOn(Schedulers.boundedElastic())
                // 数据库瞬时异常时继续执行，避免监测故障误杀模型任务。
                .onErrorReturn(true)
            )
            .filter(active -> !active);
    }

    private Flux<AgentStreamEvent> streamModelWithRetry(
        BiFunction<String, Boolean, Supplier<Flux<String>>> modelStreamFactory,
        String providerId,
        String sessionId,
        Long assistantMessageId,
        List<interview.guide.modules.agent.model.AgentSource> sources,
        AgentMetrics.RunTracker runTracker,
        AgentTaskHandle taskHandle
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
            output,
            false
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
            .doOnComplete(() -> {
                agentProgressWriter.flush(assistantMessageId);
                agentConversationService.completeMessage(
                    assistantMessageId,
                    output.get().toString()
                );
            })
            .doOnComplete(() -> agentMetrics.finishRun(runTracker, "success"))
            .doOnError(error -> {
                agentProgressWriter.flush(assistantMessageId);
                agentConversationService.failMessage(
                    assistantMessageId,
                    output.get().toString(),
                    toAgentErrorMessage(error)
                );
                agentMetrics.finishRun(runTracker, "failure");
            })
            .doOnCancel(() -> {
                agentProgressWriter.flush(assistantMessageId);
                if (taskHandle == null || taskHandle.markCancellationPersisted()) {
                    agentConversationService.cancelMessage(assistantMessageId, output.get().toString());
                }
                agentMetrics.finishRun(runTracker, "cancelled");
            });
    }

    private Flux<AgentStreamEvent> streamProviderAttempt(
        BiFunction<String, Boolean, Supplier<Flux<String>>> modelStreamFactory,
        List<String> providers,
        int providerIndex,
        String sessionId,
        Long assistantMessageId,
        AtomicBoolean modelOutputStarted,
        AtomicReference<StringBuilder> output,
        boolean compacted
    ) {
        String currentProvider = providers.get(providerIndex);
        String providerRole = providerIndex == 0 ? "primary" : "fallback";
        return Flux.defer(() -> streamModelAttempt(
                modelStreamFactory,
                modelStreamFactory.apply(currentProvider, compacted),
                currentProvider,
                sessionId,
                assistantMessageId,
                modelOutputStarted,
                output,
                0,
                compacted
            )).doOnComplete(() -> agentMetrics.recordProviderAttempt(providerRole, "success"))
            .doOnError(error -> agentMetrics.recordProviderAttempt(providerRole, "failure"))
            .onErrorResume(error -> {
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
                        output,
                        compacted
                    )
                );
            }
            return Flux.error(error);
        });
    }

    /**
     * 模型已接收请求但迟迟没有首个 token 时发送低频等待事件，避免反向代理把长等待
     * 误判为断线。收到首个增量、完成或失败后立即停止心跳，不会增加模型调用次数。
     */
    Flux<AgentStreamEvent> withModelHeartbeat(Flux<AgentStreamEvent> response) {
        Sinks.One<Void> stopSignal = Sinks.one();
        Duration interval = Duration.ofSeconds(retryProperties.getRetryHeartbeatIntervalSeconds());
        Flux<AgentStreamEvent> guardedResponse = response
            .doOnNext(event -> {
                if ("delta".equals(event.type()) || "done".equals(event.type())
                    || "error".equals(event.type())) {
                    stopSignal.tryEmitEmpty();
                }
            })
            .doOnError(error -> stopSignal.tryEmitEmpty())
            .doFinally(signal -> stopSignal.tryEmitEmpty());
        Flux<AgentStreamEvent> heartbeats = Flux.interval(interval)
            .takeUntilOther(stopSignal.asMono())
            .map(index -> AgentStreamEvent.waiting(
                "insight",
                "模型仍在生成回答，已等待约 " + ((index + 1) * interval.toSeconds()) + " 秒"
            ));
        return Flux.merge(guardedResponse, heartbeats);
    }

    private Flux<AgentStreamEvent> streamModelAttempt(
        BiFunction<String, Boolean, Supplier<Flux<String>>> modelStreamFactory,
        Supplier<Flux<String>> modelStream,
        String providerId,
        String sessionId,
        Long assistantMessageId,
        AtomicBoolean modelOutputStarted,
        AtomicReference<StringBuilder> output,
        int attempt,
        boolean compacted
    ) {
        Flux<AgentStreamEvent> attemptStream = Flux.defer(modelStream)
            .doOnNext(content -> {
                modelOutputStarted.set(true);
            })
            .doOnComplete(() -> llmProviderRegistry.markChatProviderSuccess(providerId))
            .map(content -> appendOutputChunk(content, output))
            // 达到展示上限后立即取消上游模型流，避免继续消耗模型额度。
            .takeUntil(ignored -> output.get().length() >= MAX_AGENT_OUTPUT_CHARS)
            .filter(content -> !content.isEmpty())
            .map(AgentStreamEvent::delta);
        return withModelHeartbeat(attemptStream)
            .onErrorResume(error -> {
                if (!modelOutputStarted.get() && isContextLimitError(error) && !compacted) {
                    agentMetrics.recordRetry("context_limit");
                    return Flux.concat(
                        Flux.just(AgentStreamEvent.waiting(
                            "insight", "本次上下文较长，正在压缩较早对话后重试"
                        )),
                        streamProviderAttempt(
                            // Context-limit fallback starts from the current provider;
                            // its caller still retains the complete candidate chain.
                            modelStreamFactory,
                            providerCandidatesFromCurrent(providerId),
                            0,
                            sessionId,
                            assistantMessageId,
                            modelOutputStarted,
                            output,
                            true
                        )
                    );
                }
                if (isProviderSwitchableError(error) && !isContextLimitError(error)) {
                    llmProviderRegistry.markChatProviderFailure(providerId);
                }
                if (!modelOutputStarted.get()
                    && isTransientModelError(error)
                    && attempt < retryProperties.getModelRetryAttempts()) {
                    agentMetrics.recordRetry("transient");
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
                                + retryProperties.getModelRetryAttempts() + " 次重试）"
                        )),
                        retryWaitingEvents(delay, retryNumber),
                        Flux.just(AgentStreamEvent.step("insight", "running")),
                        streamModelAttempt(
                            modelStreamFactory,
                            modelStreamFactory.apply(providerId, compacted),
                            providerId,
                            sessionId,
                            assistantMessageId,
                            modelOutputStarted,
                            output,
                            retryNumber,
                            compacted
                        )
                    );
                }
                return Flux.error(error);
            });
    }

    private List<String> providerCandidatesFromCurrent(String providerId) {
        List<String> candidates = llmProviderRegistry.resolveChatProviderCandidates(providerId);
        int index = candidates.indexOf(providerId);
        return index < 0 ? List.of(providerId) : List.copyOf(candidates.subList(index, candidates.size()));
    }

    /**
     * 在退避等待期间发送低频状态事件，同时严格保持原有退避总时长。
     *
     * <p>事件本身不触发重试，只有等待完整个 delay 后才会订阅下一次模型调用，
     * 因此不会因为心跳而增加模型请求次数。</p>
     */
    Flux<AgentStreamEvent> retryWaitingEvents(Duration delay, int retryNumber) {
        return retryWaitingEvents(
            delay,
            retryNumber,
            Duration.ofSeconds(retryProperties.getRetryHeartbeatIntervalSeconds())
        );
    }

    Flux<AgentStreamEvent> retryWaitingEvents(
        Duration delay,
        int retryNumber,
        Duration heartbeatInterval
    ) {
        long delayMillis = Math.max(0, delay.toMillis());
        long heartbeatMillis = Math.max(1, heartbeatInterval.toMillis());
        Duration effectiveHeartbeatInterval = Duration.ofMillis(heartbeatMillis);
        long heartbeatCount = delayMillis / heartbeatMillis;
        long remainingMillis = delayMillis - heartbeatCount * heartbeatMillis;

        Flux<AgentStreamEvent> heartbeats = heartbeatCount == 0
            ? Flux.empty()
            : Flux.interval(effectiveHeartbeatInterval)
                .take(heartbeatCount)
                .map(index -> {
                    long remainingSeconds = Math.max(
                        0, (delayMillis - (index + 1) * heartbeatMillis + 999) / 1000
                    );
                    String suffix = remainingSeconds == 0
                        ? "即将重试"
                        : "预计还需约 " + remainingSeconds + " 秒";
                    return AgentStreamEvent.waiting(
                        "insight",
                        "模型服务暂时繁忙，正在等待资源（第 " + retryNumber + "/"
                            + retryProperties.getModelRetryAttempts() + " 次重试，" + suffix + "）"
                    );
                });

        if (remainingMillis == 0) {
            return heartbeats;
        }
        return heartbeats.concatWith(
            Mono.delay(Duration.ofMillis(remainingMillis)).thenMany(Flux.empty())
        );
    }

    private Duration retryDelay(int attempt) {
        return Duration.ofSeconds(Math.min(
            retryProperties.getRetryMaxDelaySeconds(),
            2L << attempt
        ));
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

    private PromptVariant buildPromptVariant(
        AgentContextService.AgentContext context,
        AgentMessageRequest request,
        String message,
        List<Message> history,
        String plan,
        String loopTrace,
        boolean compacted
    ) {
        String report = compacted
            ? compactPromptText(context.interviewReport(), CONTEXT_RETRY_REPORT_CHARS)
            : context.interviewReport();
        String skill = compacted
            ? compactPromptText(context.skillReference(), CONTEXT_RETRY_SKILL_CHARS)
            : context.skillReference();
        String rag = compacted
            ? compactPromptText(context.ragContext(), CONTEXT_RETRY_RAG_CHARS)
            : context.ragContext();
        String memory = compacted
            ? compactPromptText(context.longTermMemory(), 2_000)
            : context.longTermMemory();
        Map<String, Object> fixedContextVariables = Map.of(
            "context", promptSanitizer.wrapWithStableDelimiters(
                "interview-report", report),
            "skill", promptSanitizer.wrapWithStableDelimiters(
                "skill-reference", skill),
            "memory", promptSanitizer.wrapWithStableDelimiters(
                "long-term-memory", memory)
        );
        Map<String, Object> variables = Map.of(
            "goal", promptSanitizer.sanitize(request.goal()),
            "question", promptSanitizer.wrapWithDelimiters("user-message", message),
            "rag", promptSanitizer.wrapWithDelimiters("rag-context", rag),
            "plan", promptSanitizer.wrapWithStableDelimiters(
                "agent-loop-plan", plan + "\n\n已完成的 Loop 观察：\n" + loopTrace
            )
        );
        String systemPrompt = systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION
            + "\n\n"
            + fixedContextPromptTemplate.render(fixedContextVariables);
        return new PromptVariant(systemPrompt, userPromptTemplate.render(variables), history);
    }

    private String compactPromptText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        String marker = "\n[较早固定资料已压缩]\n";
        int available = Math.max(0, maxChars - marker.length());
        int headChars = (available + 1) / 2;
        int tailChars = available - headChars;
        return value.substring(0, headChars)
            + marker
            + value.substring(value.length() - tailChars);
    }

    String toAgentErrorMessage(Throwable error) {
        if (isOutputTokenLimitError(error)) {
            return "当前模型 Provider 限制单次输出长度，系统会尝试切换可用模型；如果仍失败，请分段发送或切换其他模型。这不是面试消息字符限制";
        }
        if (isContextLimitError(error)) {
            return "本次上下文过长，模型无法处理；请清空部分对话、缩短问题，或分多次发送后重试";
        }
        if (isProviderAccountUnavailable(error)) {
            return "当前模型账号不可用，请在模型设置中检查 API Key、模型权限或切换其他模型";
        }
        if (isAuthenticationError(error)) {
            return "当前模型 API Key 或访问权限无效，请在模型设置中检查配置后重试";
        }
        if (isTimeoutError(error)) {
            return "模型响应超时，本次任务已结束；可以重试或切换其他模型";
        }
        if (isTransientModelError(error)) {
            return "当前模型服务繁忙，请稍后重试；也可以在模型设置中切换其他模型";
        }
        return "AI 面试教练暂时不可用，请稍后重试";
    }

    private boolean isContextLimitError(Throwable error) {
        return containsErrorMessage(error,
            "context length", "maximum context", "context window", "too many tokens",
            "prompt is too long", "input is too long", "token limit", "request entity too large", "413"
        );
    }

    private boolean isOutputTokenLimitError(Throwable error) {
        return containsErrorMessage(error,
            "max_tokens", "max completion tokens", "maximum output tokens",
            "output tokens", "must be between 0 and"
        );
    }

    private boolean isTimeoutError(Throwable error) {
        return containsErrorMessage(error, "timeout", "timed out", "deadline exceeded", "read timed out");
    }

    private boolean isAuthenticationError(Throwable error) {
        return containsErrorMessage(error,
            "unauthorized", "invalid api key", "invalid_api_key", "authentication failed", "401", "403"
        );
    }

    private boolean isTransientModelError(Throwable error) {
        return isTimeoutError(error) || containsErrorMessage(error,
            "overloaded", "temporarily unavailable", "service unavailable", "try again later",
            "concurrency limit", "rate limit", "too many requests", "429",
            "internal server error", "bad gateway", "gateway timeout", "upstream connect error",
            "connection reset", "connection refused", "500", "502", "503", "504"
        );
    }

    private boolean containsErrorMessage(Throwable error, String... fragments) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            for (String fragment : fragments) {
                if (normalized.contains(fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isProviderSwitchableError(Throwable error) {
        if (isContextLimitError(error) || isOutputTokenLimitError(error)
            || isTransientModelError(error) || isProviderAccountUnavailable(error)) {
            return true;
        }
        return containsErrorMessage(error,
            "unknown llm provider", "unauthorized", "invalid api key", "invalid_api_key",
            "authentication failed", "401", "403"
        );
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

    private record PromptVariant(String systemPrompt, String userPrompt, List<Message> history) {
    }
}
