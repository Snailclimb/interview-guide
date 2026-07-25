package interview.guide.common.ai;

import com.sun.net.httpserver.HttpServer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试：确保 base-url 末尾已带 {@code /v1}（如阿里 DashScope 的 compatible-mode）
 * 时，不会再被拼成 {@code .../v1/v1/chat/completions}（Spring AI OpenAiApi 的默认行为）。
 *
 * <p>真实场景下：UI 上点 "测试连接" 走的是 {@link interview.guide.modules.llmprovider
 * .service.LlmProviderConfigService#testProvider}（自己拼路径，所以 OK），但模拟面试
 * 调用走 {@link LlmProviderRegistry} → Spring AI {@code OpenAiApi}，之前会 404。
 */
@DisplayName("LlmProviderRegistry 真实 HTTP 路径回归")
class LlmProviderRegistryPathIntegrationTest {

  private HttpServer server;
  private final List<String> receivedPaths = new CopyOnWriteArrayList<>();
  private int port;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      receivedPaths.add(exchange.getRequestURI().getPath());
      String body = """
          {
            "id": "chatcmpl-test",
            "object": "chat.completion",
            "created": 0,
            "model": "test",
            "choices": [{
              "index": 0,
              "message": {"role": "assistant", "content": "ok"},
              "finish_reason": "stop"
            }],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
          }
          """;
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private LlmProviderRegistry buildRegistryFor(String baseUrl) {
    LlmProviderProperties properties = new LlmProviderProperties();
    ProviderConfig config = new ProviderConfig();
    config.setBaseUrl(baseUrl);
    config.setApiKey("test-key");
    config.setModel("test-model");
    Map<String, ProviderConfig> providers = new HashMap<>();
    providers.put("probe", config);
    properties.setProviders(providers);
    properties.setDefaultProvider("probe");
    ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder().build();
    return new LlmProviderRegistry(properties, toolCallingManager, null, null);
  }

  @Test
  @DisplayName("base-url 以 /v1 结尾时请求路径不应出现重复 /v1")
  void baseUrlWithV1_noDoubleVersion() {
    LlmProviderRegistry registry =
        buildRegistryFor("http://127.0.0.1:" + port + "/compatible-mode/v1");

    ChatClient client = registry.getChatClient("probe");
    client.prompt("hi").call().content();

    assertThat(receivedPaths).hasSize(1);
    assertThat(receivedPaths.get(0))
        .as("请求路径不应出现 /v1/v1")
        .isEqualTo("/compatible-mode/v1/chat/completions");
  }

  @Test
  @DisplayName("base-url 不带版本段时走默认 /v1/chat/completions")
  void baseUrlWithoutVersion_defaultPath() {
    LlmProviderRegistry registry = buildRegistryFor("http://127.0.0.1:" + port);

    ChatClient client = registry.getChatClient("probe");
    client.prompt("hi").call().content();

    assertThat(receivedPaths).hasSize(1);
    assertThat(receivedPaths.get(0)).isEqualTo("/v1/chat/completions");
  }

  @Test
  @DisplayName("Atlas Cloud 风格 /v1 base-url 不重复追加版本段")
  void atlasCloudBaseUrlWithV1_noDoubleVersion() {
    LlmProviderRegistry registry = buildRegistryFor("http://127.0.0.1:" + port + "/v1");

    ChatClient client = registry.getChatClient("probe");
    client.prompt("hi").call().content();

    assertThat(receivedPaths).hasSize(1);
    assertThat(receivedPaths.get(0)).isEqualTo("/v1/chat/completions");
  }

  @Test
  @DisplayName("base-url 以 /api/v3（豆包 Ark 风格）结尾时不重复版本段")
  void baseUrlWithV3_noDoubleVersion() {
    LlmProviderRegistry registry = buildRegistryFor("http://127.0.0.1:" + port + "/api/v3");

    ChatClient client = registry.getChatClient("probe");
    client.prompt("hi").call().content();

    assertThat(receivedPaths).hasSize(1);
    assertThat(receivedPaths.get(0)).isEqualTo("/api/v3/chat/completions");
  }
}
