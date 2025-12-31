package interview.guide.modules.knowledgebase.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.storage.FileStorageService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库上传服务
 * 处理知识库上传、解析的业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {
    
    private final KnowledgeBaseParseService parseService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseVectorService vectorService;
    private final AppConfigProperties appConfig;
    
    /**
     * 上传知识库文件
     * 
     * @param file 知识库文件
     * @param name 知识库名称（可选，如果为空则从文件名提取）
     * @return 上传结果和存储信息（包含duplicate字段，表示是否为重复上传）
     */
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name) {
        // 1. 验证文件
        validateFile(file);
        
        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes", fileName, file.getSize());
        
        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);
        
        // 3. 检查知识库是否已存在（去重）
        String fileHash = calculateFileHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }
        
        // 4. 解析知识库文本（用于向量化）
        String content = parseService.parseContent(file);
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确");
        }
        
        // 5. 保存文件到RustFS
        String fileKey = uploadToStorage(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("知识库已存储到RustFS: {}", fileKey);
        
        // 6. 保存知识库元数据到数据库（不存储文本内容）
        KnowledgeBaseEntity savedKb = saveKnowledgeBase(file, name, fileKey, fileUrl, fileHash);
        
        // 7. 向量化并存储到pgvector
        try {
            vectorService.vectorizeAndStore(savedKb.getId(), content);
        } catch (Exception e) {
            log.error("向量化失败，但知识库已保存: kbId={}, error={}", savedKb.getId(), e.getMessage());
            // 不抛出异常，允许后续手动重新向量化
        }
        
        log.info("知识库上传完成: {}, kbId={}", fileName, savedKb.getId());
        
        // 8. 返回结果
        return Map.of(
            "knowledgeBase", Map.of(
                "id", savedKb.getId(),
                "name", savedKb.getName(),
                "fileSize", savedKb.getFileSize(),
                "contentLength", content.length()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl
            ),
            "duplicate", false
        );
    }
    
    /**
     * 上传文件到RustFS存储
     */
    private String uploadToStorage(MultipartFile file) {
        try {
            // 使用知识库专用的上传方法
            return storageService.uploadKnowledgeBase(file);
        } catch (Exception e) {
            log.error("上传文件到RustFS失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件存储失败");
        }
    }
    
    /**
     * 验证文件
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的知识库文件");
        }
        
        // 检查文件大小（限制为50MB）
        long maxSize = 50 * 1024 * 1024; // 50MB
        if (file.getSize() > maxSize) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制");
        }
    }
    
    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType) {
        if (!isAllowedType(contentType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件类型: " + contentType + 
                "，支持的类型：PDF、DOCX、DOC、TXT、MD等");
        }
    }
    
    /**
     * 处理重复知识库（更新访问计数）
     */
    @Transactional(rollbackFor = Exception.class)
    private Map<String, Object> handleDuplicateKnowledgeBase(KnowledgeBaseEntity kb, String fileHash) {
        log.info("检测到重复知识库，返回已有记录: kbId={}", kb.getId());
        
        // 更新访问计数（在事务中）
        kb.incrementAccessCount();
        knowledgeBaseRepository.save(kb);
        
        // 重复知识库的向量数据应该已经存在，不需要重新向量化
        return Map.of(
            "knowledgeBase", Map.of(
                "id", kb.getId(),
                "name", kb.getName(),
                "fileSize", kb.getFileSize(),
                "contentLength", 0  // 不再存储content，所以长度为0
            ),
            "storage", Map.of(
                "fileKey", kb.getStorageKey() != null ? kb.getStorageKey() : "",
                "fileUrl", kb.getStorageUrl() != null ? kb.getStorageUrl() : ""
            ),
            "duplicate", true
        );
    }
    
    /**
     * 检查文件类型是否允许
     */
    private boolean isAllowedType(String contentType) {
        if (contentType == null) {
            return false;
        }
        
        String lowerContentType = contentType.toLowerCase();
        // 支持常见的文档格式
        return lowerContentType.contains("pdf") ||
               lowerContentType.contains("msword") ||
               lowerContentType.contains("wordprocessingml") ||
               lowerContentType.contains("text/plain") ||
               lowerContentType.contains("text/markdown") ||
               lowerContentType.contains("application/rtf");
    }
    
    /**
     * 保存新知识库元数据到数据库
     */
    @Transactional(rollbackFor = Exception.class)
    private KnowledgeBaseEntity saveKnowledgeBase(MultipartFile file, String name, 
                                                  String storageKey, String storageUrl, String fileHash) {
        try {
            KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
            kb.setFileHash(fileHash);
            kb.setName(name != null && !name.trim().isEmpty() ? name : extractNameFromFilename(file.getOriginalFilename()));
            kb.setOriginalFilename(file.getOriginalFilename());
            kb.setFileSize(file.getSize());
            kb.setContentType(file.getContentType());
            kb.setStorageKey(storageKey);
            kb.setStorageUrl(storageUrl);
            
            KnowledgeBaseEntity saved = knowledgeBaseRepository.save(kb);
            log.info("知识库已保存: id={}, name={}, hash={}", saved.getId(), saved.getName(), fileHash);
            return saved;
        } catch (Exception e) {
            log.error("保存知识库失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存知识库失败");
        }
    }
    
    /**
     * 计算文件内容的SHA-256哈希值
     */
    private String calculateFileHash(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(file.getBytes());
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException | IOException e) {
            log.error("计算文件哈希失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }
    
    /**
     * 将字节数组转换为十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
    
    /**
     * 从文件名提取知识库名称（去除扩展名）
     */
    private String extractNameFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "未命名知识库";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }
}

