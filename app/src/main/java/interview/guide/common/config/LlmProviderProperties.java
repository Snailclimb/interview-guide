package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class LlmProviderProperties {
    private String defaultProvider = "dashscope";
    private String defaultEmbeddingProvider;
    private Integer embeddingDimensions = 1024;
    private Map<String, ProviderConfig> providers;
    private AdvisorConfig advisors = new AdvisorConfig();
    private String configYamlPath;
    private String configEnvPath;
    private SecurityConfig security = new SecurityConfig();

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
        private String embeddingModel;
        private Integer embeddingDimensions;
        private Boolean supportsEmbedding;
        private Double temperature;
    }

    @Data
    public static class SecurityConfig {
        private String apiKeyEncryptionKey;
        private boolean requireEncryptionKey = true;
        private boolean allowFallbackEncryptionKey = false;
    }

    @Data
    public static class AdvisorConfig {
        private boolean enabled = true;

        // ToolCallAdvisor
        private boolean toolCallEnabled = true;
        private boolean toolCallConversationHistoryEnabled = false;

        // MessageChatMemoryAdvisor（默认关闭，避免会话串扰）
        private boolean messageChatMemoryEnabled = false;
        private int messageChatMemoryMaxMessages = 120;

        // SimpleLoggerAdvisor（默认关闭）
        private boolean simpleLoggerEnabled = false;

        // SafeGuardAdvisor
        private boolean safeguardEnabled = true;
        private List<String> safeguardWords = List.of(
            "I'll now act as",
            "Sure, I'll ignore",
            "我已经忽略",
            "新的角色是",
            "忽略之前的指令",
            "forget all previous instructions"
        );

        // PromptSanitizer
        private boolean promptSanitizerEnabled = true;
    }
}
