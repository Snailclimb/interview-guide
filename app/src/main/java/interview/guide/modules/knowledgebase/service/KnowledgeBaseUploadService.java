package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.infrastructure.file.KnowledgeBaseUploadLimiter;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

/**
 * 知识库上传服务
 * 处理知识库上传、解析的业务逻辑
 * 向量化改为异步处理，通过 Redis Stream 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {

    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;
    private final KnowledgeBaseUploadLimiter uploadLimiter;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    
    /**
     * 上传知识库文件
     *
     * @param file 知识库文件
     * @param name 知识库名称（可选，如果为空则从文件名提取）
     * @param category 分类（可选）
     * @return 上传结果和存储信息（包含duplicate字段，表示是否为重复上传）
     */
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category) {
        return uploadLimiter.execute(() -> doUploadKnowledgeBase(file, name, category));
    }

    private Map<String, Object> doUploadKnowledgeBase(
        MultipartFile file, String name, String category) {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.getSize(), category);

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        // 3. 检查知识库是否已存在（去重）
        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        // 4. 保存文件到RustFS
        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("知识库已存储到RustFS: {}", fileKey);

        // 5. 保存知识库元数据到数据库（状态为 PENDING）；失败时补偿删除孤儿对象（按 fileKey，不删实体——此时还没有实体）
        KnowledgeBaseEntity savedKb;
        try {
            savedKb = persistenceService.saveKnowledgeBase(file, name, category, fileKey, fileUrl, fileHash);
        } catch (Exception dbError) {
            compensateOrphanObject(fileKey, dbError);
            throw dbError;
        }

        // 6. 发送向量化任务到 Redis Stream（异步处理，正文由消费者从 RustFS 下载解析）。
        //    投递失败时文件与实体已保存（是手动重试与自动恢复的依据），只返回真实 FAILED 状态
        boolean enqueueAccepted = vectorizeStreamProducer.sendVectorizeTask(savedKb.getId());

        log.info("知识库上传完成: {}, kbId={}, enqueueAccepted={}", fileName, savedKb.getId(), enqueueAccepted);

        // 7. 返回结果：状态与数据库真实状态一致，投递失败不伪装成 PENDING
        return Map.of(
            "knowledgeBase", Map.of(
                "id", savedKb.getId(),
                "name", savedKb.getName(),
                "category", savedKb.getCategory() != null ? savedKb.getCategory() : "",
                "fileSize", savedKb.getFileSize(),
                "vectorStatus", enqueueAccepted ? VectorStatus.PENDING.name() : VectorStatus.FAILED.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl
            ),
            "enqueueAccepted", enqueueAccepted,
            "message", enqueueAccepted ? "" : "文件已保存，但任务投递失败，可在管理页重试",
            "duplicate", false
        );
    }

    /**
     * S3 已成功、数据库保存失败的补偿：按 fileKey 删除孤儿对象。
     * 补偿失败只记录，不覆盖原始业务异常。
     */
    private void compensateOrphanObject(String fileKey, Exception originalError) {
        try {
            storageService.deleteKnowledgeBase(fileKey);
            log.warn("数据库保存失败已补偿删除孤儿对象: fileKey={}", fileKey);
        } catch (Exception cleanupError) {
            log.error("补偿删除孤儿对象失败，需人工清理: fileKey={}, error={}",
                fileKey, cleanupError.getMessage(), cleanupError);
        }
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType, String fileName) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isMarkdownExtension,
            "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD等"
        );
    }
    
    /**
     * 重新向量化知识库（手动重试）
     * 只投递知识库 ID，正文由消费者从 RustFS 下载解析
     *
     * @param kbId 知识库ID
     */
    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        if (kb.getStorageKey() == null || kb.getStorageKey().trim().isEmpty()
            || kb.getOriginalFilename() == null || kb.getOriginalFilename().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "知识库缺少存储信息，无法重新向量化");
        }

        log.info("开始重新向量化知识库: kbId={}", kbId);

        // 1. 更新状态为 PENDING 并清零自动恢复计数（手动重试重新获得完整恢复额度）
        persistenceService.updateVectorStatusToPending(kbId);
        knowledgeBaseRepository.resetVectorRecoveryCount(kbId);

        // 2. 发送向量化任务到 Stream；投递失败必须让调用方感知，不能只写日志
        if (!vectorizeStreamProducer.sendVectorizeTask(kbId)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务投递失败，请稍后重试");
        }

        log.info("重新向量化任务已发送: kbId={}", kbId);
    }
}

