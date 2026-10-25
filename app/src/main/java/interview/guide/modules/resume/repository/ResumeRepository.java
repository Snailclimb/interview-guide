package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;

/**
 * 简历Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {

    long countByOwnerIdIsNotNull();

    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.ownerId = :ownerId WHERE r.ownerId IS NULL")
    int claimUnownedRows(@Param("ownerId") String ownerId);

    Optional<ResumeEntity> findByIdAndOwnerId(Long id, String ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ResumeEntity r WHERE r.id = :id AND r.ownerId = :ownerId")
    Optional<ResumeEntity> findByIdForUpdateAndOwnerId(
        @Param("id") Long id, @Param("ownerId") String ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ResumeEntity r WHERE r.id = :id")
    Optional<ResumeEntity> findByIdForUpdate(@Param("id") Long id);
    
    /**
     * 根据文件哈希查找简历（用于去重）
     */
    Optional<ResumeEntity> findByFileHash(String fileHash);

    Optional<ResumeEntity> findByFileHashAndOwnerId(String fileHash, String ownerId);

    List<ResumeEntity> findAllByOwnerIdOrderByUploadedAtDesc(String ownerId);
    
    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);

    // ========== P1-07 条件状态更新（终态不被覆盖，多实例安全） ==========

    /** PENDING → PROCESSING 条件领取，返回是否领取成功 */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeStatus = 'PROCESSING', "
        + "r.analyzeAttemptId = :attemptId, r.analyzeUpdatedAt = :now "
        + "WHERE r.id = :id AND r.analyzeStatus = 'PENDING'")
    int tryMarkAnalyzeProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                 @Param("now") LocalDateTime now);

    /** 心跳：仍为 PROCESSING 才推进进展时间 */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeUpdatedAt = :now "
        + "WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' "
        + "AND r.analyzeAttemptId = :attemptId")
    int heartbeatAnalyzeProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                   @Param("now") LocalDateTime now);

    /** PROCESSING → COMPLETED（非 PROCESSING 时 no-op） */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeStatus = 'COMPLETED', r.analyzeError = null, "
        + "r.analyzeAttemptId = null, r.analyzeUpdatedAt = :now "
        + "WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' "
        + "AND r.analyzeAttemptId = :attemptId")
    int completeAnalyzeIfProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                    @Param("now") LocalDateTime now);

    /** 当前执行代次 PROCESSING → FAILED。 */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeStatus = 'FAILED', r.analyzeError = :error, "
        + "r.analyzeAttemptId = null, r.analyzeUpdatedAt = :now "
        + "WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' "
        + "AND r.analyzeAttemptId = :attemptId")
    int failAnalyzeIfProcessing(@Param("id") Long id, @Param("attemptId") String attemptId,
                                @Param("error") String error, @Param("now") LocalDateTime now);

    /** 尚未领取的 PENDING → FAILED，用于生产者或恢复补投失败。 */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeStatus = 'FAILED', r.analyzeError = :error, "
        + "r.analyzeAttemptId = null, r.analyzeUpdatedAt = :now "
        + "WHERE r.id = :id AND r.analyzeStatus = 'PENDING'")
    int failAnalyzeIfPending(@Param("id") Long id, @Param("error") String error,
                             @Param("now") LocalDateTime now);

    /** PROCESSING → PENDING（重试重置） */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeStatus = 'PENDING', r.analyzeAttemptId = null, "
        + "r.analyzeUpdatedAt = :now WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' "
        + "AND r.analyzeAttemptId = :attemptId")
    int resetAnalyzeToPending(@Param("id") Long id, @Param("attemptId") String attemptId,
                              @Param("now") LocalDateTime now);

    /** 恢复补投前原子推进时间（PENDING 且早于阈值） */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeUpdatedAt = :now WHERE r.id = :id AND r.analyzeStatus = 'PENDING' AND r.analyzeUpdatedAt < :threshold")
    int touchQueuedAnalyzeForRecovery(@Param("id") Long id, @Param("threshold") LocalDateTime threshold, @Param("now") LocalDateTime now);

    /** PROCESSING 无进展超阈值 → PENDING */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeStatus = 'PENDING', r.analyzeAttemptId = null, "
        + "r.analyzeUpdatedAt = :now WHERE r.id = :id AND r.analyzeStatus = 'PROCESSING' "
        + "AND r.analyzeUpdatedAt < :threshold")
    int resetStaleAnalyzeProcessing(@Param("id") Long id, @Param("threshold") LocalDateTime threshold, @Param("now") LocalDateTime now);

    /** 恢复计数 +1 */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeRecoveryCount = r.analyzeRecoveryCount + 1 WHERE r.id = :id")
    int incrementAnalyzeRecoveryCount(@Param("id") Long id);

    /** 手动重试清零恢复计数 */
    @Transactional
    @Modifying
    @Query("UPDATE ResumeEntity r SET r.analyzeRecoveryCount = 0 WHERE r.id = :id")
    int resetAnalyzeRecoveryCount(@Param("id") Long id);

    /** 卡住任务扫描（带每轮上限） */
    @Query("SELECT r FROM ResumeEntity r WHERE r.analyzeStatus = :status AND r.analyzeUpdatedAt < :threshold ORDER BY r.analyzeUpdatedAt ASC")
    List<ResumeEntity> findStaleByAnalyzeStatus(@Param("status") interview.guide.common.model.AsyncTaskStatus status,
                                                @Param("threshold") LocalDateTime threshold,
                                                Pageable pageable);
}
