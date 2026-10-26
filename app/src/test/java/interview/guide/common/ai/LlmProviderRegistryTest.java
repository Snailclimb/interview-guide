package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LLM Provider Registry Test")
class LlmProviderRegistryTest {

    @Mock
    private LlmProviderProperties properties;

    @Mock
    private org.springframework.ai.model.tool.ToolCallingManager toolCallingManager;

    @Mock
    private io.micrometer.observation.ObservationRegistry observationRegistry;

    @InjectMocks
    private LlmProviderRegistry registry;

    @BeforeEach
    void setUp() {
        // No need to manually create registry when using @InjectMocks
    }

    @Test
    @DisplayName("Successfully get ChatClient for a valid provider")
    void testGetChatClient_Success() {
        // Given
        String providerId = "test-provider";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("test-model");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(providerId, config);

        when(properties.getProviders()).thenReturn(providers);

        // When
        ChatClient client = registry.getChatClient(providerId);

        // Then
        assertNotNull(client);
    }

    @Test
    @DisplayName("Verify that ChatClients are cached")
    void testGetChatClient_Caching() {
        // Given
        String providerId = "test-provider";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("test-model");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(providerId, config);

        when(properties.getProviders()).thenReturn(providers);

        // When
        ChatClient client1 = registry.getChatClient(providerId);
        ChatClient client2 = registry.getChatClient(providerId);

        // Then
        assertSame(client1, client2, "Clients should be cached and returned as the same instance");
    }

    @Test
    @DisplayName("不同 ChatClient 配方共享同一个 provider 级 ChatModel")
    void chatClientVariantsShareProviderChatModel() {
        String providerId = "test-provider";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("test-model");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(providerId, config);

        when(properties.getProviders()).thenReturn(providers);

        ChatClient defaultClient = registry.getChatClient(providerId);
        ChatClient plainClient = registry.getPlainChatClient(providerId);
        ChatClient voiceClient = registry.getVoiceChatClient(providerId);
        ChatClient agentClient = registry.getAgentChatClient(providerId);

        assertNotNull(defaultClient);
        assertNotNull(plainClient);
        assertNotNull(voiceClient);
        assertNotSame(defaultClient, plainClient, "Default and plain clients should keep advisor recipes isolated");
        assertNotSame(defaultClient, voiceClient, "Default and voice clients should keep advisor recipes isolated");
        assertNotSame(defaultClient, agentClient, "Default and agent clients should keep advisor recipes isolated");
        assertNotSame(plainClient, agentClient, "Plain and agent clients should keep tool recipes isolated");
        verify(properties, times(1)).getProviders();
    }

