package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 知识库统一访问边界。
 *
 * <p>owner_id 为空的历史知识库是部署级共享数据，继续对所有用户可见；
 * 新登录用户创建的知识库只对其所有者可见。没有认证上下文时保持旧版兼容行为，
 * 这样异步消费者和现有内部调用不会被请求级权限逻辑误伤。</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseAccessService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public String currentOwnerId() {
        return AuthUserContext.currentUserId().orElse(null);
    }

    public boolean hasAuthenticatedUser() {
        return currentOwnerId() != null;
    }

    public boolean canRead(KnowledgeBaseEntity knowledgeBase) {
        return knowledgeBase != null && canAccessOwner(knowledgeBase.getOwnerId());
    }

    /**
     * 共享历史知识库仍允许修改，避免改变原有题库管理行为；私有知识库只允许本人修改。
     */
    public boolean canModify(KnowledgeBaseEntity knowledgeBase) {
        return canRead(knowledgeBase);
    }

    public boolean canAccessOwner(String resourceOwnerId) {
        String currentOwnerId = currentOwnerId();
        return currentOwnerId == null
            || resourceOwnerId == null
            || currentOwnerId.equals(resourceOwnerId);
    }

    public void requireReadable(KnowledgeBaseEntity knowledgeBase) {
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (!canRead(knowledgeBase)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该知识库");
        }
    }

    public void requireModifiable(KnowledgeBaseEntity knowledgeBase) {
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (!canModify(knowledgeBase)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权修改该知识库");
        }
    }

    /**
     * 在 RAG 查询、Agent 检索等只拿到 ID 的入口统一校验所有知识库。
     */
    public void requireReadableIds(Collection<Long> knowledgeBaseIds) {
        if (!hasAuthenticatedUser() || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return;
        }
        List<Long> distinctIds = knowledgeBaseIds.stream()
            .filter(id -> id != null)
            .distinct()
            .toList();
        List<KnowledgeBaseEntity> entities = knowledgeBaseRepository.findAllById(distinctIds);
        if (entities.size() != distinctIds.size()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "部分知识库不存在");
        }
        entities.forEach(this::requireReadable);
    }
}
