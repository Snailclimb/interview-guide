package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.storage.FileStorageService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

/**
 * 简历上传服务
 * 处理简历上传、解析、分析的业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {
    
    private final ResumeParseService parseService;
    private final ResumeGradingService gradingService;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AppConfigProperties appConfig;
    
    /**
     * 上传并分析简历
     * 
     * @param file 简历文件
     * @return 分析结果和存储信息
     */
    public Map<String, Object> uploadAndAnalyze(MultipartFile file) {
        // 1. 验证文件
        validateFile(file);
        
        String fileName = file.getOriginalFilename();
        log.info("收到简历上传请求: {}, 大小: {} bytes", fileName, file.getSize());
        
        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);
        
        // 3. 检查简历是否已存在（去重）
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            return handleDuplicateResume(existingResume.get());
        }
        
        // 4. 解析简历文本
        String resumeText = parseService.parseResume(file);
        if (resumeText == null || resumeText.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF");
        }
        
        // 5. 保存简历到RustFS
        String fileKey = storageService.uploadResume(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("简历已存储到RustFS: {}", fileKey);
        
        // 6. 保存简历到数据库
        ResumeEntity savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl);
        
        // 7. AI分析简历
        ResumeAnalysisResponse analysis = gradingService.analyzeResume(resumeText);
        
        // 8. 保存评测结果
        persistenceService.saveAnalysis(savedResume, analysis);
        
        log.info("简历分析完成: {}, 得分: {}, resumeId={}", fileName, analysis.overallScore(), savedResume.getId());
        
        // 9. 返回结果，包含存储信息
        return Map.of(
            "analysis", analysis,
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl,
                "resumeId", savedResume.getId()
            ),
            "duplicate", false
        );
    }
    
    /**
     * 验证文件
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的简历文件");
        }
        
        // 检查文件大小（简历限制为10MB）
        long maxSize = 10 * 1024 * 1024; // 10MB
        if (file.getSize() > maxSize) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制");
        }
    }
    
    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType) {
        if (!isAllowedType(contentType)) {
            throw new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "不支持的文件类型: " + contentType);
        }
    }
    
    /**
     * 处理重复简历
     */
    private Map<String, Object> handleDuplicateResume(ResumeEntity resume) {
        log.info("检测到重复简历，返回历史分析结果: resumeId={}", resume.getId());
        
        // 获取历史分析结果，不重新分析
        ResumeAnalysisResponse analysis = persistenceService.getLatestAnalysisAsDTO(resume.getId())
            .orElseGet(() -> gradingService.analyzeResume(resume.getResumeText()));
        
        return Map.of(
            "analysis", analysis,
            "storage", Map.of(
                "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                "resumeId", resume.getId()
            ),
            "duplicate", true
        );
    }
    
    /**
     * 检查文件类型是否允许
     */
    private boolean isAllowedType(String contentType) {
        if (contentType == null || appConfig.getAllowedTypes() == null) {
            return false;
        }
        return appConfig.getAllowedTypes().stream()
            .anyMatch(allowed -> contentType.toLowerCase().contains(allowed.toLowerCase()) 
                || allowed.toLowerCase().contains(contentType.toLowerCase()));
    }
}

