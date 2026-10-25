package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.listener.AnalyzeStreamProducer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 简历上传服务
 * 处理简历上传、解析的业务逻辑
 * AI 分析改为异步处理，通过 Redis Stream 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    private final ResumeParseService parseService;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AppConfigProperties appConfig;
    private final FileValidationService fileValidationService;
    private final AnalyzeStreamProducer analyzeStreamProducer;
    private final ResumeRepository resumeRepository;
    private final TransactionalExecutor transactionalExecutor;

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * 上传并分析简历（异步）
     *
     * @param file 简历文件
     * @return 上传结果（分析将异步进行）
     */
    public Map<String, Object> uploadAndAnalyze(org.springframework.web.multipart.MultipartFile file) {
        long startTime = System.currentTimeMillis();

        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历");

        String fileName = file.getOriginalFilename();
        long fileSize = file.getSize();
        log.info("收到简历上传请求: {}, 大小: {} bytes ({}), 上传开始处理",
            fileName, fileSize, formatFileSize(fileSize));

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);

        // 3. 检查简历是否已存在（去重）
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            log.info("简历上传处理完成（重复）: {} - 耗时: {}ms",
                fileName, System.currentTimeMillis() - startTime);
            return handleDuplicateResume(existingResume.get());
        }

        // 4. 解析简历文本
        long parseStart = System.currentTimeMillis();
        String resumeText = parseService.parseResume(file);
        if (resumeText == null || resumeText.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF");
        }
        log.info("简历文本解析完成: {} - 解析耗时: {}ms, 文本长度: {} 字符",
            fileName, System.currentTimeMillis() - parseStart, resumeText.length());

        // 5. 保存简历到RustFS
        long storageStart = System.currentTimeMillis();
        String fileKey = storageService.uploadResume(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("简历已存储到RustFS: {} - 存储耗时: {}ms",
            fileKey, System.currentTimeMillis() - storageStart);

        // 6. 保存简历到数据库（状态为 PENDING）；失败时按 fileKey 补偿删除孤儿对象（简历是先解析再上传，补偿只包在 S3 成功之后）
        ResumeEntity savedResume;
        try {
            savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl);
        } catch (Exception dbError) {
            try {
                storageService.deleteResume(fileKey);
                log.warn("数据库保存失败已补偿删除孤儿对象: fileKey={}", fileKey);
            } catch (Exception cleanupError) {
                log.error("补偿删除孤儿对象失败，需人工清理: fileKey={}, error={}",
                    fileKey, cleanupError.getMessage(), cleanupError);
            }
            throw dbError;
        }

        // 7. 发送分析任务到 Redis Stream；投递失败时文件与实体保留（手动重试依据），返回真实状态
        boolean enqueueAccepted = analyzeStreamProducer.sendAnalyzeTask(savedResume.getId());

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("简历上传处理完成: {}, resumeId={} - 总耗时: {}ms (解析+存储+入库)",
            fileName, savedResume.getId(), totalTime);

        // 8. 返回结果：状态与数据库真实状态一致，投递失败不伪装成 PENDING
        return Map.of(
            "resume", Map.of(
                "id", savedResume.getId(),
                "filename", savedResume.getOriginalFilename(),
                "analyzeStatus", enqueueAccepted ? AsyncTaskStatus.PENDING.name() : AsyncTaskStatus.FAILED.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl,
                "resumeId", savedResume.getId()
            ),
            "enqueueAccepted", enqueueAccepted,
            "message", enqueueAccepted ? "" : "简历已保存，但分析任务投递失败，请稍后重试",
            "duplicate", false
        );
    }

    /**
     * 格式化文件大小为可读字符串
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType) {
        fileValidationService.validateContentTypeByList(
            contentType,
            appConfig.getAllowedTypes(),
            "不支持的文件类型: " + contentType
        );
    }

    /**
     * 处理重复简历
     */
    private Map<String, Object> handleDuplicateResume(ResumeEntity resume) {
        log.info("检测到重复简历，返回历史分析结果: resumeId={}", resume.getId());

        // 获取历史分析结果
        Optional<ResumeAnalysisResponse> analysisOpt = persistenceService.getLatestAnalysisAsDTO(resume.getId());

        // 已有分析结果，直接返回
        // 没有分析结果（可能之前分析失败），返回当前状态
        return analysisOpt.map(resumeAnalysisResponse -> Map.of(
                "analysis", resumeAnalysisResponse,
                "storage", Map.of(
                        "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                        "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                        "resumeId", resume.getId()
                ),
                "enqueueAccepted", true,
                "message", "检测到重复文件，已返回现有分析结果",
                "duplicate", true
        )).orElseGet(() -> Map.of(
                "resume", Map.of(
                        "id", resume.getId(),
                        "filename", resume.getOriginalFilename(),
                        "analyzeStatus", resume.getAnalyzeStatus() != null ? resume.getAnalyzeStatus().name() : AsyncTaskStatus.PENDING.name()
                ),
                "storage", Map.of(
                        "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                        "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                        "resumeId", resume.getId()
                ),
                "enqueueAccepted", true,
                "message", "检测到重复文件，已返回现有简历",
                "duplicate", true
        ));
    }

    /**
     * 重新分析简历（手动重试）
     * 只投递简历 ID，正文以数据库实体为事实来源；
     * 历史正文为空时由消费者从 RustFS 恢复并回填
     *
     * @param resumeId 简历ID
     */
    public void reanalyze(Long resumeId) {
        findScopedResume(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        log.info("开始重新分析简历: resumeId={}", resumeId);

        transactionalExecutor.run(() -> {
            ResumeEntity resume = findScopedResume(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
            resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
            resume.setAnalyzeError(null);
            resume.setAnalyzeAttemptId(null);
            resume.setAnalyzeUpdatedAt(java.time.LocalDateTime.now());
            resumeRepository.save(resume);
        });
        // 手动重试清零自动恢复计数
        findScopedResume(resumeId).ifPresent(resume -> {
            resume.setAnalyzeRecoveryCount(0);
            resumeRepository.save(resume);
        });

        // 事务提交后再发送分析任务到 Stream；投递失败必须让调用方感知
        if (!analyzeStreamProducer.sendAnalyzeTask(resumeId)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务投递失败，请稍后重试");
        }

        log.info("重新分析任务已发送: resumeId={}", resumeId);
    }

    private Optional<ResumeEntity> findScopedResume(Long resumeId) {
        return AuthUserContext.currentUserId()
            .map(ownerId -> resumeRepository.findByIdAndOwnerId(resumeId, ownerId))
            .orElseGet(() -> resumeRepository.findById(resumeId));
    }
}
