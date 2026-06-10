package interview.guide.common.ai;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * OpenAI API 客户端构造器。
 * 不同 LLM Provider 的 baseUrl 结构不同：
 * - OpenAI 系：{@code https://api.openai.com/v1}  → API 路径为 /v1/chat/completions
 * - Ollama 等：{@code http://localhost:11434}      → 路径就是 /chat/completions（无版本前缀）
 * 此类自动识别 baseUrl 中是否已包含版本段，决定是否需要补全 API 路径。
 */
public final class ApiPathResolver {

  // 连接超时 10s：网络握手不应等待过久
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  // 读取超时 5min：LLM 流式响应可能持续较长时间
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);

  // 匹配 baseUrl 末尾的 /v1、/v1beta 等版本段
  private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

  private ApiPathResolver() {}

  public static OpenAiApi buildOpenAiApi(String baseUrl, String apiKey) {
    return buildOpenAiApi(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
  }

  public static OpenAiApi buildOpenAiApi(String baseUrl, String apiKey,
      Duration connectTimeout, Duration readTimeout) {
    // 自定义超时：RestClient 默认无超时，LLM 请求必须设置合理超时避免线程挂死
    // 使用 JdkClientHttpRequestFactory 替代 SimpleClientHttpRequestFactory：
    // - Java 21 内置 HttpClient 原生支持 HTTP/2 和连接池（keep-alive）
    // - 零额外依赖，项目 Java 21 + Spring Boot 4.0 天然适配
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);

    RestClient.Builder restClientBuilder = RestClient.builder()
        .requestFactory(requestFactory);

    OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .restClientBuilder(restClientBuilder);
    // baseUrl 末尾含版本段（如 /v1）时，API 路径不再拼接版本前缀
    if (baseUrlContainsVersion(baseUrl)) {
      apiBuilder.completionsPath("/chat/completions").embeddingsPath("/embeddings");
    }
    return apiBuilder.build();
  }

  /** 判断 baseUrl 末尾是否包含 /v1 之类的版本段。 */
  public static boolean baseUrlContainsVersion(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    String stripped = stripTrailingSlashes(baseUrl.trim());
    return TRAILING_VERSION.matcher(stripped).find();
  }

  /** 去除字符串末尾的斜杠，便于路径拼接和正则匹配。 */
  public static String stripTrailingSlashes(String value) {
    if (value == null) {
      return "";
    }
    String result = value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
