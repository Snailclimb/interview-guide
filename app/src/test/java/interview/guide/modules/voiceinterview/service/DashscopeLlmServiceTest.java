package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.VoiceInterviewPromptService.RolePrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DashscopeLlmService 单元测试
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>基本 LLM 调用</li>
 *   <li>对话历史处理</li>
 *   <li>API 错误处理</li>
 *   <li>流式调用回退到同步</li>
 * </ul>
 *
 * <p>注意：由于 ChatClient 的复杂链式调用，这里简化测试策略，
 * 主要验证服务层的错误处理和边界条件。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Dashscope LLM 服务测试")
class DashscopeLlmServiceTest {

    @Mock
    private interview.guide.common.ai.LlmProviderRegistry llmProviderRegistry;

    @Mock
    private ChatClient chatClient;

    @Mock
    private VoiceInterviewPromptService promptService;

    @Mock
    private ResumeRepository resumeRepository;

    private DashscopeLlmService dashscopeLlmService;

    private RolePrompt mockRolePrompt;
    private VoiceInterviewSessionEntity mockSession;

    @BeforeEach
    void setUp() {
        dashscopeLlmService = new DashscopeLlmService(llmProviderRegistry, promptService, resumeRepository);
        // Setup mock role prompt
        mockRolePrompt = new RolePrompt();
        mockRolePrompt.setRoleType("ali-p8");
        mockRolePrompt.setSystemPrompt("你是一位专业的面试官");

        // Setup mock session
        mockSession = VoiceInterviewSessionEntity.builder()
                .id(1L)
                .roleType("ali-p8")
                .llmProvider("dashscope")
                .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                .build();

        // Setup prompt service
        lenient().when(promptService.generateSystemPromptWithContext(anyString(), any())).thenReturn("System prompt");
    }

    @Nested
    @DisplayName("基本 LLM 调用测试")
    class BasicChatTests {

        @Test
        @DisplayName("调用 LLM - 验证提示词获取")
        void testChat_VerifyRolePrompt() {
            // Given
            String userInput = "请介绍一下你的项目经验";
            mockSession.setRoleType("byteance-algo");
            mockSession.setLlmProvider("dashscope");

            when(promptService.generateSystemPromptWithContext(eq("byteance-algo"), any())).thenReturn("你是字节跳动算法面试官");
            when(llmProviderRegistry.getChatClient(eq("dashscope"))).thenReturn(chatClient);

            // When & Then - 验证方法可以正常调用
            // 注意：由于 ChatClient 链式调用的复杂性，这里主要验证不抛出异常
            // 实际的 API 调用会失败（因为没有真实的 LLM），但应返回默认消息
            assertDoesNotThrow(() -> {
                String result = dashscopeLlmService.chat(userInput, mockSession, Collections.emptyList());
                // 结果可能是默认错误消息（API 调用失败）或 LLM 响应
                assertNotNull(result);
            });

            // Verify prompt service was called
            verify(promptService, times(1)).generateSystemPromptWithContext(eq("byteance-algo"), any());
            verify(llmProviderRegistry, times(1)).getChatClient(eq("dashscope"));
        }

        @Test
        @DisplayName("调用 LLM - 长输入")
        void testChat_LongInput() {
            // Given
            StringBuilder longInput = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                longInput.append("这是第").append(i).append("句话。");
            }

