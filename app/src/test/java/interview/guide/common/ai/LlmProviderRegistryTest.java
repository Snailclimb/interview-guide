package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
        config.setModel("qwen-plus");

        Map<String, ProviderConfig> providers = new HashMap<>();
        providers.put(defaultProviderId, config);

        when(properties.getDefaultProvider()).thenReturn(defaultProviderId);
        when(properties.getProviders()).thenReturn(providers);

        // When
        ChatClient client = registry.getDefaultChatClient();

        // Then
        assertNotNull(client);
    }
}
