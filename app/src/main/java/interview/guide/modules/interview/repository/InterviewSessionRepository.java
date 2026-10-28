package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

/**
 * 面试会话Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    long countByOwnerIdIsNotNull();

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE InterviewSessionEntity s SET s.ownerId = :ownerId WHERE s.ownerId IS NULL")
    int claimUnownedRows(@Param("ownerId") String ownerId);

    Optional<InterviewSessionEntity> findBySessionIdAndOwnerId(String sessionId, String ownerId);

    Optional<InterviewSessionEntity> findByRequestIdAndOwnerId(String requestId, String ownerId);

    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume "
        + "WHERE s.sessionId = :sessionId AND s.ownerId = :ownerId")
    Optional<InterviewSessionEntity> findBySessionIdWithResumeAndOwnerId(
        @Param("sessionId") String sessionId, @Param("ownerId") String ownerId);

    List<InterviewSessionEntity> findByResumeIdAndOwnerIdOrderByCreatedAtDesc(
        Long resumeId, String ownerId);

    List<InterviewSessionEntity> findAllByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<InterviewSessionEntity> findFirstByResumeIdAndOwnerIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, String ownerId, List<SessionStatus> statuses);

    List<InterviewSessionEntity> findTop10ByResumeIdAndOwnerIdAndSkillIdOrderByCreatedAtDesc(
        Long resumeId, String ownerId, String skillId);

    List<InterviewSessionEntity> findTop10ByOwnerIdAndSkillIdOrderByCreatedAtDesc(
        String ownerId, String skillId);

    /**
     * 根据会话ID查找
     */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    /** Agent 首次接管未归属的登录前历史记录时锁定行，避免两个账号同时认领。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InterviewSessionEntity s WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

    Optional<InterviewSessionEntity> findByRequestId(String requestId);

    /**
     * 根据会话ID查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);
    
    /**
     * 根据简历查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);
    
    /**
     * 根据简历ID查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);
    
    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     */
    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );
    
    /**
     * 根据简历ID和状态查找会话
     */
    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找所有面试会话（按创建时间倒序）
     */
    List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    List<InterviewSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);
}
