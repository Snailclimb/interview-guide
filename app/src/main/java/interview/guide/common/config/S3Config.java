package interview.guide.common.config;

import java.net.URI;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3客户端配置（用于RustFS）
 */
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final StorageConfigProperties storageConfig;

    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
            storageConfig.getAccessKey(),
            storageConfig.getSecretKey()
        );

        return S3Client.builder()
            .endpointOverride(URI.create(storageConfig.getEndpoint()))
            .region(Region.of(storageConfig.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .overrideConfiguration(clientOverrideConfiguration())
            // 使用路径风格访问，避免 SDK 使用 bucket.endpoint 导致 DNS 解析失败。
            .forcePathStyle(true)
            .build();
    }

    private ClientOverrideConfiguration clientOverrideConfiguration() {
        ClientOverrideConfiguration.Builder builder = ClientOverrideConfiguration.builder();
        Duration apiCallTimeout = storageConfig.getApiCallTimeout();
        Duration apiCallAttemptTimeout = storageConfig.getApiCallAttemptTimeout();
        if (apiCallTimeout != null) {
            builder.apiCallTimeout(apiCallTimeout);
        }
        if (apiCallAttemptTimeout != null) {
            builder.apiCallAttemptTimeout(apiCallAttemptTimeout);
        }
        return builder.build();
    }
}
