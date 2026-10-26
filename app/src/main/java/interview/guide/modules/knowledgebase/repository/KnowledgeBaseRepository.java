package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.QuestionGenStatus;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 知识库Repository
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    /**
     * 根据文件哈希查找知识库（用于去重）
     */
    Optional<KnowledgeBaseEntity> findByFileHash(String fileHash);

    /** 按所有者范围去重，NULL owner 通过 IS NULL 语义兼容历史共享库。 */
    Optional<KnowledgeBaseEntity> findByFileHashAndOwnerId(String fileHash, String ownerId);

    /**
     * 锁定知识库行，用于串行化同一知识库的题目生成状态迁移。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM KnowledgeBaseEntity k WHERE k.id = :id")
    Optional<KnowledgeBaseEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);

    /**
     * 按上传时间倒序查找所有知识库
     */
    List<KnowledgeBaseEntity> findAllByOrderByUploadedAtDesc();

    /**
     * 获取所有不同的分类
     */
    @Query("SELECT DISTINCT k.category FROM KnowledgeBaseEntity k WHERE k.category IS NOT NULL ORDER BY k.category")
    List<String> findAllCategories();

    /**
     * 根据分类查找知识库
     */
    List<KnowledgeBaseEntity> findByCategoryOrderByUploadedAtDesc(String category);

    /**
     * 查找未分类的知识库
     */
    List<KnowledgeBaseEntity> findByCategoryIsNullOrderByUploadedAtDesc();

    /**
     * 按名称或文件名模糊搜索（不区分大小写）
     */
    @Query("SELECT k FROM KnowledgeBaseEntity k WHERE LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY k.uploadedAt DESC")
    List<KnowledgeBaseEntity> searchByKeyword(@Param("keyword") String keyword);

    /**
     * 按文件大小排序
     */
    List<KnowledgeBaseEntity> findAllByOrderByFileSizeDesc();

    /**
     * 按访问次数排序
     */
    List<KnowledgeBaseEntity> findAllByOrderByAccessCountDesc();

    /**
     * 按提问次数排序
     */
    List<KnowledgeBaseEntity> findAllByOrderByQuestionCountDesc();

    // ==================== 批量更新 ====================

    /**
     * 批量增加知识库提问计数
     * @param ids 知识库ID列表
     * @return 更新的行数
     */
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity k SET k.questionCount = k.questionCount + 1 WHERE k.id IN :ids")
    int incrementQuestionCountBatch(@Param("ids") List<Long> ids);

    // ==================== 统计查询 ====================

    /**
     * 统计总提问次数
     */
    @Query("SELECT COALESCE(SUM(k.questionCount), 0) FROM KnowledgeBaseEntity k")
    long sumQuestionCount();

    /**
     * 统计总访问次数
     */
    @Query("SELECT COALESCE(SUM(k.accessCount), 0) FROM KnowledgeBaseEntity k")
    long sumAccessCount();

    /**
     * 按向量化状态统计数量
     */
    long countByVectorStatus(VectorStatus vectorStatus);

    /**
     * 按向量化状态查找知识库（按上传时间倒序）
     */
    List<KnowledgeBaseEntity> findByVectorStatusOrderByUploadedAtDesc(VectorStatus vectorStatus);

    @Query("SELECT k FROM KnowledgeBaseEntity k "
        + "WHERE k.questionGenStatus = :status "
        + "AND (k.questionGenUpdatedAt IS NULL OR k.questionGenUpdatedAt < :threshold)")
    List<KnowledgeBaseEntity> findStaleQuestionGenerationTasks(
        @Param("status") QuestionGenStatus status,
        @Param("threshold") LocalDateTime threshold);

    // ========== P1-07 条件状态更新（终态不被覆盖，多实例安全） ==========

    /** PENDING → PROCESSING 条件领取，返回是否领取成功 */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'PROCESSING', "
        + "kb.vectorAttemptId = :attemptId, kb.vectorUpdatedAt = :now "
        + "WHERE kb.id = :id AND kb.vectorStatus = 'PENDING'")
    int tryMarkVectorProcessing(@Param("id") Long id,
                                @Param("attemptId") String attemptId,
                                @Param("now") LocalDateTime now);

    /** 心跳：仍为 PROCESSING 才推进进展时间 */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorUpdatedAt = :now "
        + "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' "
        + "AND kb.vectorAttemptId = :attemptId")
    int heartbeatVectorProcessing(@Param("id") Long id,
                                  @Param("attemptId") String attemptId,
                                  @Param("now") LocalDateTime now);

    /** PROCESSING → COMPLETED（非 PROCESSING 时 no-op） */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'COMPLETED', "
        + "kb.vectorError = null, kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now "
        + "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' "
        + "AND kb.vectorAttemptId = :attemptId")
    int completeVectorIfProcessing(@Param("id") Long id,
                                   @Param("attemptId") String attemptId,
                                   @Param("now") LocalDateTime now);

    /** 当前执行代次 PROCESSING → FAILED。 */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'FAILED', "
        + "kb.vectorError = :error, kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now "
        + "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' "
        + "AND kb.vectorAttemptId = :attemptId")
    int failVectorIfProcessing(@Param("id") Long id,
                               @Param("attemptId") String attemptId,
                               @Param("error") String error,
                               @Param("now") LocalDateTime now);

    /** 尚未领取的 PENDING → FAILED，用于生产者或恢复补投失败。 */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'FAILED', "
        + "kb.vectorError = :error, kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now "
        + "WHERE kb.id = :id AND kb.vectorStatus = 'PENDING'")
    int failVectorIfPending(@Param("id") Long id,
                            @Param("error") String error,
                            @Param("now") LocalDateTime now);

    /** PROCESSING → PENDING（重试重置） */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'PENDING', "
        + "kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now "
        + "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' "
        + "AND kb.vectorAttemptId = :attemptId")
    int resetVectorToPending(@Param("id") Long id,
                             @Param("attemptId") String attemptId,
                             @Param("now") LocalDateTime now);

    /** 恢复补投前原子推进时间（PENDING 且早于阈值），保证同期只补投一次 */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorUpdatedAt = :now WHERE kb.id = :id AND kb.vectorStatus = 'PENDING' AND kb.vectorUpdatedAt < :threshold")
    int touchQueuedVectorForRecovery(@Param("id") Long id,
                                     @Param("threshold") LocalDateTime threshold,
                                     @Param("now") LocalDateTime now);

    /** PROCESSING 无进展超阈值 → PENDING（恢复前置条件重置） */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorStatus = 'PENDING', "
        + "kb.vectorAttemptId = null, kb.vectorUpdatedAt = :now "
        + "WHERE kb.id = :id AND kb.vectorStatus = 'PROCESSING' "
        + "AND kb.vectorUpdatedAt < :threshold")
    int resetStaleVectorProcessing(@Param("id") Long id,
                                   @Param("threshold") LocalDateTime threshold,
                                   @Param("now") LocalDateTime now);

    /** 恢复计数 +1，返回更新后计数用 findById 读取；此处只做自增 */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorRecoveryCount = kb.vectorRecoveryCount + 1 WHERE kb.id = :id")
    int incrementVectorRecoveryCount(@Param("id") Long id);

    /** 手动重试清零恢复计数 */
    @Transactional
    @Modifying
    @Query("UPDATE KnowledgeBaseEntity kb SET kb.vectorRecoveryCount = 0 WHERE kb.id = :id")
    int resetVectorRecoveryCount(@Param("id") Long id);

    /** 卡住任务扫描（带每轮上限） */
    @Query("SELECT kb FROM KnowledgeBaseEntity kb WHERE kb.vectorStatus = :status AND kb.vectorUpdatedAt < :threshold ORDER BY kb.vectorUpdatedAt ASC")
    java.util.List<KnowledgeBaseEntity> findStaleByVectorStatus(@Param("status") VectorStatus status,
                                                                @Param("threshold") LocalDateTime threshold,
                                                                Pageable pageable);
}
