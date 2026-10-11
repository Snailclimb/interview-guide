package interview.guide.common.ai;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Anonymous, opt-in web tools for tool-enabled interview clients. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.ai.parallel-search.enabled", havingValue = "true")
public class ParallelSearchConfiguration {

  static final String USER_AGENT =
      "interview-guide/0.0.1 (https://github.com/Snailclimb/interview-guide)";

  @Bean(initMethod = "initialize", destroyMethod = "closeGracefully")
  McpSyncClient parallelSearchMcpClient() {
    return createClient("https://search.parallel.ai");
  }

  static McpSyncClient createClient(String baseUri) {
    var transport = HttpClientStreamableHttpTransport.builder(baseUri)
        .endpoint("/mcp")
        .connectTimeout(Duration.ofSeconds(10))
        .requestBuilder(HttpRequest.newBuilder()
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT))
        .build();
    return McpClient.sync(transport)
        .clientInfo(McpSchema.Implementation.builder("interview-guide", "0.0.1").build())
        .requestTimeout(Duration.ofSeconds(30))
        .build();
  }

  @Bean
  ToolCallbackProvider parallelSearchToolCallbackProvider(McpSyncClient parallelSearchMcpClient) {
    return SyncMcpToolCallbackProvider.builder()
        .mcpClients(parallelSearchMcpClient)
        .toolFilter((connection, tool) ->
            "web_search".equals(tool.name()) || "web_fetch".equals(tool.name()))
        .toolNamePrefixGenerator((connection, tool) -> "parallel_" + tool.name())
        .build();
  }
}
