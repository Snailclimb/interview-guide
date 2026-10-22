package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentStreamEvent;
import interview.guide.modules.agent.metrics.AgentMetrics;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
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
        when(contextService.build(anyString(), any())).thenReturn(context);
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
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics
        );

        List<AgentStreamEvent> events = service.stream(
            "session-1", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).anySatisfy(event -> assertThat(event.status()).isEqualTo("waiting"));
        assertThat(events).anySatisfy(event -> assertThat(event.content()).isEqualTo("备用模型回答"));
        assertThat(events).anySatisfy(event -> assertThat(event.type()).isEqualTo("done"));
        verify(conversationService).completeMessage(10L, "备用模型回答");
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
        when(contextService.build(anyString(), any())).thenReturn(new AgentContextService.AgentContext(
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
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics
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
        when(contextService.build(anyString(), any())).thenReturn(new AgentContextService.AgentContext(
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
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics
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
        when(contextService.build(anyString(), any())).thenReturn(new AgentContextService.AgentContext(
            "报告", "skill", "skill", "无资料", "问题", List.of()
        ));
        when(conversationService.loadHistory(anyString())).thenReturn(List.of());
        when(conversationService.prepareMessage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new AgentConversationService.PreparedMessage(10L, 1L, "run-1"));
        when(providerRegistry.resolveChatProviderCandidates("primary"))
            .thenThrow(new IllegalStateException("provider registry unavailable"));

        InterviewAgentService service = new InterviewAgentService(
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics
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
        when(contextService.build(anyString(), any())).thenReturn(new AgentContextService.AgentContext(
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
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics
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
        when(contextService.build(anyString(), any())).thenReturn(new AgentContextService.AgentContext(
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
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics
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
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader, agentMetrics
        );

        List<AgentStreamEvent> events = service.retryWaitingEvents(
            java.time.Duration.ofMillis(30), 2, java.time.Duration.ofMillis(10)
        ).collectList().block(java.time.Duration.ofSeconds(2));

        assertThat(events).hasSize(3);
        assertThat(events).allMatch(event -> "waiting".equals(event.status()));
        assertThat(events.get(0).message()).contains("预计还需约 1 秒");
        assertThat(events.get(2).message()).contains("即将重试");
    }

    private ChatClient mockStreamClient(Flux<String> content) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponse = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponse);
        when(streamResponse.content()).thenReturn(content);
        return client;
    }
}
