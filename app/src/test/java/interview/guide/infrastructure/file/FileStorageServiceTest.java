package interview.guide.infrastructure.file;

import interview.guide.common.config.StorageConfigProperties;
import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("文件存储服务测试")
class FileStorageServiceTest {

    @Mock
    private S3Client s3Client;

    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        StorageConfigProperties storageConfig = new StorageConfigProperties();
        storageConfig.setEndpoint("http://localhost:9000");
        storageConfig.setBucket("interview-guide");
        fileStorageService = new FileStorageService(s3Client, storageConfig);
    }

    @Test
    @DisplayName("启动检查发现桶不存在时自动创建")
    void shouldCreateBucketWhenMissingOnStartup() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(404).message("bucket not found").build());

        fileStorageService.init();

        verify(s3Client).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    @DisplayName("启动检查发现桶已存在时不重复创建")
    void shouldNotCreateBucketWhenExists() {
        fileStorageService.init();

        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    @DisplayName("启动检查遇到非404存储错误时暴露失败")
    void shouldFailFastWhenBucketCheckFails() {
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(403).message("forbidden").build());

        assertThrows(BusinessException.class, () -> fileStorageService.init());

        verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
    }
}