    @Test
    @DisplayName("Throw exception for unknown provider")
    void testGetChatClient_UnknownProvider() {
        // Given
        when(properties.getProviders()).thenReturn(new HashMap<>());

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> registry.getChatClient("unknown"));
    }

    @Test
    @DisplayName("Successfully get default ChatClient")
    void testGetDefaultChatClient() {
        // Given
        String defaultProviderId = "dashscope";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setApiKey("dashscope-key");
        config.setModel("qwen3.5-flash");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(defaultProviderId, config);

        when(properties.getDefaultProvider()).thenReturn(defaultProviderId);
        when(properties.getProviders()).thenReturn(providers);

        // When
        ChatClient client = registry.getDefaultChatClient();

        // Then
        assertNotNull(client);
    }

    @Test
    @DisplayName("reload clears cache and allows re-creation")
    void testReload() {
        String providerId = "test-provider";
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://localhost:1234/v1");
        config.setApiKey("test-key");
        config.setModel("test-model");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(providerId, config);

        when(properties.getProviders()).thenReturn(providers);

        ChatClient client1 = registry.getChatClient(providerId);
        registry.reload();
        when(properties.getProviders()).thenReturn(providers);
        ChatClient client2 = registry.getChatClient(providerId);

        assertNotNull(client1);
        assertNotNull(client2);
        assertNotSame(client1, client2, "After reload, new client should be created");
    }

    @Test
    @DisplayName("Agent 备用 Provider 排除纯 Embedding 配置并保持请求 Provider 优先")
    void resolveChatProviderCandidatesFiltersEmbeddingOnlyProviders() {
        ProviderConfig requested = new ProviderConfig();
        requested.setBaseUrl("http://requested/v1");
        requested.setApiKey("requested-key");
        requested.setModel("requested-chat");

        ProviderConfig embeddingOnly = new ProviderConfig();
        embeddingOnly.setBaseUrl("http://embedding/v1");
        embeddingOnly.setApiKey("embedding-key");
        embeddingOnly.setModel("qwen3.7-text-embedding-flash");
        embeddingOnly.setEmbeddingModel("qwen3.7-text-embedding-flash");
        embeddingOnly.setSupportsEmbedding(true);

        ProviderConfig dualPurpose = new ProviderConfig();
        dualPurpose.setBaseUrl("http://dual/v1");
        dualPurpose.setApiKey("dual-key");
        dualPurpose.setModel("qwen3.5-flash");
        dualPurpose.setEmbeddingModel("text-embedding-v3");
        dualPurpose.setSupportsEmbedding(true);

        Map<String, ProviderConfig> providers = new java.util.LinkedHashMap<>();
        providers.put("requested", requested);
        providers.put("embedding-only", embeddingOnly);
        providers.put("dual-purpose", dualPurpose);
        when(properties.getProviders()).thenReturn(providers);
        when(properties.getDefaultProvider()).thenReturn("dual-purpose");

        assertEquals(
            java.util.List.of("requested", "dual-purpose"),
            registry.resolveChatProviderCandidates("requested")
        );
    }

    @Test
    @DisplayName("Agent 备用 Provider 排除未配置 API Key 的聊天配置")
    void resolveChatProviderCandidatesFiltersProvidersWithoutCredentials() {
        ProviderConfig configured = new ProviderConfig();
        configured.setBaseUrl("http://configured/v1");
        configured.setApiKey("configured-key");
        configured.setModel("configured-chat");

        ProviderConfig missingCredential = new ProviderConfig();
        missingCredential.setBaseUrl("http://missing/v1");
        missingCredential.setModel("missing-chat");

        when(properties.getProviders()).thenReturn(java.util.Map.of(
            "configured", configured,
            "missing", missingCredential
        ));
        when(properties.getDefaultProvider()).thenReturn("configured");

        assertEquals(
            java.util.List.of("configured"),
            registry.resolveChatProviderCandidates("configured")
        );
    }

    @Test
    @DisplayName("Agent Provider 失败后进入短期熔断，成功后恢复")
    void failedChatProviderIsTemporarilySkipped() {
        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl("http://requested/v1");
        config.setApiKey("requested-key");
        config.setModel("requested-chat");
        when(properties.getProviders()).thenReturn(Map.of("requested", config));
        when(properties.getDefaultProvider()).thenReturn("requested");

        registry.markChatProviderFailure("requested");
        assertTrue(registry.isChatProviderCircuitOpen("requested"));
        assertTrue(registry.resolveChatProviderCandidates("requested").isEmpty());

        registry.markChatProviderSuccess("requested");
        assertFalse(registry.isChatProviderCircuitOpen("requested"));
        assertEquals(java.util.List.of("requested"),
            registry.resolveChatProviderCandidates("requested"));
    }

    @Nested
    @DisplayName("getChatClientOrDefault 回退逻辑")
    class GetChatClientOrDefault {

        private ProviderConfig defaultConfig;
        private ProviderConfig explicitConfig;
        private Map<String, ProviderConfig> providers;

        @BeforeEach
        void setUpProviders() {
            defaultConfig = new ProviderConfig();
            defaultConfig.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
            defaultConfig.setApiKey("default-key");
            defaultConfig.setModel("qwen3.5-flash");

            explicitConfig = new ProviderConfig();
            explicitConfig.setBaseUrl("https://api.moonshot.cn/v1");
            explicitConfig.setApiKey("kimi-key");
            explicitConfig.setModel("kimi-latest");

            providers = new HashMap<>();
            providers.put("dashscope", defaultConfig);
            providers.put("kimi", explicitConfig);

            lenient().when(properties.getDefaultProvider()).thenReturn("dashscope");
            when(properties.getProviders()).thenReturn(providers);
        }

        @Test
        @DisplayName("非 null providerId 委托给 getChatClient")
        void explicitProvider() {
            ChatClient client = registry.getChatClientOrDefault("kimi");

            assertNotNull(client);
            // Should be cached under "kimi" key, not default
            assertSame(client, registry.getChatClient("kimi"));
        }

        @Test
        @DisplayName("null providerId 回退到默认 provider")
        void nullProviderFallsBackToDefault() {
            ChatClient client = registry.getChatClientOrDefault(null);

            assertNotNull(client);
            assertSame(client, registry.getChatClient("dashscope"));
        }

        @Test
        @DisplayName("空白 providerId 回退到默认 provider")
        void blankProviderFallsBackToDefault() {
            ChatClient client = registry.getChatClientOrDefault("   ");

            assertNotNull(client);
            assertSame(client, registry.getChatClient("dashscope"));
        }

        @Test
        @DisplayName("default providerId 作为默认 provider 别名")
        void defaultAliasFallsBackToDefault() {
            ChatClient client = registry.getChatClientOrDefault("default");

            assertNotNull(client);
            assertSame(client, registry.getChatClient("dashscope"));
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled(
        "Pending: ProviderConfig.enabled flag + PROVIDER_DISABLED error code not yet implemented"
    )
    @DisplayName("getChatClient 对 disabled provider 应抛 PROVIDER_DISABLED（占位，待实现）")
    void testGetChatClient_disabledProvider() {
        // 占位测试：等 ProviderConfig 补齐 enabled 字段 + Registry 实现禁用分支后恢复断言。
        // 不写任何 mock，避免 Mockito 严格模式把占位记为异常失败。
    }
}
