package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.auth.AuthUserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Objects;

/**
 * 知识库持久化服务
 * 处理所有需要事务的数据库操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBasePersistenceService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final VectorRepository vectorRepository;

    /**
     * 处理重复知识库（更新访问计数）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleDuplicateKnowledgeBase(KnowledgeBaseEntity kb, String fileHash) {
        log.info("检测到重复知识库，返回已有记录: kbId={}", kb.getId());
        
        // 更新访问计数（在事务中）
        kb.incrementAccessCount();
        knowledgeBaseRepository.save(kb);
        
        // 重复知识库的向量数据应该已经存在，不需要重新向量化；响应契约与新上传保持一致
        return Map.of(
            "knowledgeBase", Map.of(
                "id", kb.getId(),
                "name", kb.getName(),
                "category", kb.getCategory() != null ? kb.getCategory() : "",
                "fileSize", kb.getFileSize(),
                "vectorStatus", kb.getVectorStatus() != null ? kb.getVectorStatus().name() : "PENDING"
            ),
            "storage", Map.of(
                "fileKey", kb.getStorageKey() != null ? kb.getStorageKey() : "",
                "fileUrl", kb.getStorageUrl() != null ? kb.getStorageUrl() : ""
            ),
            "enqueueAccepted", true,
            "message", "检测到重复文件，已返回现有知识库",
            "duplicate", true
        );
    }

    /**
     * 向量化成功后的快照更新（独立短事务调用方保证）：Chunk 数、策略 JSON、完成时间。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVectorizationSnapshot(Long knowledgeBaseId, int chunkCount, String vectorConfig) {
        knowledgeBaseRepository.findById(knowledgeBaseId).ifPresent(kb -> {
            kb.setChunkCount(chunkCount);
            kb.setVectorConfig(vectorConfig);
            kb.setVectorizedAt(java.time.LocalDateTime.now());
            kb.setVectorUpdatedAt(java.time.LocalDateTime.now());
            knowledgeBaseRepository.save(kb);
        });
    }

    /**
     * 在锁定知识库行后校验执行代次，并原子提升临时向量与写入配置快照。
     * Embedding 已在调用前完成，不会进入本数据库事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public void activateVectorJobAndUpdateSnapshot(Long knowledgeBaseId, String attemptId,
                                                    String jobId, int chunkCount,
                                                    String vectorConfig) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        if (kb.getVectorStatus() != VectorStatus.PROCESSING
            || !Objects.equals(kb.getVectorAttemptId(), attemptId)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化任务执行权已失效");
        }
        vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
        kb.setChunkCount(chunkCount);
        kb.setVectorConfig(vectorConfig);
        kb.setVectorizedAt(java.time.LocalDateTime.now());
        kb.setVectorUpdatedAt(java.time.LocalDateTime.now());
        knowledgeBaseRepository.save(kb);
    }

    /**
     * 保存新知识库元数据到数据库
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity saveKnowledgeBase(MultipartFile file, String name, String category,
                                                  String storageKey, String storageUrl, String fileHash) {
        return saveKnowledgeBase(file, name, category, storageKey, storageUrl, fileHash,
            AuthUserContext.currentUserId().orElse(null));
    }

    /** 保存知识库并记录当前登录用户；旧内部调用仍可使用上面的兼容重载。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity saveKnowledgeBase(MultipartFile file, String name, String category,
                                                  String storageKey, String storageUrl, String fileHash,
                                                  String ownerId) {
        try {
            KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
            kb.setFileHash(fileHash);
            kb.setOwnerId(ownerId);
            kb.setName(name != null && !name.trim().isEmpty() ? name : extractNameFromFilename(file.getOriginalFilename()));
            kb.setCategory(category != null && !category.trim().isEmpty() ? category.trim() : null);
            kb.setOriginalFilename(file.getOriginalFilename());
            kb.setFileSize(file.getSize());
            kb.setContentType(file.getContentType());
            kb.setStorageKey(storageKey);
            kb.setStorageUrl(storageUrl);
            // 进展时间初始化：NULL 会被 stale 扫描的阈值比较排除，导致 PENDING 永远不被恢复
            kb.setVectorUpdatedAt(java.time.LocalDateTime.now());

            KnowledgeBaseEntity saved = knowledgeBaseRepository.save(kb);
            log.info("知识库已保存: id={}, name={}, category={}, hash={}", saved.getId(), saved.getName(), saved.getCategory(), fileHash);
            return saved;
        } catch (Exception e) {
            log.error("保存知识库失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存知识库失败");
        }
    }

    /**
     * 更新知识库向量化状态为 PENDING
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVectorStatusToPending(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        
        kb.setVectorStatus(VectorStatus.PENDING);
        kb.setVectorError(null);
        kb.setVectorAttemptId(null);
        kb.setVectorUpdatedAt(java.time.LocalDateTime.now());
        knowledgeBaseRepository.save(kb);
        
        log.info("知识库向量化状态已更新为 PENDING: kbId={}", kbId);
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
