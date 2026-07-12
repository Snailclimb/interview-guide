package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LLM Provider 启动引导")
class LlmProviderBootstrapServiceTest {

  @Mock private LlmProviderProperties properties;
  @Mock private LlmProviderRepository providerRepository;
  @Mock private LlmGlobalSettingRepository globalSettingRepository;
  @Mock private ApiKeyEncryptionService encryptionService;

  @Test
  @DisplayName("已有旧默认配置时补种硅基流动并迁移默认 Provider")
  void seedsSiliconFlowAndMigratesLegacyDefaults() {
    when(properties.getProviders()).thenReturn(providerSeeds());
    when(properties.getDefaultProvider()).thenReturn("siliconflow");
    when(properties.getDefaultEmbeddingProvider()).thenReturn("siliconflow");
    when(encryptionService.encrypt(any()))
        .thenReturn(new ApiKeyEncryptionService.EncryptedValue("nonce", "ciphertext"));
    when(providerRepository.existsById("siliconflow")).thenReturn(false, true, true);
    when(providerRepository.existsById("dashscope")).thenReturn(true);
    when(providerRepository.findById("siliconflow")).thenReturn(Optional.of(siliconFlowEntity()));
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.of(LlmGlobalSettingEntity.builder()
            .id(LlmGlobalSettingEntity.SINGLETON_ID)
            .defaultChatProviderId("dashscope")
            .defaultEmbeddingProviderId("dashscope")
            .build()));

    service().seedProvidersIfNecessary();

    ArgumentCaptor<LlmProviderEntity> providerCaptor = ArgumentCaptor.forClass(LlmProviderEntity.class);
    verify(providerRepository).save(providerCaptor.capture());
    assertThat(providerCaptor.getValue().getId()).isEqualTo("siliconflow");

    ArgumentCaptor<LlmGlobalSettingEntity> settingCaptor =
        ArgumentCaptor.forClass(LlmGlobalSettingEntity.class);
    verify(globalSettingRepository, atLeastOnce()).save(settingCaptor.capture());
    LlmGlobalSettingEntity savedSetting = settingCaptor.getValue();
    assertThat(savedSetting.getDefaultChatProviderId()).isEqualTo("siliconflow");
    assertThat(savedSetting.getDefaultEmbeddingProviderId()).isEqualTo("siliconflow");
  }

  private LlmProviderBootstrapService service() {
    return new LlmProviderBootstrapService(
        properties,
        providerRepository,
        globalSettingRepository,
        encryptionService);
  }

  private Map<String, ProviderConfig> providerSeeds() {
    ProviderConfig siliconflow = new ProviderConfig();
    siliconflow.setBaseUrl("https://api.siliconflow.cn/v1");
    siliconflow.setApiKey("key");
    siliconflow.setModel("Pro/zai-org/GLM-4.7");
    siliconflow.setEmbeddingModel("Qwen/Qwen3-Embedding-0.6B");
    siliconflow.setEmbeddingDimensions(1024);
    siliconflow.setSupportsEmbedding(true);

    ProviderConfig dashscope = new ProviderConfig();
    dashscope.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
    dashscope.setApiKey("key");
    dashscope.setModel("qwen3.5-flash");

    Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    providers.put("siliconflow", siliconflow);
    providers.put("dashscope", dashscope);
    return providers;
  }

  private LlmProviderEntity siliconFlowEntity() {
    return LlmProviderEntity.builder()
        .id("siliconflow")
        .baseUrl("https://api.siliconflow.cn/v1")
        .apiKeyNonce("nonce")
        .apiKeyCiphertext("ciphertext")
        .model("Pro/zai-org/GLM-4.7")
        .embeddingModel("Qwen/Qwen3-Embedding-0.6B")
        .embeddingDimensions(1024)
        .supportsEmbedding(true)
        .enabled(true)
        .builtin(true)
        .build();
  }
}
