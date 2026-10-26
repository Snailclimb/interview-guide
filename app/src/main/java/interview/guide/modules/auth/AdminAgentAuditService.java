package interview.guide.modules.auth;

import interview.guide.modules.agent.repository.AgentAuditEventRepository;
import interview.guide.modules.auth.model.AdminAgentAuditEventResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理员侧 Agent 低敏审计查询，不开放审计写入或正文读取。 */
@Service
@RequiredArgsConstructor
public class AdminAgentAuditService {
    private static final int MAX_LIMIT = 200;

    private final AgentAuditEventRepository repository;

    @Transactional(readOnly = true)
    public List<AdminAgentAuditEventResponse> list(int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit));
        return repository.findTop200ByOrderByCreatedAtDesc().stream()
            .limit(safeLimit)
            .map(AdminAgentAuditEventResponse::from)
            .toList();
    }
}
