package interview.guide.modules.auth;

import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将正式登录上线前的单用户历史数据迁移给首个账号。
 *
 * <p>只有数据库中恰好存在一个账号且尚无任何已归属数据时才会执行，避免多用户环境
 * 把历史数据误分配给后来注册的账号。多账号环境应通过管理迁移或显式数据归属完成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyDataOwnershipService {
    private final UserAccountRepository userRepository;
    private final ResumeRepository resumeRepository;
    private final InterviewSessionRepository sessionRepository;

    @Transactional
    public void claimForSingleUser(String userId) {
        if (userId == null || userId.isBlank() || userRepository.count() != 1
            || resumeRepository.countByOwnerIdIsNotNull() > 0
            || sessionRepository.countByOwnerIdIsNotNull() > 0) {
            return;
        }

        int resumes = resumeRepository.claimUnownedRows(userId);
        int sessions = sessionRepository.claimUnownedRows(userId);
        if (resumes > 0 || sessions > 0) {
            log.info("已将单用户迁移期历史数据归属到首个账号: userId={}, resumes={}, sessions={}",
                userId, resumes, sessions);
        }
    }
}
