package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库删除服务
 * 负责知识库的删除操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeleteService {
    
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatSessionRepository sessionRepository;
    private final KnowledgeBaseVectorService vectorService;
    private final FileStorageService storageService;
    private final TransactionalExecutor transactionalExecutor;
    
    /**
     * 删除知识库
     * 包括：RAG会话关联、向量数据、RustFS文件、数据库记录
     */
    public void deleteKnowledgeBase(Long id) {
        String storageKey = transactionalExecutor.call(() -> deleteKnowledgeBaseRecords(id));

        vectorService.deleteByKnowledgeBaseId(id);

        try {
            storageService.deleteKnowledgeBase(storageKey);
        } catch (Exception e) {
            log.warn(
                "知识库数据库记录已删除，但RustFS文件清理失败，可后续按storageKey补偿: kbId={}, storageKey={}, error={}",
                id, storageKey, e.getMessage(), e
            );
        }

        log.info("知识库已删除: id={}", id);
    }

    private String deleteKnowledgeBaseRecords(Long id) {
        // 1. 获取知识库信息
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        String storageKey = kb.getStorageKey();
        
        // 2. 删除所有RAG会话中的知识库关联（必须先删除关联，否则外键约束会阻止删除）
        List<RagChatSessionEntity> sessions = sessionRepository.findByKnowledgeBaseIds(List.of(id));
        for (RagChatSessionEntity session : sessions) {
            session.getKnowledgeBases().removeIf(kbEntity -> kbEntity.getId().equals(id));
            sessionRepository.save(session);
            log.debug("已从会话中移除知识库关联: sessionId={}, kbId={}", session.getId(), id);
        }
        if (!sessions.isEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: kbId={}", sessions.size(), id);
        }
        
        // 3. 删除知识库记录（在事务中）
        knowledgeBaseRepository.deleteById(id);
        return storageKey;
    }
}
