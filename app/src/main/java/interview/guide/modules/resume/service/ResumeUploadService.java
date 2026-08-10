package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
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
     * 上传并分析简历（异步，首版本）
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

        // 4-8. 解析、存储、入库、触发异步分析
        return doUpload(file, null, 1, null, null, startTime);
    }

    /**
     * 上传优化后的简历新版本（异步分析）
     * 新版本作为独立简历记录挂到同一版本族，旧版本与旧分析全部保留。
     *
     * @param parentId    父版本简历ID
     * @param file        优化后的简历文件
     * @param versionNote 版本说明（可选，如"根据建议优化了项目描述"）
     * @return 上传结果（分析将异步进行）
     */
    public Map<String, Object> uploadVersion(Long parentId,
                                             org.springframework.web.multipart.MultipartFile file,
                                             String versionNote) {
        long startTime = System.currentTimeMillis();

        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历");

        // 2. 校验父版本存在并解析版本族
        ResumeEntity parent = resumeRepository.findById(parentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "父版本简历不存在"));
        Long versionGroupId = parent.resolveVersionGroupId();
        int nextVersionNo = resolveNextVersionNo(parent, versionGroupId);

        String fileName = file.getOriginalFilename();
        long fileSize = file.getSize();
        log.info("收到简历新版本上传请求: parentId={}, 版本族={}, 新版本号=v{}, 文件: {} ({} bytes)",
            parentId, versionGroupId, nextVersionNo, fileName, fileSize);

        // 3. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);

        // 4. 去重：相同内容已在库中（含同族其他版本）则返回已有记录
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            ResumeEntity existing = existingResume.get();
            log.info("简历新版本上传处理完成（内容重复）: 命中 v{} (resumeId={}) - 耗时: {}ms",
                existing.getVersionNo(), existing.getId(), System.currentTimeMillis() - startTime);
            return handleDuplicateResume(existing);
        }

        // 5-9. 解析、存储、入库（带版本信息）、触发异步分析
        return doUpload(file, versionGroupId, nextVersionNo, parentId, versionNote, startTime);
    }

    /**
     * 计算版本族内下一个版本号
     * 首版本（根）的 versionGroupId 为 NULL，以自身 id 为根；后续版本共享根的 id。
     */
    private int resolveNextVersionNo(ResumeEntity parent, Long versionGroupId) {
        if (parent.getVersionGroupId() == null) {
            // 父版本是根：族内至少已有 v1
            int maxNo = 1;
            Optional<ResumeEntity> latestInGroup = resumeRepository
                .findFirstByVersionGroupIdOrderByVersionNoDesc(parent.getId());
            if (latestInGroup.isPresent()) {
                maxNo = latestInGroup.get().getVersionNo();
            }
            return maxNo + 1;
        }
        Optional<ResumeEntity> latestInGroup = resumeRepository
            .findFirstByVersionGroupIdOrderByVersionNoDesc(versionGroupId);
        return latestInGroup.map(resume -> resume.getVersionNo() + 1).orElse(1);
    }

    /**
     * 公共上传流程：解析文本 → 存储RustFS → 保存数据库 → 发送异步分析任务
     */
    private Map<String, Object> doUpload(org.springframework.web.multipart.MultipartFile file,
                                         Long versionGroupId,
                                         int versionNo,
                                         Long parentId,
                                         String versionNote,
                                         long startTime) {
        String fileName = file.getOriginalFilename();

        // 5. 解析简历文本
        long parseStart = System.currentTimeMillis();
        String resumeText = parseService.parseResume(file);
        if (resumeText == null || resumeText.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF");
        }
        log.info("简历文本解析完成: {} - 解析耗时: {}ms, 文本长度: {} 字符",
            fileName, System.currentTimeMillis() - parseStart, resumeText.length());

        // 6. 保存简历到RustFS
        long storageStart = System.currentTimeMillis();
        String fileKey = storageService.uploadResume(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("简历已存储到RustFS: {} - 存储耗时: {}ms",
            fileKey, System.currentTimeMillis() - storageStart);

        // 7. 保存简历到数据库（状态为 PENDING）
        ResumeEntity savedResume = persistenceService.saveResume(
            file, resumeText, fileKey, fileUrl, versionGroupId, versionNo, parentId, versionNote);

        // 8. 发送分析任务到 Redis Stream（异步处理）
        analyzeStreamProducer.sendAnalyzeTask(savedResume.getId(), resumeText);

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("简历上传处理完成: {}, resumeId={}, v{} - 总耗时: {}ms (解析+存储+入库)",
            fileName, savedResume.getId(), savedResume.getVersionNo(), totalTime);

        // 9. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of(
            "resume", Map.of(
                "id", savedResume.getId(),
                "filename", savedResume.getOriginalFilename(),
                "analyzeStatus", AsyncTaskStatus.PENDING.name(),
                "versionNo", savedResume.getVersionNo()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl,
                "resumeId", savedResume.getId()
            ),
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
                "duplicate", true
        ));
    }

    /**
     * 重新分析简历（手动重试）
     * 从数据库获取简历文本并发送分析任务
     *
     * @param resumeId 简历ID
     */
    public void reanalyze(Long resumeId) {
        ResumeReanalyzeSource source = loadReanalyzeSource(resumeId);

        log.info("开始重新分析简历: resumeId={}, filename={}", resumeId, source.originalFilename());

        String resumeText = source.resumeText();
        boolean shouldCacheResumeText = !hasText(resumeText);
        if (shouldCacheResumeText) {
            // 如果没有缓存的文本，尝试重新解析
            resumeText = parseService.downloadAndParseContent(
                source.storageKey(), source.originalFilename());
            if (!hasText(resumeText)) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
        }

        String taskContent = resumeText;
        transactionalExecutor.run(
            () -> updateResumeForReanalysis(resumeId, taskContent, shouldCacheResumeText));

        // 事务提交后再发送分析任务到 Stream
        analyzeStreamProducer.sendAnalyzeTask(resumeId, taskContent);

        log.info("重新分析任务已发送: resumeId={}", resumeId);
    }

    private ResumeReanalyzeSource loadReanalyzeSource(Long resumeId) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        return new ResumeReanalyzeSource(
            resume.getOriginalFilename(),
            resume.getStorageKey(),
            resume.getResumeText()
        );
    }

    private void updateResumeForReanalysis(
        Long resumeId,
        String resumeText,
        boolean shouldCacheResumeText
    ) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        if (shouldCacheResumeText || !hasText(resume.getResumeText())) {
            resume.setResumeText(resumeText);
        }
        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
        resume.setAnalyzeError(null);
        resumeRepository.save(resume);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record ResumeReanalyzeSource(
        String originalFilename,
        String storageKey,
        String resumeText
    ) {
    }
}
