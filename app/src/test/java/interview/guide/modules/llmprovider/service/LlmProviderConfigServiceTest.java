package interview.guide.modules.llmprovider.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LlmProviderConfigService 测试")
class LlmProviderConfigServiceTest {

    @Mock private LlmProviderProperties properties;
    @Mock private LlmProviderRegistry registry;
    @Mock private VoiceInterviewProperties voiceProperties;
    @Mock private QwenAsrService asrService;
    @Mock private QwenTtsService ttsService;

    private LlmProviderConfigService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        Path tempYaml = tempDir.resolve("application.yml");
        Path tempEnv = tempDir.resolve(".env");
        Files.writeString(tempYaml, """
            app:
              ai:
                providers: {}
            """);
        Files.writeString(tempEnv, "");

        when(properties.getConfigYamlPath()).thenReturn(tempYaml.toString());
        when(properties.getConfigEnvPath()).thenReturn(tempEnv.toString());

        service = new LlmProviderConfigService(
            properties,
            registry,
            voiceProperties,
            asrService,
            ttsService
        );
    }

    @Nested
    @DisplayName("启动校验")
    class Bootstrap {

        @Test
        @DisplayName("validateWritablePaths 对不可创建的父目录 fail-fast")
        void validateWritablePathsFailsFastWhenParentUnwritable(@TempDir Path tempDir) throws IOException {
            Path sentinel = tempDir.resolve("not-a-dir");
            Files.writeString(sentinel, "");
            Path unreachableYaml = sentinel.resolve("child/llm-providers.yml");

            when(properties.getConfigYamlPath()).thenReturn(unreachableYaml.toString());
            when(properties.getConfigEnvPath()).thenReturn(tempDir.resolve(".env").toString());

            LlmProviderConfigService failing = new LlmProviderConfigService(
                properties, registry, voiceProperties, asrService, ttsService);

            assertThrows(BusinessException.class, failing::validateWritablePaths);
        }
    }

    @Nested
    @DisplayName("基础行为")
    class BasicBehavior {

        @Test
        @DisplayName("maskApiKey 返回脱敏值")
        void maskApiKeyReturnsMaskedValue() {
            assertEquals("sk-***xyz", service.maskApiKey("sk-abcdefxyz"));
            assertEquals("***", service.maskApiKey("ab"));
            assertEquals("abc***fgh", service.maskApiKey("abcdefgh"));
        }

        @Test
        @DisplayName("listProviders 在 providers 为空时返回空列表")
        void listProvidersReturnsEmptyWhenProvidersNull() {
            when(properties.getProviders()).thenReturn(null);

            assertTrue(service.listProviders().isEmpty());
        }

        @Test
        @DisplayName("getProvider 对未知 provider 抛出异常")
        void getProviderThrowsWhenProviderMissing() {
            when(properties.getProviders()).thenReturn(new HashMap<>());

            assertThrows(BusinessException.class, () -> service.getProvider("unknown"));
        }

        @Test
        @DisplayName("GLM base-url 测试连接不应重复拼接 /v1")
        void buildConnectivityUrlsAvoidsDoubleVersionForGlm() throws Exception {
            List<String> urls = invokeConnectivityUrls("https://open.bigmodel.cn/api/coding/paas/v4");

            assertEquals(List.of("https://open.bigmodel.cn/api/coding/paas/v4/chat/completions"), urls);
        }

        @Test
        @DisplayName("Atlas Cloud base-url 测试连接不应重复拼接 /v1")
        void buildConnectivityUrlsAvoidsDoubleVersionForAtlasCloud() throws Exception {
            List<String> urls = invokeConnectivityUrls("https://api.atlascloud.ai/v1");

            assertEquals(List.of("https://api.atlascloud.ai/v1/chat/completions"), urls);
        }

        @Test
        @DisplayName("测试连接请求体不再强制携带 temperature")
        void connectivityRequestBodyOmitsTemperature() throws Exception {
            Map<String, Object> body = invokeConnectivityRequestBody("kimi-latest");

            assertEquals("kimi-latest", body.get("model"));
            assertEquals(1, body.get("max_tokens"));
            assertTrue(body.containsKey("messages"));
            assertTrue(!body.containsKey("temperature"));
        }
    }

    @Nested
    @DisplayName("Provider 管理")
    class ProviderManagement {

        @Test
        @DisplayName("createProvider 对重复 id 抛出异常")
        void createProviderThrowsForDuplicateId() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new HashMap<>();
            providers.put("existing", createProviderConfig("http://localhost:1234", "key", "model", null));
            when(properties.getProviders()).thenReturn(providers);

            CreateProviderRequest request = new CreateProviderRequest(
                "existing",
                "http://localhost:1234",
                "key",
                "model",
                null,
                null
            );

            assertThrows(BusinessException.class, () -> service.createProvider(request));
        }

        @Test
        @DisplayName("deleteProvider 删除默认 provider 时抛出异常")
        void deleteProviderThrowsForDefaultProvider() {
            when(properties.getDefaultProvider()).thenReturn("dashscope");

            assertThrows(BusinessException.class, () -> service.deleteProvider("dashscope"));
        }

        @Test
        @DisplayName("updateProvider 允许清空 embedding model")
        void updateProviderAllowsClearingEmbeddingModel() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            LlmProviderProperties.ProviderConfig config = createProviderConfig(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "secret",
                "qwen-plus",
                "text-embedding-v3"
            );
            providers.put("dashscope", config);
            when(properties.getProviders()).thenReturn(providers);

            service.updateProvider("dashscope", new UpdateProviderRequest(null, null, null, "", null));

            assertNull(config.getEmbeddingModel());
            verify(registry).reload();
        }

        @Test
        @DisplayName("updateProvider 对纯空白 embedding model 等价于清空")
        void updateProviderTreatsBlankEmbeddingModelAsClear() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            LlmProviderProperties.ProviderConfig config = createProviderConfig(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "secret",
                "qwen-plus",
                "text-embedding-v3"
            );
            providers.put("dashscope", config);
            when(properties.getProviders()).thenReturn(providers);

            service.updateProvider("dashscope", new UpdateProviderRequest(null, null, null, "   ", null));

            assertNull(config.getEmbeddingModel());
            verify(registry).reload();
        }

        @Test
        @DisplayName("updateProvider 拒绝空串 baseUrl / model / apiKey")
        void updateProviderRejectsBlankRequiredFields() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope",
                createProviderConfig("https://dashscope.aliyuncs.com", "secret", "qwen-plus", null));
            when(properties.getProviders()).thenReturn(providers);

            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest("", null, null, null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest("   ", null, null, null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest(null, null, "", null, null)));
            assertThrows(BusinessException.class, () ->
                service.updateProvider("dashscope",
                    new UpdateProviderRequest(null, "  ", null, null, null)));
        }
    }

    @Nested
    @DisplayName("全局默认 Provider")
    class DefaultProviderBehavior {

        @Test
        @DisplayName("updateDefaultProvider 拒绝未知 provider")
        void updateDefaultProviderRejectsUnknownProvider() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope", createProviderConfig("https://dashscope.aliyuncs.com", "key", "qwen", null));
            when(properties.getProviders()).thenReturn(providers);

            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.updateDefaultProvider(new DefaultProviderDTO("unknown"))
            );

            assertEquals(ErrorCode.PROVIDER_NOT_FOUND.getCode(), exception.getCode());
            verify(properties, never()).setDefaultProvider("unknown");
            verify(registry, never()).reload();
        }

        @Test
        @DisplayName("updateDefaultProvider 写入新的默认 provider")
        void updateDefaultProviderPersistsValue() {
            Map<String, LlmProviderProperties.ProviderConfig> providers = new LinkedHashMap<>();
            providers.put("dashscope", createProviderConfig("https://dashscope.aliyuncs.com", "key", "qwen", null));
            providers.put("glm", createProviderConfig("https://open.bigmodel.cn/api/coding/paas/v4", "key", "glm-4-flash", null));
            when(properties.getProviders()).thenReturn(providers);

            service.updateDefaultProvider(new DefaultProviderDTO("glm"));

            verify(properties).setDefaultProvider("glm");
            verify(registry).reload();
        }
    }

    @Nested
    @DisplayName("env 文件操作")
    class EnvFileOperations {

        private LlmProviderConfigService createServiceWithEnv(Path envFile) {
            when(properties.getConfigYamlPath()).thenReturn(null);
            when(properties.getConfigEnvPath()).thenReturn(envFile.toString());
            return new LlmProviderConfigService(
                properties, registry, voiceProperties, asrService, ttsService);
        }

        @Test
        @DisplayName("writeEnvValue 在空文件追加新键")
        void appendNewKeyToEmptyFile(@TempDir Path tempDir) throws Exception {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "");

            LlmProviderConfigService envService = createServiceWithEnv(envFile);
            invokeMethod(envService, "writeEnvValue", "PROVIDER_KIMI_API_KEY", "sk-123");

            String content = Files.readString(envFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("PROVIDER_KIMI_API_KEY=sk-123"));
        }

        @Test
        @DisplayName("writeEnvValue 在已有文件追加新键并补换行")
        void appendNewKeyToExistingFile(@TempDir Path tempDir) throws Exception {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "EXISTING_KEY=val1");

            LlmProviderConfigService envService = createServiceWithEnv(envFile);
            invokeMethod(envService, "writeEnvValue", "PROVIDER_KIMI_API_KEY", "sk-123");

            String content = Files.readString(envFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("EXISTING_KEY=val1"));
            assertTrue(content.contains("PROVIDER_KIMI_API_KEY=sk-123"));
        }

        @Test
        @DisplayName("writeEnvValue 替换已有键的值")
        void replaceExistingKey(@TempDir Path tempDir) throws Exception {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "PROVIDER_KIMI_API_KEY=old-value\nOTHER_KEY=x\n");

            LlmProviderConfigService envService = createServiceWithEnv(envFile);
            invokeMethod(envService, "writeEnvValue", "PROVIDER_KIMI_API_KEY", "new-value");

            String content = Files.readString(envFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("PROVIDER_KIMI_API_KEY=new-value"));
            assertTrue(content.contains("OTHER_KEY=x"));
        }

        @Test
        @DisplayName("envPath 为 null 时不写入文件")
        void nullEnvPathIsNoOp(@TempDir Path tempDir) throws IOException {
            when(properties.getConfigYamlPath()).thenReturn(null);
            when(properties.getConfigEnvPath()).thenReturn(null);

            LlmProviderConfigService nullEnvService = new LlmProviderConfigService(
                properties, registry, voiceProperties, asrService, ttsService);

            assertDoesNotThrow(() -> nullEnvService.createProvider(
                new CreateProviderRequest("test", "http://localhost", "key", "model", null, null)));
        }

        @Test
        @DisplayName("removeFromEnv 移除指定键")
        void removeFromEnvDeletesKey(@TempDir Path tempDir) throws Exception {
            Path envFile = tempDir.resolve(".env");
            Files.writeString(envFile, "PROVIDER_KIMI_API_KEY=sk-123\nKEEP_ME=yes\n");

            LlmProviderConfigService envService = createServiceWithEnv(envFile);
            invokeMethod(envService, "removeFromEnv", "PROVIDER_KIMI_API_KEY");

            String content = Files.readString(envFile, StandardCharsets.UTF_8);
            assertFalse(content.contains("PROVIDER_KIMI_API_KEY"));
            assertTrue(content.contains("KEEP_ME=yes"));
        }
    }

    @Nested
    @DisplayName("YAML 文件操作")
    class YamlMutations {

        @Test
        @DisplayName("mutateYaml 在文件不存在时创建新文件")
        void createsNewFileWhenMissing(@TempDir Path tempDir) throws IOException {
            Path yamlFile = tempDir.resolve("new-config.yml");
            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());
            when(properties.getConfigEnvPath()).thenReturn(null);

            LlmProviderConfigService yamlService = new LlmProviderConfigService(
                properties, registry, voiceProperties, asrService, ttsService);

            when(properties.getProviders()).thenReturn(new HashMap<>());

            yamlService.createProvider(
                new CreateProviderRequest("kimi", "https://api.moonshot.cn/v1", "key", "kimi-latest", null, null));

            assertTrue(Files.exists(yamlFile));
            String content = Files.readString(yamlFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("kimi"));
            assertTrue(content.contains("kimi-latest"));
        }

        @Test
        @DisplayName("mutateYaml 保留已有 YAML 结构")
        void preservesExistingStructure(@TempDir Path tempDir) throws IOException {
            Path yamlFile = tempDir.resolve("config.yml");
            Files.writeString(yamlFile, """
                app:
                  ai:
                    default-provider: dashscope
                  voice-interview:
                    qwen:
                      asr:
                        model: qwen3-asr-flash-realtime
                """);

            when(properties.getConfigYamlPath()).thenReturn(yamlFile.toString());
            when(properties.getConfigEnvPath()).thenReturn(null);

            LlmProviderConfigService yamlService = new LlmProviderConfigService(
                properties, registry, voiceProperties, asrService, ttsService);

            when(properties.getProviders()).thenReturn(new HashMap<>());

            yamlService.createProvider(
                new CreateProviderRequest("kimi", "https://api.moonshot.cn/v1", "key", "kimi-latest", null, null));

            String content = Files.readString(yamlFile, StandardCharsets.UTF_8);
            assertTrue(content.contains("default-provider"));
            assertTrue(content.contains("qwen3-asr-flash-realtime"));
            assertTrue(content.contains("kimi"));
        }

        @Test
        @DisplayName("yamlPath 为 null 时 mutateYaml 不抛异常")
        void nullYamlPathIsNoOp() {
            when(properties.getConfigYamlPath()).thenReturn(null);
            when(properties.getConfigEnvPath()).thenReturn(null);

            LlmProviderConfigService nullYamlService = new LlmProviderConfigService(
                properties, registry, voiceProperties, asrService, ttsService);

            assertDoesNotThrow(() -> nullYamlService.createProvider(
                new CreateProviderRequest("test", "http://localhost", "key", "model", null, null)));
        }
    }

    private LlmProviderProperties.ProviderConfig createProviderConfig(
        String baseUrl,
        String apiKey,
        String model,
        String embeddingModel
    ) {
        LlmProviderProperties.ProviderConfig config = new LlmProviderProperties.ProviderConfig();
        config.setBaseUrl(baseUrl);
        config.setApiKey(apiKey);
        config.setModel(model);
        config.setEmbeddingModel(embeddingModel);
        return config;
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeConnectivityUrls(String baseUrl)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = LlmProviderConfigService.class.getDeclaredMethod(
            "buildConnectivityTestUrls",
            String.class
        );
        method.setAccessible(true);
        return (List<String>) method.invoke(service, baseUrl);
    }

    private void invokeMethod(Object target, String methodName, Object... args)
        throws Exception {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i].getClass();
        }
        Method method = LlmProviderConfigService.class.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeConnectivityRequestBody(String model)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = LlmProviderConfigService.class.getDeclaredMethod(
            "buildConnectivityTestRequestBody",
            String.class
        );
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, model);
    }
}
