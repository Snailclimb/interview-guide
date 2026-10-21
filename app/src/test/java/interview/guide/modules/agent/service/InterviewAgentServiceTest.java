package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentStreamEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader
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
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader
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
            contextService, conversationService, providerRegistry, promptSanitizer, resourceLoader
        );

        reactor.core.Disposable subscription = service.stream(
            "cancel-session", new AgentMessageRequest("review", "请复盘", List.of(), "primary")
        ).subscribe();

        assertThat(service.cancel("cancel-session")).isTrue();
        verify(conversationService, timeout(1000))
            .cancelMessage(10L, "");
        subscription.dispose();
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
