package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.service.AgentAuditService;
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
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.time.Instant;

/**
 * Registry for managing and caching LLM providers.
 * Supports dynamic creation of ChatClient based on provider configurations.
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private static final Duration CHAT_PROVIDER_COOLDOWN = Duration.ofMinutes(2);
    private final Map<String, Instant> chatProviderCircuitOpenUntil = new ConcurrentHashMap<>();
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final ToolCallback interviewSkillsToolCallback;
    private final AgentMetrics agentMetrics;
    private final AgentAuditService agentAuditService;

    @Autowired
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback,
             @Autowired(required = false) AgentMetrics agentMetrics,
             @Autowired(required = false) AgentAuditService agentAuditService) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
        this.agentMetrics = agentMetrics;
        this.agentAuditService = agentAuditService;
    }

    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(properties, null, null, null, toolCallingManager, observationRegistry,
            interviewSkillsToolCallback, null, null);
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
     * 获取默认 Provider 的 Plain ChatClient，当前用于知识库查询。
     * 与 {@link #getDefaultChatClient()} 的区别在于不注册工具回调、ToolCall、Memory 或 Logger Advisor，
     * 仅按配置注册 SafeGuardAdvisor。
     */
    public ChatClient getPlainChatClient() {
        return getPlainChatClient(resolveDefaultChatProviderId());
    }

    /**
     * Get a ChatClient for the specified provider, falling back to the default if null, blank, or
     * the legacy "default" alias.
     */
    public ChatClient getChatClientOrDefault(String providerId) {
        return getChatClient(resolveProviderId(providerId));
    }

    /**
     * 获取指定 Provider 的 Plain ChatClient，当前用于文字方向题、简历题及知识库面试出题。
     * 不注册工具回调，仅按配置注册 SafeGuardAdvisor，减少工具调用对结构化输出的干扰。
     * 面试评分和简历分析当前仍使用默认类型的 ChatClient；结构化输出不会自动切换为 Plain。
     *
     * @see #getChatClientOrDefault(String)
     * @see #getDefaultChatClient()
     */
    public ChatClient getPlainChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":plain", key -> createPlainChatClient(id));
    }

    /**
     * 返回 Agent 可尝试的聊天 Provider 顺序：显式 Provider、默认 Provider、其余已启用 Provider。
     *
     * <p>这里只返回配置候选，不提前创建 ChatClient，避免一次请求为所有备用模型建立连接。
     * Agent 只有在当前模型不可用时才会按顺序懒加载下一个 Provider。</p>
     */
    public List<String> resolveChatProviderCandidates(String requestedProviderId) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String requested = resolveProviderId(requestedProviderId);
        if (!isBlank(requested) && isAvailableChatProvider(requested)) {
            candidates.add(requested);
        }
        String defaultProvider = resolveDefaultChatProviderId();
        if (!isBlank(defaultProvider) && isAvailableChatProvider(defaultProvider)) {
            candidates.add(defaultProvider);
        }

        if (providerRepository != null) {
            providerRepository.findByEnabledTrueOrderByIdAsc().stream()
                .filter(this::isAvailableChatProvider)
                .map(LlmProviderEntity::getId)
                .forEach(candidates::add);
        } else if (properties.getProviders() != null) {
            properties.getProviders().entrySet().stream()
                .filter(entry -> isAvailableChatProvider(entry.getKey(), entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .forEach(candidates::add);
        }
        return List.copyOf(candidates);
    }

    /** Agent 请求成功后清除 Provider 的短期熔断状态。 */
    public void markChatProviderSuccess(String providerId) {
        if (!isBlank(providerId)) {
            chatProviderCircuitOpenUntil.remove(providerId);
        }
    }

    /** Agent 请求失败后打开短期熔断，避免失效账号被每个请求重复等待。 */
    public void markChatProviderFailure(String providerId) {
        if (!isBlank(providerId)) {
            chatProviderCircuitOpenUntil.put(
                providerId,
                Instant.now().plus(CHAT_PROVIDER_COOLDOWN)
            );
        }
    }

    public boolean isChatProviderCircuitOpen(String providerId) {
        if (isBlank(providerId)) {
            return false;
        }
        Instant openUntil = chatProviderCircuitOpenUntil.get(providerId);
        if (openUntil == null) {
            return false;
        }
        if (openUntil.isAfter(Instant.now())) {
            return true;
        }
        chatProviderCircuitOpenUntil.remove(providerId, openUntil);
        return false;
    }

    private boolean isChatCapableProvider(String providerId) {
        if (providerRepository != null) {
            return providerRepository.findById(providerId)
                .filter(LlmProviderEntity::isEnabled)
                .map(this::isChatCapableProvider)
                .orElse(false);
        }
        if (properties.getProviders() == null) {
            return false;
        }
        return isChatCapableProvider(providerId, properties.getProviders().get(providerId));
    }

    private boolean isAvailableChatProvider(String providerId) {
        return !isChatProviderCircuitOpen(providerId) && isChatCapableProvider(providerId);
    }

    private boolean isAvailableChatProvider(String providerId, ProviderConfig config) {
        return !isChatProviderCircuitOpen(providerId) && isChatCapableProvider(providerId, config);
    }

    private boolean isAvailableChatProvider(LlmProviderEntity provider) {
        return provider != null
            && !isChatProviderCircuitOpen(provider.getId())
            && isChatCapableProvider(provider);
    }

    private boolean isChatCapableProvider(LlmProviderEntity provider) {
        if (provider == null || !hasProviderCredential(provider)) {
            return false;
        }
        return isChatCapableProvider(
            provider.getId(),
            provider.getModel(),
            provider.getEmbeddingModel(),
            provider.isSupportsEmbedding()
        );
    }

    private boolean isChatCapableProvider(String providerId, ProviderConfig config) {
        if (config == null || isBlank(config.getApiKey())) {
            return false;
        }
        return isChatCapableProvider(
            providerId,
            config.getModel(),
            config.getEmbeddingModel(),
            Boolean.TRUE.equals(config.getSupportsEmbedding())
        );
    }

    private boolean isChatCapableProvider(
        String providerId,
        String model,
        String embeddingModel,
        boolean supportsEmbedding
    ) {
        if (isBlank(providerId) || isBlank(model)) {
            return false;
        }
        // 只有 model 与 embeddingModel 相同的 Provider 才视为纯向量服务。
        return !supportsEmbedding
            || isBlank(embeddingModel)
            || !model.trim().equalsIgnoreCase(embeddingModel.trim());
    }

    /**
     * 只有已配置凭证的数据库 Provider 才能进入 Agent 候选链路。
     * API Key 解密失败按不可用处理，避免在模型请求阶段反复重试一个必然失败的 Provider。
     */
    private boolean hasProviderCredential(LlmProviderEntity provider) {
        if (encryptionService == null) {
            return false;
        }
        try {
            return !isBlank(encryptionService.decrypt(
                provider.getApiKeyNonce(), provider.getApiKeyCiphertext()
            ));
        } catch (RuntimeException error) {
            log.warn("[LlmProviderRegistry] Provider 凭证解密失败，跳过候选: {}", provider.getId());
            return false;
        }
    }

    /**
     * 获取语音面试专用 ChatClient：SkillsTool + ToolCallAdvisor（流式）。
     * 不加 Memory Advisor（语音面试手动管理对话历史）。
     */
    public ChatClient getVoiceChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":voice", key -> createVoiceChatClient(id));
    }

    /**
     * 获取 Agent 专用 ChatClient：SkillsTool + ToolCallingAdvisor，不启用会话记忆。
     * Agent 的历史由 AgentConversationService 持久化和裁剪，避免共享内存造成会话串扰。
     */
    public ChatClient getAgentChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":agent", key -> createAgentChatClient(id));
    }

    /**
     * 清空缓存，重新加载所有 provider。
     */
    public void reload() {
        int size = clientCache.size() + chatModelCache.size() + embeddingModelCache.size();
        clientCache.clear();
        chatModelCache.clear();
        embeddingModelCache.clear();
        chatProviderCircuitOpenUntil.clear();
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create clients.", size);
    }

    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(id);
        });
    }

    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId());
    }

    private ChatClient createChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
        }

        return builder.build();
    }

    private ChatClient createPlainChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
        return builder.build();
    }

    private ChatClient createVoiceChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId);
        return builder.build();
    }

    private ChatClient createAgentChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(new AgentObservedToolCallback(
                interviewSkillsToolCallback, agentMetrics, agentAuditService
            ));
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null && interviewSkillsToolCallback != null) {
            advisors.add(buildToolCallAdvisor(false));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        log.info("[LlmProviderRegistry] Created agent ChatClient (SkillsTool + ToolCall, no memory) for {}", providerId);
        return builder.build();
    }

    private OpenAiChatModel getChatModel(String providerId) {
        return chatModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new ChatModel for provider: {}", id);
            return buildChatModel(id);
        });
    }

    private OpenAiChatModel buildChatModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
                 providerId, config.baseUrl(), config.model());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .temperature(config.temperature() != null ? config.temperature() : 0.2)
                .build();

        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    private EmbeddingModel createEmbeddingModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }
        // 模型名和自定义别名不能证明能力；实际支持情况由 Embedding 接口确认。
        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
            providerId, config.baseUrl(), config.embeddingModel());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(config.embeddingModel())
            .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
            .build();

        return OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .metadataMode(MetadataMode.EMBED)
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();

        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(config.isToolCallConversationHistoryEnabled()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}", providerId);
            }
        }

        if (config.isMessageChatMemoryEnabled()) {
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

    private ToolCallingAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled) {
        return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .build();
    }

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

    private String resolveProviderId(String providerId) {
        if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
            return resolveDefaultChatProviderId();
        }
        return providerId;
    }

    private String resolveDefaultChatProviderId() {
        if (globalSettingRepository == null) {
            return properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
            .filter(id -> !isBlank(id))
            .orElse(properties.getDefaultProvider());
    }

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
            encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
            entity.getModel(),
            entity.getEmbeddingModel(),
            entity.getEmbeddingDimensions(),
            entity.isSupportsEmbedding(),
            entity.getTemperature()
        );
    }

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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

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
