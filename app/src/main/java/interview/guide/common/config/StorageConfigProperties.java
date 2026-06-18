package interview.guide.common.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RustFS (S3兼容) 存储配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageConfigProperties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private String region = "us-east-1";
    private Duration apiCallTimeout = Duration.ofSeconds(60);
    private Duration apiCallAttemptTimeout = Duration.ofSeconds(20);
    private boolean autoCreateBucket = true;
}
