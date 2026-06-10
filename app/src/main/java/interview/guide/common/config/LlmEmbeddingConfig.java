package interview.guide.common.config;

import interview.guide.common.ai.LlmProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将 {@link LlmProviderRegistry} 包装为 Spring AI 的 {@link EmbeddingModel} Bean。
 * <p>
 * 之所以不直接注入或暴露某个 Provider 的 EmbeddingModel，而是通过一个薄委托层中转，是因为：
 * <ol>
 *   <li>{@link LlmProviderRegistry} 负责在运行时决定使用哪个 Provider（按配置路由、兜底、缓存），
 *       而 Spring 的 {@code @Bean} 在启动时即固化——如果直接注入一个具体的 EmbeddingModel，
 *       切换 Provider 必须重启应用；</li>
 *   <li>所有依赖 {@code EmbeddingModel} 的组件（如向量化服务）只需注入这一个 Bean，感知不到其背后的 Provider 切换，
 *       符合依赖倒置原则。</li>
 * </ol>
 * 本 Bean 本身是无状态的纯委托，不持有 Provider 实例，所有调用最终由 Registry 按当前配置路由。
 */
@Configuration
@Slf4j
public class LlmEmbeddingConfig {

  /**
   * 注册一个委托 EmbeddingModel Bean，所有方法转发至 Registry 的默认 Provider。
   * <p>
   * 显式重写 {@code embed(Document)} 是因为 Spring AI 在某些 Provider 实现中为 {@code embed(Document)}</br>
   * 提供了独立的优化路径（如批量编码、缓存），与 {@code call(EmbeddingRequest)} 走不同内部逻辑——
   * 仅委托 {@code call()} 会导致这些优化丢失。
   */
  @Bean
  public EmbeddingModel embeddingModel(LlmProviderRegistry registry) {
    log.info("EmbeddingModel bean initialized as registry delegate");
    return new EmbeddingModel() {
      @Override
      public EmbeddingResponse call(EmbeddingRequest request) {
        return registry.getDefaultEmbeddingModel().call(request);
      }

      @Override
      public float[] embed(Document document) {
        return registry.getDefaultEmbeddingModel().embed(document);
      }
    };
  }
}
