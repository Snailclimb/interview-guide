package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderBootstrapService {

  private static final String LEGACY_DEFAULT_PROVIDER = "dashscope";

  private final LlmProviderProperties properties;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;

  @PostConstruct
  @Transactional
  public void seedProvidersIfNecessary() {
    seedMissingProviders();
    ensureGlobalSetting();
  }

  private void seedMissingProviders() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null || providers.isEmpty()) {
      log.warn("No app.ai.providers seed configuration found");
      return;
    }

    providers.forEach((id, config) -> {
      if (isBlank(id) || config == null || isBlank(config.getBaseUrl()) || isBlank(config.getModel())) {
        log.warn("Skip invalid provider seed: id={}", id);
        return;
      }
      if (providerRepository.existsById(id)) {
        return;
      }
      ApiKeyEncryptionService.EncryptedValue encrypted =
          encryptionService.encrypt(config.getApiKey() != null ? config.getApiKey() : "");
      boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
          || !isBlank(config.getEmbeddingModel());

      LlmProviderEntity entity = LlmProviderEntity.builder()
          .id(id)
          .baseUrl(config.getBaseUrl())
          .apiKeyNonce(encrypted.nonce())
          .apiKeyCiphertext(encrypted.ciphertext())
          .model(config.getModel())
          .embeddingModel(trimOrNull(config.getEmbeddingModel()))
          .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
          .supportsEmbedding(supportsEmbedding)
          .temperature(config.getTemperature())
          .enabled(true)
          .builtin(true)
          .build();
      providerRepository.save(entity);
    });
    log.info("Seeded missing LLM providers from application configuration");
  }

  private void ensureGlobalSetting() {
    LlmGlobalSettingEntity existing = globalSettingRepository
        .findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .orElse(null);
    if (existing != null) {
      migrateLegacyDefaultSetting(existing);
      return;
    }
    String defaultChatProvider = resolveExistingProvider(
        properties.getDefaultProvider(),
        providerRepository.findAll().stream().findFirst().map(LlmProviderEntity::getId).orElse("siliconflow")
    );
    String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
        ? properties.getDefaultEmbeddingProvider()
        : defaultChatProvider;
    String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider);

    globalSettingRepository.save(LlmGlobalSettingEntity.builder()
        .id(LlmGlobalSettingEntity.SINGLETON_ID)
        .defaultChatProviderId(defaultChatProvider)
        .defaultEmbeddingProviderId(defaultEmbeddingProvider)
        .build());
    log.info("Initialized LLM global setting: chatProvider={}, embeddingProvider={}",
        defaultChatProvider, defaultEmbeddingProvider);
  }

  private void migrateLegacyDefaultSetting(LlmGlobalSettingEntity setting) {
    boolean changed = false;
    String configuredChatProvider = properties.getDefaultProvider();
    if (LEGACY_DEFAULT_PROVIDER.equals(setting.getDefaultChatProviderId())
        && !isBlank(configuredChatProvider)
        && providerRepository.existsById(configuredChatProvider)) {
      setting.setDefaultChatProviderId(configuredChatProvider);
      changed = true;
    }

    String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
        ? properties.getDefaultEmbeddingProvider()
        : setting.getDefaultChatProviderId();
    if (LEGACY_DEFAULT_PROVIDER.equals(setting.getDefaultEmbeddingProviderId())) {
      String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(
          configuredEmbeddingProvider,
          setting.getDefaultChatProviderId());
      if (!LEGACY_DEFAULT_PROVIDER.equals(defaultEmbeddingProvider)) {
        setting.setDefaultEmbeddingProviderId(defaultEmbeddingProvider);
        changed = true;
      }
    }

    if (changed) {
      globalSettingRepository.save(setting);
      log.info("Migrated legacy LLM global setting: chatProvider={}, embeddingProvider={}",
          setting.getDefaultChatProviderId(), setting.getDefaultEmbeddingProviderId());
    }
  }

  private String resolveExistingProvider(String preferredProvider, String fallbackProvider) {
    if (!isBlank(preferredProvider) && providerRepository.existsById(preferredProvider)) {
      return preferredProvider;
    }
    return fallbackProvider;
  }

  private String resolveExistingEmbeddingProvider(String preferredProvider, String fallbackProvider) {
    return providerRepository.findById(preferredProvider)
        .filter(this::canProvideEmbedding)
        .map(LlmProviderEntity::getId)
        .orElseGet(() -> providerRepository.findAll().stream()
            .filter(this::canProvideEmbedding)
            .findFirst()
            .map(LlmProviderEntity::getId)
            .orElse(fallbackProvider));
  }

  private boolean canProvideEmbedding(LlmProviderEntity provider) {
    return provider.isEnabled()
        && provider.isSupportsEmbedding()
        && !isBlank(provider.getEmbeddingModel());
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
