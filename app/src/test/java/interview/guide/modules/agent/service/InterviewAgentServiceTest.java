package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentStreamEvent;
import interview.guide.modules.agent.metrics.AgentMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewAgentServiceTest {

    @Mock
    private AgentContextService contextService;

    @Mock
    private AgentConversationService conversationService;

    @Mock
    private LlmProviderRegistry providerRegistry;

    @Mock
    private PromptSanitizer promptSanitizer;

    @Mock
    private AgentMetrics agentMetrics;

    @Mock
    private AgentProgressWriter agentProgressWriter;

    private final AgentRetryProperties retryProperties = new AgentRetryProperties();

    @Mock
    private ResourceLoader resourceLoader;

    @Test
    @DisplayName("首选模型账号不可用时切换备用 Provider 并完成 Agent 输出")
    void switchesToBackupProviderWhenPrimaryAccountUnavailable() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));

        AgentContextService.AgentContext context = new AgentContextService.AgentContext(
            "面试报告", "java-backend", "Skill 资料", "暂无 RAG", "问题", List.of()
        );
        when(contextService.build(anyString(), any(), any())).thenReturn(context);
        when(conversationService.loadHistory("session-1")).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary", "backup"));

        ChatClient primary = mockStreamClient(Flux.error(
            new IllegalStateException("Selected account is no longer available")
        ));
        ChatClient backup = mockStreamClient(Flux.just("备用模型回答"));
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(primary);
        when(providerRegistry.getAgentChatClient("backup")).thenReturn(backup);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.stream(
            "session-1", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).anySatisfy(event -> assertThat(event.status()).isEqualTo("waiting"));
        assertThat(events).anySatisfy(event -> assertThat(event.content()).isEqualTo("备用模型回答"));
        assertThat(events).anySatisfy(event -> assertThat(event.type()).isEqualTo("done"));
        assertThat(events).anySatisfy(event -> assertThat(event.type()).isEqualTo("loop"));
        verify(conversationService).completeMessage(10L, "备用模型回答");
    }

    @Test
    @DisplayName("Agent 默认不发送输出 token 限制")
    void omitsOutputTokenLimitByDefault() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary"));

        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponse = mock(ChatClient.StreamResponseSpec.class);
        AtomicInteger optionsCalls = new AtomicInteger();
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.options(any())).thenAnswer(invocation -> {
            optionsCalls.incrementAndGet();
            return requestSpec;
        });
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponse);
        when(streamResponse.content()).thenReturn(Flux.just("不设置输出上限的回答"));
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(client);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.stream(
            "output-limit-session", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(optionsCalls).hasValue(0);
        assertThat(events).anySatisfy(event -> assertThat(event.content()).isEqualTo("不设置输出上限的回答"));
        verify(conversationService).completeMessage(10L, "不设置输出上限的回答");
    }

    @Test
    @DisplayName("同一面试已有 Agent 任务时拒绝重复执行")
    void rejectsDuplicateActiveSession() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary"));
        ChatClient longRunningClient = mockStreamClient(Flux.never());
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(longRunningClient);
        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        AgentMessageRequest request = new AgentMessageRequest("review", "请复盘", List.of(), "primary");
        reactor.core.Disposable firstSubscription = service.stream("same-session", request).subscribe();
        List<AgentStreamEvent> second = service.stream("same-session", request).collectList().block();

        assertThat(second).isNotNull();
        assertThat(second).anySatisfy(event -> assertThat(event.message())
            .contains("已有一个 Agent 任务正在执行"));
        firstSubscription.dispose();
    }

    @Test
    @DisplayName("不同面试会话可以并行执行 Agent 任务")
    void allowsDifferentSessionsToRunConcurrently() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenAnswer(invocation -> new AgentConversationService.PreparedMessage(
                invocation.getArgument(0).equals("session-a") ? 10L : 11L,
                1L,
                invocation.getArgument(0) + "-run"
            ));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary"));
        ChatClient longRunningClient = mockStreamClient(Flux.never());
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(longRunningClient);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );
        AgentMessageRequest request = new AgentMessageRequest("review", "请复盘", List.of(), "primary");
        AtomicBoolean sessionAReceivedError = new AtomicBoolean();
        AtomicBoolean sessionBReceivedError = new AtomicBoolean();

        reactor.core.Disposable sessionASubscription = service.stream("session-a", request)
            .subscribe(event -> sessionAReceivedError.compareAndSet(false, "error".equals(event.type())));
        reactor.core.Disposable sessionBSubscription = service.stream("session-b", request)
            .subscribe(event -> sessionBReceivedError.compareAndSet(false, "error".equals(event.type())));

        assertThat(service.isLocallyActive("session-a")).isTrue();
        assertThat(service.isLocallyActive("session-b")).isTrue();
        assertThat(sessionAReceivedError).isFalse();
        assertThat(sessionBReceivedError).isFalse();

        assertThat(service.cancel("session-a")).isTrue();
        assertThat(service.cancel("session-b")).isTrue();
        sessionASubscription.dispose();
        sessionBSubscription.dispose();
    }

    @Test
    @DisplayName("取消 Agent 任务时终止模型流并持久化取消状态")
    void cancelsActiveModelStream() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary"));
        ChatClient longRunningClient = mockStreamClient(Flux.never());
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(longRunningClient);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        reactor.core.Disposable subscription = service.stream(
            "cancel-session", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).subscribe();

        assertThat(service.cancel("cancel-session")).isTrue();
        verify(conversationService, timeout(1000))
            .cancelMessage(10L, "");
        subscription.dispose();
    }

    @Test
    @DisplayName("Provider 返回 ChatResponse usage 时保留回答并上报 Token")
    void consumesChatResponseUsageStream() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary"));

        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponse = mock(ChatClient.StreamResponseSpec.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        Generation generation = mock(Generation.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponse);
        when(streamResponse.chatResponse()).thenReturn(Flux.just(chatResponse));
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(metadata.getModel()).thenReturn("gpt-5.6-luna");
        when(usage.getPromptTokens()).thenReturn(120);
        when(usage.getCompletionTokens()).thenReturn(80);
        when(usage.getTotalTokens()).thenReturn(200);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(new AssistantMessage("带 usage 的回答"));
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(client);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.stream(
            "usage-session", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events).anySatisfy(event -> assertThat(event.content()).isEqualTo("带 usage 的回答"));
        verify(agentMetrics).observeModelUsage(isNull(), eq(120), eq(80), eq(200), eq("gpt-5.6-luna"));
        verify(conversationService).completeMessage(10L, "带 usage 的回答");
    }

    @Test
    @DisplayName("模型流组装阶段异常时也关闭已创建的 Agent 任务")
    void failsPreparedMessageWhenModelStreamAssemblyFails() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenThrow(new IllegalStateException("provider registry unavailable"));

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.stream(
            "assembly-failure-session", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).anySatisfy(event -> assertThat(event.type()).isEqualTo("error"));
        verify(conversationService).failMessage(10L, "", "AI 面试教练暂时不可用，请稍后重试");
    }

    @Test
    @DisplayName("SSE 客户端断开时后台模型任务继续执行，不误标记为取消")
    void keepsBackgroundTaskAfterClientDisconnect() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary"));
        ChatClient longRunningClient = mockStreamClient(Flux.never());
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(longRunningClient);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        reactor.core.Disposable subscription = service.stream(
            "disconnect-session", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).subscribe();
        subscription.dispose();

        verify(conversationService, after(200).never()).cancelMessage(any(), anyString());
        assertThat(service.cancel("disconnect-session")).isTrue();
        verify(conversationService, timeout(1000)).cancelMessage(10L, "");
    }

    @Test
    @DisplayName("异常超长模型输出被截断并持久化安全内容")
    void capsOversizedModelOutput() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary"));
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        Flux<String> oversizedOutput = Flux.concat(
            Flux.just("x".repeat(40_000)),
            Flux.never()
        ).doOnCancel(() -> upstreamCancelled.set(true));
        ChatClient oversizedClient = mockStreamClient(oversizedOutput);
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(oversizedClient);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.stream(
            "oversized-session", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).collectList().block();

        assertThat(events).isNotNull();
        ArgumentCaptor<String> outputCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService).completeMessage(org.mockito.ArgumentMatchers.eq(10L), outputCaptor.capture());
        assertThat(outputCaptor.getValue()).hasSizeLessThanOrEqualTo(32_000);
        assertThat(outputCaptor.getValue()).contains("回答已达到展示上限");
        assertThat(upstreamCancelled).isTrue();
    }

    @Test
    @DisplayName("长重试退避期间发送心跳且不改变等待总时长")
    void emitsRetryHeartbeatWithoutExtraRetry() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.retryWaitingEvents(
            java.time.Duration.ofMillis(30), 2, java.time.Duration.ofMillis(10)
        ).collectList().block(java.time.Duration.ofSeconds(2));

        assertThat(events).hasSize(3);
        assertThat(events).allMatch(event -> "waiting".equals(event.status()));
        assertThat(events.get(0).message()).contains("预计还需约 1 秒");
        assertThat(events.get(2).message()).contains("即将重试");
    }

    @Test
    @DisplayName("模型首 token 长时间未返回时发送等待心跳")
    void emitsHeartbeatWhileWaitingForFirstModelToken() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        retryProperties.setRetryHeartbeatIntervalSeconds(1);
        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.withModelHeartbeat(
            Flux.<AgentStreamEvent>never()
        ).take(1).collectList().block(Duration.ofSeconds(3));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().status()).isEqualTo("waiting");
        assertThat(events.getFirst().message()).contains("模型仍在生成回答");
    }

    @Test
    @DisplayName("模型首次繁忙时等待后重试并完成 Agent 输出")
    void retriesTransientModelFailureBeforeFirstOutput() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary"));

        retryProperties.setModelRetryAttempts(1);
        retryProperties.setRetryMaxDelaySeconds(1);
        retryProperties.setRetryHeartbeatIntervalSeconds(1);
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> modelOutput = Flux.defer(() -> {
            if (attempts.incrementAndGet() == 1) {
                return Flux.error(new IllegalStateException("429 too many requests"));
            }
            return Flux.just("重试后回答");
        });
        ChatClient retryingClient = mockStreamClient(modelOutput);
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(retryingClient);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.stream(
            "retry-session", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(attempts).hasValue(2);
        assertThat(events).anySatisfy(event -> assertThat(event.status()).isEqualTo("waiting"));
        assertThat(events).anySatisfy(event -> assertThat(event.content()).isEqualTo("重试后回答"));
        assertThat(events).anySatisfy(event -> assertThat(event.type()).isEqualTo("done"));
        verify(conversationService).completeMessage(10L, "重试后回答");
    }

    @Test
    @DisplayName("上下文超限时压缩较早历史后重试，不直接返回不可用")
    void retriesWithCompactHistoryAfterContextLimit() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system")
                ? "系统规则"
                : path.contains("context")
                    ? "固定上下文 {context} {skill}"
                    : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary"));
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> modelOutput = Flux.defer(() -> {
            if (attempts.incrementAndGet() == 1) {
                return Flux.error(new IllegalStateException("maximum context length exceeded"));
            }
            return Flux.just("压缩后回答");
        });
        ChatClient contextLimitClient = mockStreamClient(modelOutput);
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(contextLimitClient);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.stream(
            "context-limit-session", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(attempts).hasValue(2);
        assertThat(events).anySatisfy(event -> assertThat(event.message()).contains("压缩较早对话"));
        assertThat(events).anySatisfy(event -> assertThat(event.content()).isEqualTo("压缩后回答"));
        assertThat(events).anySatisfy(event -> assertThat(event.type()).isEqualTo("done"));
        verify(conversationService).completeMessage(10L, "压缩后回答");
    }

    @Test
    @DisplayName("压缩上下文后首选模型仍失败时继续切换备用模型")
    void switchesProviderAfterCompactContextRetryFails() throws Exception {
        when(resourceLoader.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String template = path.contains("system") ? "系统规则"
                : path.contains("context") ? "固定上下文 {context} {skill}"
                : "目标 {goal}，资料 {rag}，问题 {question}";
            return new ByteArrayResource(template.getBytes(StandardCharsets.UTF_8));
        });
        when(promptSanitizer.sanitize(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(promptSanitizer.wrapWithStableDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(promptSanitizer.wrapWithDelimiters(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(contextService.build(anyString(), any(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenReturn(List.of("primary", "backup"));
        ChatClient primary = mockStreamClient(Flux.error(
            new IllegalStateException("maximum context length exceeded")
        ));
        ChatClient backup = mockStreamClient(Flux.just("备用模型完成"));
        when(providerRegistry.getAgentChatClient("primary")).thenReturn(primary);
        when(providerRegistry.getAgentChatClient("backup")).thenReturn(backup);

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics,
            agentProgressWriter, retryProperties
        );

        List<AgentStreamEvent> events = service.stream(
            "context-provider-chain", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events).anySatisfy(event -> assertThat(event.message()).contains("切换备用模型"));
        assertThat(events).anySatisfy(event -> assertThat(event.content()).isEqualTo("备用模型完成"));
        verify(conversationService).completeMessage(10L, "备用模型完成");
    }

    private ChatClient mockStreamClient(Flux<String> content) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponse = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponse);
        when(streamResponse.content()).thenReturn(content);
        return client;
    }
}
