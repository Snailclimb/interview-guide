package interview.guide.common.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Parallel MCP 通过面试客户端执行联网工具")
class ParallelSearchIntegrationTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final List<String> mcpMethods = new CopyOnWriteArrayList<>();
  private final List<String> userAgents = new CopyOnWriteArrayList<>();
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private final List<String> authorization = new CopyOnWriteArrayList<>();
  private final List<JsonNode> modelRequests = new CopyOnWriteArrayList<>();
  private final AtomicInteger completions = new AtomicInteger();
  private HttpServer server;
  private McpSyncClient mcpClient;
  private boolean requestTools;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mcp", exchange -> {
      paths.add(exchange.getRequestURI().getPath());
      userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      if (auth != null) {
        authorization.add(auth);
      }
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
        return;
      }
      JsonNode request = mapper.readTree(exchange.getRequestBody());
      String method = request.path("method").asText();
      mcpMethods.add(method);
      if (!request.has("id")) {
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
        return;
      }
      Object result = switch (method) {
        case "initialize" -> Map.of("protocolVersion", "2025-03-26",
            "capabilities", Map.of("tools", Map.of("listChanged", false)),
            "serverInfo", Map.of("name", "fixture", "version", "1"));
        case "tools/list" -> Map.of("tools", List.of(tool("web_search"), tool("web_fetch"),
            tool("unrelated_tool")));
        case "tools/call" -> Map.of("isError", false, "content", List.of(Map.of("type", "text",
            "text", request.path("params").path("name").asText().equals("web_search")
                ? "Spring AI MCP documentation https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html"
                : "Spring AI supports Streamable HTTP MCP clients and tool callbacks.")));
        default -> Map.of();
      };
      byte[] bytes = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"),
          "result", result));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.createContext("/v1/chat/completions", exchange -> {
      JsonNode request = mapper.readTree(exchange.getRequestBody());
      modelRequests.add(request);
      int index = completions.getAndIncrement();
      Map<String, Object> message;
      String finish;
      if (requestTools && index < 2) {
        String name = index == 0 ? "parallel_web_search" : "parallel_web_fetch";
        String args = index == 0 ? "{\"objective\":\"Spring AI MCP docs\",\"search_queries\":[\"Spring AI MCP client\"]}"
            : "{\"urls\":[\"https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html\"],\"objective\":\"Spring AI MCP client setup\"}";
        message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call" + index,
            "type", "function", "function", Map.of("name", name, "arguments", args))));
        finish = "tool_calls";
      }
      else {
        message = Map.of("role", "assistant", "content", "请解释 Spring AI 的 Streamable HTTP MCP 工具调用。");
        finish = "stop";
      }
      Object response;
      boolean streaming = request.path("stream").asBoolean();
      if (streaming) {
        response = Map.of("id", "fixture", "object", "chat.completion.chunk", "created", 0,
            "model", "fixture", "choices", List.of(Map.of("index", 0, "delta", message,
                "finish_reason", finish)));
      }
      else {
        response = Map.of("id", "fixture", "object", "chat.completion", "created", 0,
            "model", "fixture", "choices", List.of(Map.of("index", 0, "message", message,
                "finish_reason", finish)), "usage", Map.of("prompt_tokens", 1,
                    "completion_tokens", 1, "total_tokens", 2));
      }
      String body = mapper.writeValueAsString(response);
      if (streaming) {
        body = "data: " + body + "\n\ndata: [DONE]\n\n";
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
  }

  private Map<String, Object> tool(String name) {
    return Map.of("name", name, "description", "Public technical reference lookup",
        "inputSchema", Map.of("type", "object", "properties", Map.of()));
  }

  @AfterEach
  void close() {
    if (mcpClient != null) {
      mcpClient.closeGracefully();
    }
    server.stop(0);
  }

  private LlmProviderRegistry registry(boolean enabled) {
    ToolCallbackProvider callbacks = null;
    if (enabled) {
      mcpClient = ParallelSearchConfiguration.createClient(baseUrl());
      mcpClient.initialize();
      callbacks = new ParallelSearchConfiguration().parallelSearchToolCallbackProvider(mcpClient);
    }
    return registry(callbacks);
  }

  private LlmProviderRegistry registry(ToolCallbackProvider callbacks) {
    var properties = new LlmProviderProperties();
    var provider = new ProviderConfig();
    provider.setBaseUrl(baseUrl());
    provider.setApiKey("model-fixture-only");
    provider.setModel("fixture");
    properties.setProviders(Map.of("fixture", provider));
    properties.setDefaultProvider("fixture");
    return new LlmProviderRegistry(properties, null, null, null,
        DefaultToolCallingManager.builder().build(), null, null, callbacks);
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Test
  @DisplayName("默认关闭时不创建 MCP 客户端或发出联网请求")
  void disabledByDefault() {
    new ApplicationContextRunner().withUserConfiguration(ParallelSearchConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(McpSyncClient.class));
    registry(false).getChatClientOrDefault(null).prompt("出一道技术题").call().content();
    assertThat(mcpMethods).isEmpty();
    assertThat(modelRequests.getFirst().path("tools").isMissingNode()).isTrue();
  }

  @Test
  @DisplayName("启用后普通客户端通过模型工具循环执行搜索和提取")
  void toolLoop() {
    requestTools = true;
    String output = registry(true).getChatClientOrDefault(null)
        .prompt("根据官方文档提出一道技术题").call().content();
    verifyToolLoop(output);
  }

  @Test
  @DisplayName("语音客户端流式工具循环返回搜索资料生成的题目")
  void voiceToolLoop() {
    requestTools = true;
    String output = registry(true).getVoiceChatClient(null)
        .prompt("根据官方文档提出一道技术题").stream().content().collectList().block()
        .stream().reduce("", String::concat);
    verifyToolLoop(output);
  }

  private void verifyToolLoop(String output) {
    assertThat(output).contains("Streamable HTTP MCP");
    assertThat(mcpMethods).contains("initialize", "tools/list", "tools/call");
    assertThat(mcpMethods.stream().filter("tools/call"::equals)).hasSize(2);
    assertThat(paths).allMatch("/mcp"::equals);
    assertThat(userAgents).allMatch(ParallelSearchConfiguration.USER_AGENT::equals);
    assertThat(authorization).isEmpty();
    assertThat(modelRequests).hasSize(3);
    assertThat(modelRequests.getFirst().path("tools").toString())
        .contains("parallel_web_search", "parallel_web_fetch").doesNotContain("unrelated_tool");
    assertThat(modelRequests.toString())
        .contains("https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html", "tool",
            "Spring AI supports Streamable HTTP");
  }

  @Test
  @DisplayName("启用后 Plain 客户端仍然没有联网工具")
  void plainClientStaysPlain() {
    registry(true).getPlainChatClient().prompt("只查询知识库").call().content();
    assertThat(mcpMethods).doesNotContain("tools/call", "tools/list");
    assertThat(modelRequests.getFirst().path("tools").isMissingNode()).isTrue();
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "RUN_PARALLEL_SEARCH_LIVE", matches = "true")
  @DisplayName("匿名真实 MCP 搜索和提取通过面试客户端工具循环返回模型")
  void liveAnonymousToolLoop() throws IOException {
    mcpClient = new ParallelSearchConfiguration().parallelSearchMcpClient();
    mcpClient.initialize();
    var callbacks = new ParallelSearchConfiguration().parallelSearchToolCallbackProvider(mcpClient);
    requestTools = true;
    String output = registry(callbacks).getChatClientOrDefault(null)
        .prompt("查找 Spring AI 官方 MCP 文档，并提取相关内容").call().content();
    assertThat(output).contains("Streamable HTTP MCP");
    assertThat(modelRequests).hasSize(3);
    Path report = Path.of("build/reports/parallel-search-live.json");
    Files.createDirectories(report.getParent());
    Files.writeString(report, mapper.writeValueAsString(modelRequests));
    for (int i = 1; i < 3; i++) {
      JsonNode toolMessage = null;
      for (JsonNode entry : modelRequests.get(i).path("messages")) {
        if ("tool".equals(entry.path("role").asText())) {
          toolMessage = entry;
        }
      }
      assertThat(toolMessage).isNotNull();
      JsonNode content = mapper.readTree(toolMessage.path("content").asText());
      JsonNode result = mapper.readTree(content.get(0).path("text").asText());
      assertThat(result.path("results").isArray()).isTrue();
      assertThat(result.path("results").size()).isPositive();
      if (i == 2) {
        assertThat(result.path("errors").size()).isZero();
        assertThat(result.path("results").toString()).contains("docs.spring.io", "excerpts");
      }
    }
  }
}
