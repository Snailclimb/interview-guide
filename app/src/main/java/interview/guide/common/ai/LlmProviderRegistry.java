package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Provider 注册中心，负责 ChatClient / EmbeddingModel 的创建、缓存和路由。
 *
 * 职责：
 * - 按 providerId 创建并缓存 ChatClient，避免重复创建底层 ChatModel
 * - 区分 通用/纯文本(plain)/语音(voice) 三种客户端场景，各自装配不同的 Advisor
 * - Provider 配置支持 DB 和配置文件两种来源，DB 优先
 * - 运行时支持 reload 重新加载（配置热更新）
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    // 三层缓存，按粒度拆分：ChatModel 和 EmbeddingModel 可被不同 Client 共享复用
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final ToolCallback interviewSkillsToolCallback;
    // 各厂商推荐的 Embedding 模型名映射，用于用户误配聊天模型时给出具体建议
    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
        "dashscope", "text-embedding-v3",
        "glm", "embedding-3",
        "zhipu", "embedding-3",
        "baidu", "Embedding-V1",
        "minimax", "embo-01"
    );

    @Autowired
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            // 以下三个入参依赖第三方 jar（spring-ai、spring-ai-agent-utils），
            // 用 required=false 避免它们不在类路径时启动失败
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
    }

    // 无 DB 依赖的构造器重载，供单测或无需 DB 持久化的环境使用
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(properties, null, null, null, toolCallingManager, observationRegistry, interviewSkillsToolCallback);
    }

    /**
     * Get a ChatClient for the specified provider ID.
     * If the client is not in the cache, it will be created based on the provider's configuration.
     *
     * @param providerId The ID of the provider (e.g., "dashscope", "lmstudio")
     * @return A ChatClient instance
     * @throws IllegalArgumentException if the providerId is unknown
     */
    public ChatClient getChatClient(String providerId) {
        return clientCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new client for provider: {}", id);
            return createChatClient(id);
        });
    }

    /**
     * Get the default ChatClient based on app.ai.default-provider.
     *
     * @return The default ChatClient instance
     */
    public ChatClient getDefaultChatClient() {
        return getChatClient(resolveDefaultChatProviderId());
    }

    /**
     * Get a ChatClient for the specified provider, falling back to the default if null or blank.
     */
    public ChatClient getChatClientOrDefault(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return getChatClient(providerId);
        }
        return getDefaultChatClient();
    }

    /**
     * 获取不带 SkillsTool 的 ChatClient，用于结构化输出场景（出题、简历评分等）。
     * 这些场景要求模型一次性返回可解析 JSON，不应混入工具调用消息。
     */
    public ChatClient getPlainChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        // ":plain" 后缀与通用 ChatClient 隔离，避免缓存污染
        return clientCache.computeIfAbsent(id + ":plain", key -> createPlainChatClient(id));
    }

    /**
     * 获取语音面试专用 ChatClient：SkillsTool + ToolCallAdvisor（流式）。
     * 不加 Memory Advisor（语音面试手动管理对话历史）。
     */
    public ChatClient getVoiceChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        // ":voice" 后缀与通用/plain ChatClient 隔离
        return clientCache.computeIfAbsent(id + ":voice", key -> createVoiceChatClient(id));
    }

    /**
     * 清空缓存，重新加载所有 provider。
     * 配置页修改后调用，下次请求重建 ChatClient。
     */
    public void reload() {
        int size = clientCache.size() + chatModelCache.size() + embeddingModelCache.size();
        clientCache.clear();
        chatModelCache.clear();
        embeddingModelCache.clear();
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create clients.", size);
    }

    /**
     * 获取 EmbeddingModel（用于知识库向量化）。
     */
    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(id);
        });
    }

    /**
     * 获取默认的 EmbeddingModel。
     * 优先使用 app.ai.default-embedding-provider，未配置时退化为 default-provider。
     */
    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId());
    }

    // ---- 三种 ChatClient 创建 ----

    /**
     * 创建通用 ChatClient：SkillsTool + 配置装配的 Advisor 列表。
     */
    private ChatClient createChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        // SkillsTool 可选依赖，不存在时跳过（如测试环境）
        if (interviewSkillsToolCallback != null) {
            builder.defaultToolCallbacks(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
        }

        return builder.build();
    }

    /**
     * 纯文本 ChatClient：仅保留敏感词护卫，不带工具调用和记忆。
     * 用于出题、评分等结构化输出场景——工具调用消息会干扰模型生成纯净 JSON。
     */
    private ChatClient createPlainChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(builder::defaultAdvisors);
        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
        return builder.build();
    }

    /**
     * 语音面试 ChatClient：SkillsTool + 流式 ToolCallAdvisor。
     * 不加 Memory Advisor——语音面试按对话轮次管理历史，不走 Spring AI 的窗口记忆。
     */
    private ChatClient createVoiceChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultToolCallbacks(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true, true));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
        }
        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId);
        return builder.build();
    }

    // ---- Model 创建与缓存 ----

    /**
     * 按 provider 缓存 ChatModel，避免相同 Provider 的 ChatClient 反复重建底层模型。
     */
    private OpenAiChatModel getChatModel(String providerId) {
        return chatModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new ChatModel for provider: {}", id);
            return buildChatModel(id);
        });
    }

    /**
     * 构造 OpenAiChatModel。
     * temperature 默认 0.2，偏向确定性和可复现的面试评估——过高 temperature 的输出难以评分。
     */
    private OpenAiChatModel buildChatModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
                 providerId, config.baseUrl(), config.model());

        OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(config.baseUrl(), config.apiKey());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .temperature(config.temperature() != null ? config.temperature() : 0.2)
                .build();

        return new OpenAiChatModel(
                openAiApi,
                options,
                toolCallingManager,
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                // observationRegistry 可能为 null（required=false），降级为 NOOP 避免 NPE
                observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP
        );
    }

    /**
     * 创建 EmbeddingModel（知识库向量化）。
     * 创建前校验：
     * - 未配置 Embedding 模型时抛异常
     * - 用户可能误填聊天模型名，通过 looksLikeChatModel 预先拦截并给出推荐修正
     */
    private EmbeddingModel createEmbeddingModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }
        if (looksLikeChatModel(config.embeddingModel())) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
            String suffix = recommendation != null
                ? "，推荐填写 " + recommendation
                : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
                    + config.embeddingModel() + "'" + suffix);
        }
        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl(), config.embeddingModel());

        OpenAiApi openAiApi = ApiPathResolver.buildOpenAiApi(config.baseUrl(), config.apiKey());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(config.embeddingModel())
            .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
            .build();

        return new OpenAiEmbeddingModel(
            openAiApi,
            MetadataMode.EMBED,
            options,
            RetryUtils.DEFAULT_RETRY_TEMPLATE,
            observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP
        );
    }

    // ---- Advisor 装配 ----

    /**
     * 按配置逐项装配 Advisor。
     * 工具调用 → 对话记忆 → 请求日志 → 敏感词护卫，全由 application.yml 控制开关。
     */
    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();

        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(
                    config.isToolCallConversationHistoryEnabled(),
                    config.isStreamToolCallResponses()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}", providerId);
            }
        }

        if (config.isMessageChatMemoryEnabled()) {
            // 窗口记忆最少保留 20 条，避免记忆太短对话失去上下文
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                    .maxMessages(maxMessages)
                    .build()
            ).build();
            advisors.add(memoryAdvisor);
        }

        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    /**
     * 构建 ToolCallAdvisor。
     * 配置项：
     * - toolCallingManager：工具调用管理器，由 Spring 创建
     * - conversationHistoryEnabled：是否启用对话记忆
     * - streamToolCallResponses：是否流式返回工具调用结果
     */
    private ToolCallAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled,
                                                  boolean streamToolCallResponses) {
        return ToolCallAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .streamToolCallResponses(streamToolCallResponses)
            .build();
    }

    /**
     * 敏感词护卫：拦截求职者试图让 AI 做面试无关的事情（写代码、聊天等）。
     * 提示词设计为"只能协助面试相关任务"，保持边界安全。
     */
    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
            .sensitiveWords(config.getSafeguardWords())
            .failureResponse("抱歉，我只能协助面试相关的任务。")
            .order(100)
            .build();
        return Optional.of(advisor);
    }

    // ---- Provider ID 解析（DB 优先，properties 兜底） ----

    private String resolveProviderId(String providerId) {
        return (providerId != null && !providerId.isBlank())
            ? providerId : resolveDefaultChatProviderId();
    }

    /**
     * 从 DB 查询默认聊天 Provider，查询不到时用配置文件的默认值。
     * providerRepository 为 null 说明是无 DB 环境（测试/配置驱动模式），直接返回配置默认值。
     */
    private String resolveDefaultChatProviderId() {
        if (globalSettingRepository == null) {
            return properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
            .filter(id -> !isBlank(id))
            .orElse(properties.getDefaultProvider());
    }

    /**
     * 解析默认 Embedding Provider：优先查 DB，未配置时退化为 default-provider。
     * 比聊天 Provider 多一层 fallback：先查 default-embedding-provider，再退化为 default-provider。
     */
    private String resolveDefaultEmbeddingProviderId() {
        if (globalSettingRepository == null) {
            return !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
            .filter(id -> !isBlank(id))
            .orElseGet(() -> !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()
                : properties.getDefaultProvider());
    }


    /**
     * 加载 Provider 配置：DB 有数据则走 DB（支持加密存储的 API Key），否则退化为配置文件。
     * providerRepository 为 null 时直接走配置文件路径（测试/配置驱动模式）。
     */
    private ProviderSnapshot loadProviderOrThrow(String providerId) {
        if (providerRepository == null) {
            return loadProviderFromPropertiesOrThrow(providerId);
        }
        LlmProviderEntity entity = providerRepository.findById(providerId)
            .filter(LlmProviderEntity::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("Unknown LLM provider: " + providerId));
        return new ProviderSnapshot(
            entity.getId(),
            entity.getBaseUrl(),
            // API Key 在 DB 中以 nonce + ciphertext 分存，需解密后使用
            encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.getTemperature()
        );
    }

    /**
     * 从配置文件加载 Provider 配置。
     * supportsEmbedding 自动推导：显式开启或配置了 embeddingModel 即视为支持。
     */
    private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
        ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
            || !isBlank(config.getEmbeddingModel());
        return new ProviderSnapshot(
            providerId,
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            config.getEmbeddingModel(),
            config.getEmbeddingDimensions(),
            supportsEmbedding,
            config.getTemperature()
        );
    }

    // ---- 工具方法 ----

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 解析 Embedding 维度：配置值 > 0 则用配置值，否则用全局默认 dimensions。
     */
    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    /**
     * 启发式判断用户是否误将聊天模型名填入了 Embedding 模型配置。
     * 常见聊天模型名以 glm- / deepseek / qwen 等开头，Embedding 模型名不会有这些前缀。
     */
    private boolean looksLikeChatModel(String model) {
        String lower = model.toLowerCase();
        return lower.startsWith("glm-")
            || lower.startsWith("deepseek")
            || lower.startsWith("kimi")
            || lower.startsWith("moonshot")
            || lower.startsWith("qwen")
            || lower.startsWith("ernie");
    }

    // ---- 内部数据载体 ----

    private record ProviderSnapshot(
        String id,
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel,
        Integer embeddingDimensions,
        boolean supportsEmbedding,
        Double temperature
    ) {
    }
}