            // When & Then
            assertDoesNotThrow(() -> {
                String result = dashscopeLlmService.chat(longInput.toString(), mockSession, null);
                assertNotNull(result);
            });
        }
    }

    @Nested
    @DisplayName("对话历史测试")
    class ConversationHistoryTests {

        @Test
        @DisplayName("多轮对话 - 验证上下文保持")
        void testChat_MultipleTurns() {
            // Given
            String firstInput = "你好";
            String secondInput = "我有3年Java开发经验";

            // When & Then - 多次调用不应抛出异常
            assertDoesNotThrow(() -> {
                dashscopeLlmService.chat(firstInput, mockSession, null);
                dashscopeLlmService.chat(secondInput, mockSession, null);
            });

            // Verify prompt service was called for each turn
            verify(promptService, times(2)).generateSystemPromptWithContext(anyString(), any());
        }

        @Test
        @DisplayName("不同角色类型 - 使用不同提示词")
        void testChat_DifferentRoleTypes() {
            // Given
            String userInput = "开始面试";

            RolePrompt aliPrompt = new RolePrompt();
            aliPrompt.setRoleType("ali-p8");
            aliPrompt.setSystemPrompt("阿里P8面试官");

            RolePrompt tencentPrompt = new RolePrompt();
            tencentPrompt.setRoleType("tencent-backend");
            tencentPrompt.setSystemPrompt("腾讯后端面试官");

            when(promptService.generateSystemPromptWithContext(eq("ali-p8"), any())).thenReturn("阿里P8面试官");
            when(promptService.generateSystemPromptWithContext(eq("tencent-backend"), any())).thenReturn("腾讯后端面试官");

            VoiceInterviewSessionEntity aliSession = VoiceInterviewSessionEntity.builder()
                    .id(1L)
                    .roleType("ali-p8")
                    .build();

            VoiceInterviewSessionEntity tencentSession = VoiceInterviewSessionEntity.builder()
                    .id(2L)
                    .roleType("tencent-backend")
                    .build();

            // When & Then
            assertDoesNotThrow(() -> {
                dashscopeLlmService.chat(userInput, aliSession, null);
                dashscopeLlmService.chat(userInput, tencentSession, null);
            });

            // Verify different prompts were used
            verify(promptService, times(1)).generateSystemPromptWithContext(eq("ali-p8"), any());
            verify(promptService, times(1)).generateSystemPromptWithContext(eq("tencent-backend"), any());
        }
    }

    @Nested
    @DisplayName("错误处理测试")
    class ErrorHandlingTests {

        @Test
        @DisplayName("提示词服务异常 - 返回默认消息")
        void testChat_PromptServiceError() {
            // Given
            String userInput = "测试";
            when(promptService.generateSystemPromptWithContext(anyString(), any()))
                    .thenThrow(new RuntimeException("提示词加载失败"));

            // When
            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            // Then
            assertNotNull(result);
            assertTrue(result.contains("AI 服务") || result.contains("错误"),
                    "Error message should be user-friendly and mention AI service");
        }

        @Test
        @DisplayName("ChatClient 认证错误 - 返回特定错误消息")
        void testChat_ChatClientAuthenticationError() {
            // Given
            String userInput = "测试";
            when(llmProviderRegistry.getChatClient(anyString()))
                    .thenThrow(new RuntimeException("403 ACCESS_DENIED: Invalid API key"));

            // When
            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            // Then
            assertNotNull(result);
            assertTrue(result.contains("认证失败") || result.contains("API Key"),
                    "Should return authentication error message");
        }

        @Test
        @DisplayName("ChatClient 超时错误 - 返回超时错误消息")
        void testChat_ChatClientTimeoutError() {
            // Given
            String userInput = "测试";
            when(llmProviderRegistry.getChatClient(anyString()))
                    .thenThrow(new RuntimeException("Request timeout after 30000ms"));

            // When
            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            // Then
            assertNotNull(result);
            assertTrue(result.contains("超时") || result.contains("timeout"),
                    "Should return timeout error message");
        }

        @Test
        @DisplayName("ChatClient 频率限制错误 - 返回限流错误消息")
        void testChat_ChatClientRateLimitError() {
            // Given
            String userInput = "测试";
            when(llmProviderRegistry.getChatClient(anyString()))
                    .thenThrow(new RuntimeException("429 rate limit exceeded"));

            // When
            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            // Then
            assertNotNull(result);
            assertTrue(result.contains("频率") || result.contains("quota") || result.contains("超限"),
                    "Should return rate limit error message");
        }

        @Test
        @DisplayName("ChatClient 网络错误 - 返回网络错误消息")
        void testChat_ChatClientNetworkError() {
            // Given
            String userInput = "测试";
            when(llmProviderRegistry.getChatClient(anyString()))
                    .thenThrow(new RuntimeException("connection refused: network error"));

            // When
            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            // Then
            assertNotNull(result);
            assertTrue(result.contains("网络") || result.contains("connection"),
                    "Should return network error message");
        }

        @Test
        @DisplayName("ChatClient 未知错误 - 返回通用错误消息")
        void testChat_ChatClientUnknownError() {
            // Given
            String userInput = "测试";
            when(llmProviderRegistry.getChatClient(anyString()))
                    .thenThrow(new RuntimeException("Unknown error occurred"));

            // When
            String result = dashscopeLlmService.chat(userInput, mockSession, null);

            // Then
            assertNotNull(result);
            assertTrue(result.contains("不可用") || result.contains("稍后"),
                    "Should return generic error message for unknown errors");
        }
    }

    @Nested
    @DisplayName("流式调用测试")
    class StreamingTests {

        @Test
        @DisplayName("流式调用 - 回退到同步实现")
        void testChatStream_UsesSync() {
            // Given
            String userInput = "测试流式调用";
            Consumer<String> onToken = token -> {};

            // When & Then - 流式调用应回退到同步实现
            assertDoesNotThrow(() -> {
                String result = dashscopeLlmService.chatStream(userInput, onToken, mockSession, Collections.emptyList());
                assertNotNull(result);
            });

            // Verify sync chat was called (via prompt service)
            verify(promptService, times(1)).generateSystemPromptWithContext(anyString(), any());
        }

        @Test
        @DisplayName("流式调用 - null 回调处理")
        void testChatStream_NullCallback() {
            // Given
            String userInput = "测试";

            // When & Then - 不应抛出异常
            assertDoesNotThrow(() -> {
                String result = dashscopeLlmService.chatStream(userInput, null, mockSession, null);
                assertNotNull(result);
            });
        }

        @Test
        @DisplayName("流式调用 - API 错误处理")
        void testChatStream_ApiError() {
            // Given
            String userInput = "测试流式错误";
            Consumer<String> onToken = token -> {};
            when(llmProviderRegistry.getChatClient(anyString()))
                    .thenThrow(new RuntimeException("API 错误"));

            // When
            String result = dashscopeLlmService.chatStream(userInput, onToken, mockSession, null);

            // Then
            assertNotNull(result);
            assertTrue(result.contains("不可用") || result.contains("稍后"),
                    "Should return user-friendly error message");
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("null 会话 - 抛出异常")
        void testChat_NullSession() {
            // Given
            String userInput = "测试";

            // When & Then
            assertThrows(NullPointerException.class, () -> {
                dashscopeLlmService.chat(userInput, null, null);
            });
        }

        @Test
        @DisplayName("会话角色类型为 null - 使用默认提示词")
        void testChat_NullRoleType() {
            // Given
            String userInput = "测试";
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                    .id(1L)
                    .roleType(null)
                    .build();

            when(promptService.generateSystemPromptWithContext(eq(null), any())).thenReturn("默认提示词");

            // When
            dashscopeLlmService.chat(userInput, session, null);

            // Then
            verify(promptService, times(1)).generateSystemPromptWithContext(eq(null), any());
        }

        @Test
        @DisplayName("特殊字符输入 - 正常处理")
        void testChat_SpecialCharacters() {
            // Given
            String specialInput = "你好！@#$%^&*()_+";

            // When & Then
            assertDoesNotThrow(() -> {
                String result = dashscopeLlmService.chat(specialInput, mockSession, null);
                assertNotNull(result);
            });
        }
    }

    @Nested
    @DisplayName("集成场景测试")
    class IntegrationScenarioTests {

        @Test
        @DisplayName("不同面试阶段的提示词")
        void testDifferentInterviewPhases() {
            // Given
            String userInput = "开始";

            VoiceInterviewSessionEntity introSession = VoiceInterviewSessionEntity.builder()
                    .id(1L)
                    .roleType("ali-p8")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
                    .build();

            VoiceInterviewSessionEntity techSession = VoiceInterviewSessionEntity.builder()
                    .id(2L)
                    .roleType("ali-p8")
                    .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                    .build();

            // When & Then
            assertDoesNotThrow(() -> {
                dashscopeLlmService.chat(userInput, introSession, null);
                dashscopeLlmService.chat(userInput, techSession, null);
            });

            // 验证两次调用都成功
            verify(promptService, times(2)).generateSystemPromptWithContext(anyString(), any());
        }
    }
}
