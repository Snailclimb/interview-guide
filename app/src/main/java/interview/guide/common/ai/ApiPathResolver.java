package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

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

  public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
    return buildOpenAiClient(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
  }

  public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey,
                                               Duration connectTimeout, Duration readTimeout) {
    Timeout timeout = Timeout.builder()
        .connect(connectTimeout)
        .read(readTimeout)
        .build();
    ClientOptions options = ClientOptions.Companion.builder()
        .apiKey(apiKey)
        .credential(BearerTokenCredential.create(apiKey))
        .baseUrl(resolveVersionedBaseUrl(baseUrl))
        .timeout(timeout)
        .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
        .build();
    return new OpenAIClientImpl(options);
  }

  public static String resolveVersionedBaseUrl(String baseUrl) {
    String stripped = stripTrailingSlashes(baseUrl);
    if (baseUrlContainsVersion(stripped)) {
      return stripped;
    }
    return stripped + "/v1";
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
