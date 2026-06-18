package interview.guide.infrastructure.file;

import interview.guide.common.config.StorageConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件存储服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final S3Client s3Client;
    private final StorageConfigProperties storageConfig;

    @PostConstruct
    public void init() {
        if (!storageConfig.isAutoCreateBucket()) {
            log.info("存储桶启动检查已关闭: bucket={}", storageConfig.getBucket());
            return;
        }
        ensureBucketExists();
    }

    /**
     * 上传简历文件
     */
    public String uploadResume(MultipartFile file) {
        return uploadFile(file, "resumes");
    }

    /**
     * 删除简历文件
     */
    public void deleteResume(String fileKey) {
        deleteFile(fileKey);
    }

    /**
     * 上传知识库文件
     */
    public String uploadKnowledgeBase(MultipartFile file) {
        return uploadFile(file, "knowledgebases");
    }

    /**
     * 删除知识库文件
     */
    public void deleteKnowledgeBase(String fileKey) {
        deleteFile(fileKey);
    }

    /**
     * 下载文件（通用方法）
     *
     * @param fileKey 文件存储键
     * @return 文件字节数组
     */
    public byte[] downloadFile(String fileKey) {
        if (!fileExists(fileKey)) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在: " + fileKey);
        }

        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            return s3Client.getObjectAsBytes(getRequest).asByteArray();
        } catch (S3Exception e) {
            log.error("下载文件失败: {} - {}", fileKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件下载失败: " + e.getMessage());
        }
    }

    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 通用文件上传方法
     */
    private String uploadFile(MultipartFile file, String prefix) {
        String originalFilename = file.getOriginalFilename();
        String fileKey = generateFileKey(originalFilename, prefix);

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("文件上传成功: {} -> {}", originalFilename, fileKey);
            return fileKey;
        } catch (IOException e) {
            log.error("读取上传文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件读取失败");
        } catch (S3Exception e) {
            log.error("上传文件到RustFS失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件存储失败: " + e.getMessage());
        }
    }

    /**
     * 检查文件是否存在
     */
    public boolean fileExists(String fileKey) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            log.warn("检查文件存在性失败: {} - {}", fileKey, e.getMessage());
            return false;
        }
    }

    /**
     * 获取文件大小（字节）
     */
    public long getFileSize(String fileKey) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            return s3Client.headObject(headRequest).contentLength();
        } catch (S3Exception e) {
            log.error("获取文件大小失败: {} - {}", fileKey, e.getMessage());
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "获取文件信息失败");
        }
    }

    /**
     * 通用文件删除方法
     */
    private void deleteFile(String fileKey) {
        // 空键直接跳过
        if (fileKey == null || fileKey.isEmpty()) {
            log.debug("文件键为空，跳过删除");
            return;
        }

        // 检查文件是否存在，避免不必要的删除操作
        if (!fileExists(fileKey)) {
            log.warn("文件不存在，跳过删除: {}", fileKey);
            return;
        }

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .key(fileKey)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("文件删除成功: {}", fileKey);
        } catch (S3Exception e) {
            log.error("删除文件失败: {} - {}", fileKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "文件删除失败: " + e.getMessage());
        }
    }

    public String getFileUrl(String fileKey) {
        return String.format("%s/%s/%s", storageConfig.getEndpoint(), storageConfig.getBucket(), fileKey);
    }

    /**
     * 确保存储桶存在
     */
    public void ensureBucketExists() {
        try {
            HeadBucketRequest headRequest = HeadBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            s3Client.headBucket(headRequest);
            log.info("存储桶已存在: {}", storageConfig.getBucket());
        } catch (NoSuchBucketException e) {
            createBucket();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket();
                return;
            }
            log.error("检查存储桶失败: bucket={}, error={}", storageConfig.getBucket(), e.getMessage(), e);
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    "检查存储桶失败: " + e.getMessage(),
                    e
            );
        }
    }

    private void createBucket() {
        try {
            log.info("存储桶不存在，正在创建: {}", storageConfig.getBucket());
            CreateBucketRequest createRequest = CreateBucketRequest.builder()
                    .bucket(storageConfig.getBucket())
                    .build();
            s3Client.createBucket(createRequest);
            log.info("存储桶创建成功: {}", storageConfig.getBucket());
        } catch (S3Exception e) {
            if (e.statusCode() == 409) {
                log.info("存储桶已由其他进程创建: {}", storageConfig.getBucket());
                return;
            }
            log.error("创建存储桶失败: bucket={}, error={}", storageConfig.getBucket(), e.getMessage(), e);
            throw new BusinessException(
                    ErrorCode.STORAGE_UPLOAD_FAILED,
                    "创建存储桶失败: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * 生成文件键
     */
    private String generateFileKey(String originalFilename, String prefix) {
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DATE_PATH_FORMAT);
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String safeName = sanitizeFilename(originalFilename);
        return String.format("%s/%s/%s_%s", prefix, datePath, uuid, safeName);
    }

    /**
     * 清理文件名，移除不安全的字符
     * <p>
     * 汉字转换为大驼峰拼音，保留字母、数字、点号、下划线和连字符，
     * 其他字符统一替换为下划线，防止 S3 存储出现问题。
     *
     * @param filename 原始文件名
     * @return 清理后的安全文件名
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown";
        }
        return convertToPinyin(filename);
    }

    /**
     * 将字符串中的汉字转换为大驼峰拼音，非汉字字符保持不变
     *
     * @param input 输入字符串
     * @return 转换后的字符串
     */
    private String convertToPinyin(String input) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);

        StringBuilder result = new StringBuilder();
        for (char ch : input.toCharArray()) {
            try {
                String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(ch, format);
                if (pinyins != null && pinyins.length > 0) {
                    // 首字母大写（大驼峰）
                    result.append(capitalize(pinyins[0]));
                } else {
                    // 非汉字字符直接保留，但特殊字符需要处理
                    result.append(sanitizeChar(ch));
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                result.append(sanitizeChar(ch));
            }
        }
        return result.toString();
    }

    /**
     * 处理单个字符，保留安全字符，其他替换为下划线
     */
    private char sanitizeChar(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-') {
            return ch;
        }
        return '_';
    }

    /**
     * 首字母大写
     */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
