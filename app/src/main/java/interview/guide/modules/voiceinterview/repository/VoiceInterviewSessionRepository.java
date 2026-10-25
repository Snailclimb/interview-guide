package interview.guide.modules.voiceinterview.repository;

import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity.InterviewPhase;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 语音面试会话Repository
 */
@Repository
public interface VoiceInterviewSessionRepository extends JpaRepository<VoiceInterviewSessionEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM VoiceInterviewSessionEntity s WHERE s.id = :sessionId")
    Optional<VoiceInterviewSessionEntity> findByIdForUpdate(@Param("sessionId") Long sessionId);

    /**
     * 根据用户ID查找所有会话，按开始时间倒序
     */
    List<VoiceInterviewSessionEntity> findByUserIdOrderByStartTimeDesc(String userId);

    /**
     * 查找指定状态且结束时间早于给定时间的会话
     * Note: Queries the AsyncTaskStatus field, not InterviewPhase
     */
    Optional<VoiceInterviewSessionEntity> findByStatusAndEndTimeBefore(
        interview.guide.common.model.AsyncTaskStatus status,
        LocalDateTime time
    );

    /**
     * Find all sessions for a user, ordered by update time
     */
    List<VoiceInterviewSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * Find sessions by user and status, ordered by update time
     */
    List<VoiceInterviewSessionEntity> findByUserIdAndStatusOrderByUpdatedAtDesc(
        String userId,
        VoiceInterviewSessionStatus status
    );

    @Query("SELECT COUNT(s) FROM VoiceInterviewSessionEntity s "
        + "WHERE s.userId IS NOT NULL AND s.userId <> 'default'")
    long countByAssignedUserId();

    @Modifying
    @Query("UPDATE VoiceInterviewSessionEntity s SET s.userId = :userId "
        + "WHERE s.userId IS NULL OR s.userId = 'default'")
    int claimLegacyRows(@Param("userId") String userId);

    Optional<VoiceInterviewSessionEntity> findByIdAndUserId(Long id, String userId);

    List<VoiceInterviewSessionEntity> findByStatusAndStartTimeBefore(
        VoiceInterviewSessionStatus status,
        LocalDateTime time
    );

    List<VoiceInterviewSessionEntity> findByEvaluateStatusAndUpdatedAtBefore(
        interview.guide.common.model.AsyncTaskStatus evaluateStatus,
        LocalDateTime time
    );
}
