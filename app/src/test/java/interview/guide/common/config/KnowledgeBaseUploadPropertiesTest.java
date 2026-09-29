package interview.guide.common.config;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.KnowledgeBaseUploadLimiter;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("上传并发配置绑定与校验")
class KnowledgeBaseUploadPropertiesTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(UploadConfiguration.class);

  @Test
  @DisplayName("未设置配置时应用使用默认四个上传名额")
  void shouldUseDefaultLimit() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(KnowledgeBaseUploadProperties.class).getMaxConcurrent())
          .isEqualTo(4);
      assertThat(context.getBean(KnowledgeBaseUploadLimiter.class).execute(() -> "uploaded"))
          .isEqualTo("uploaded");
    });
  }

  @Test
  @DisplayName("自定义并发配置实际作用于共享上传名额")
  void shouldBindConfiguredLimitToLimiter() {
    contextRunner.withPropertyValues("app.knowledge-base-upload.max-concurrent=2").run(context -> {
      assertThat(context).hasNotFailed();
      KnowledgeBaseUploadLimiter limiter = context.getBean(KnowledgeBaseUploadLimiter.class);

      limiter.execute(() -> limiter.execute(() -> {
        assertThatThrownBy(() -> limiter.execute(() -> "excess upload"))
            .isInstanceOf(BusinessException.class);
        return "uploaded";
      }));

      assertThat(limiter.execute(() -> "uploaded")).isEqualTo("uploaded");
    });
  }

  @Test
  @DisplayName("application.yml 中的环境变量占位符可覆盖默认上传上限")
  void shouldApplyEnvironmentOverrideFromApplicationYaml() throws IOException {
    var sources = new YamlPropertySourceLoader()
        .load("application", new ClassPathResource("application.yml"));
    contextRunner.withInitializer(context -> {
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
          "upload-override", Map.of("APP_KNOWLEDGE_BASE_UPLOAD_MAX_CONCURRENT", "1")));
      sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
    }).run(context -> {
      assertThat(context).hasNotFailed();
      KnowledgeBaseUploadLimiter limiter = context.getBean(KnowledgeBaseUploadLimiter.class);

      limiter.execute(() -> {
        assertThatThrownBy(() -> limiter.execute(() -> "excess upload"))
            .isInstanceOf(BusinessException.class);
        return "uploaded";
      });

      assertThat(limiter.execute(() -> "uploaded")).isEqualTo("uploaded");
    });
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("零或负数并发配置在启动时明确失败")
  void shouldRejectInvalidLimit(int maximum) {
    contextRunner.withPropertyValues("app.knowledge-base-upload.max-concurrent=" + maximum)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(BindValidationException.class);
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(KnowledgeBaseUploadProperties.class)
  @Import(KnowledgeBaseUploadLimiter.class)
  static class UploadConfiguration {
  }
}
