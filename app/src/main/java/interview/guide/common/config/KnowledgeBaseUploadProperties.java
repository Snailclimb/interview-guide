package interview.guide.common.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 知识库同步上传链路的单实例并发配置，修改后需重启应用。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.knowledge-base-upload")
public class KnowledgeBaseUploadProperties {

  @Min(1)
  private int maxConcurrent = 4;
}
