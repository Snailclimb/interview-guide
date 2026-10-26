package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 知识库实体
 */
@Entity
@Table(name = "knowledge_bases", indexes = {
    @Index(name = "uk_kb_owner_hash", columnList = "owner_id,fileHash", unique = true),
    @Index(name = "idx_kb_category", columnList = "category")
})
public class KnowledgeBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 文件内容的SHA-256哈希值，用于去重
    @Column(nullable = false, length = 64)
    private String fileHash;

    /**
     * 登录用户上传的知识库所有者；NULL 表示历史部署级共享知识库。
     */
    @Column(name = "owner_id", length = 64)
    private String ownerId;

    // 知识库名称（用户自定义或从文件名提取）
    @Column(nullable = false)
    private String name;

    // 分类/分组（如"Java面试"、"项目文档"等）
    @Column(length = 100)
    private String category;

    // 原始文件名
    @Column(nullable = false)
    private String originalFilename;
    
    // 文件大小（字节）
    private Long fileSize;
    
    // 文件类型
    private String contentType;
    
    // RustFS存储的文件Key
    @Column(length = 500)
    private String storageKey;
    
    // RustFS存储的文件URL
    @Column(length = 1000)
    private String storageUrl;
    
    // 上传时间
    @Column(nullable = false)
    private LocalDateTime uploadedAt;
    
    // 最后访问时间
    private LocalDateTime lastAccessedAt;
    
    // 访问次数
    private Integer accessCount = 0;
    
    // 问题数量（用户针对此知识库提问的次数）
    private Integer questionCount = 0;

    // 向量化状态（新上传时为 PENDING，异步处理完成后变为 COMPLETED）
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VectorStatus vectorStatus = VectorStatus.PENDING;

    // 向量化错误信息（失败时记录）
    @Column(length = 500)
    private String vectorError;

    // 向量分块数量
    private Integer chunkCount = 0;

    /**
     * 本次向量化使用的 Chunk 策略快照（无密钥 JSON）；null 表示旧版默认 800。
     */
    @Column(name = "vector_config")
    private String vectorConfig;

    /**
     * 最近一次向量化完成时间。
     */
    @Column(name = "vectorized_at")
    private LocalDateTime vectorizedAt;

    /**
     * 最后一次有效进展时间（状态变化与心跳都推进），用于卡住任务恢复判定。
     */
    @Column(name = "vector_updated_at")
    private LocalDateTime vectorUpdatedAt;

    /**
     * 恢复调度器自动补投次数（手动重试清零），与 Stream retryCount 独立。
     */
    @Column(name = "vector_recovery_count", nullable = false)
    private int vectorRecoveryCount = 0;

    /**
     * 当前 PROCESSING 执行代次；条件心跳和终态写入必须同时匹配该值。
     */
    @Column(name = "vector_attempt_id", length = 36)
    private String vectorAttemptId;

    // 问题生成状态
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private QuestionGenStatus questionGenStatus = QuestionGenStatus.NONE;

    // 问题生成错误信息（失败时记录）
    @Column(length = 500)
    private String questionGenError;

    // 问题生成任务ID（用于幂等判断，防止旧任务覆盖新任务）
    @Column(length = 36)
    private String questionGenTaskId;

    // 问题生成参数快照（不包含 Prompt、上下文或密钥）
    @Column(columnDefinition = "TEXT")
    private String questionGenConfig;

    // 问题生成结果摘要
    @Column(length = 500)
    private String questionGenMessage;

    private Integer questionGenSavedCount = 0;

    private Integer questionGenSkippedCount = 0;

    private LocalDateTime questionGenUpdatedAt;
    
    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
        accessCount = 1;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getOriginalFilename() {
        return originalFilename;
    }
    
    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }
    
    public Long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    public String getStorageKey() {
        return storageKey;
    }
    
    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }
    
    public String getStorageUrl() {
        return storageUrl;
    }
    
    public void setStorageUrl(String storageUrl) {
        this.storageUrl = storageUrl;
    }
    
    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
    
    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
    
    public LocalDateTime getLastAccessedAt() {
        return lastAccessedAt;
    }
    
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }
    
    public Integer getAccessCount() {
        return accessCount;
    }
    
    public void setAccessCount(Integer accessCount) {
        this.accessCount = accessCount;
    }
    
    public Integer getQuestionCount() {
        return questionCount;
    }
    
    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }
    
    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }
    
    public void incrementQuestionCount() {
        this.questionCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public VectorStatus getVectorStatus() {
        return vectorStatus;
    }

    public void setVectorStatus(VectorStatus vectorStatus) {
        this.vectorStatus = vectorStatus;
    }

    public String getVectorError() {
        return vectorError;
    }

    public void setVectorError(String vectorError) {
        this.vectorError = vectorError;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getVectorConfig() {
        return vectorConfig;
    }

    public void setVectorConfig(String vectorConfig) {
        this.vectorConfig = vectorConfig;
    }

    public LocalDateTime getVectorizedAt() {
        return vectorizedAt;
    }

    public LocalDateTime getVectorUpdatedAt() {
        return vectorUpdatedAt;
    }

    public void setVectorUpdatedAt(LocalDateTime vectorUpdatedAt) {
        this.vectorUpdatedAt = vectorUpdatedAt;
    }

    public int getVectorRecoveryCount() {
        return vectorRecoveryCount;
    }

    public void setVectorRecoveryCount(int vectorRecoveryCount) {
        this.vectorRecoveryCount = vectorRecoveryCount;
    }

    public String getVectorAttemptId() {
        return vectorAttemptId;
    }

    public void setVectorAttemptId(String vectorAttemptId) {
        this.vectorAttemptId = vectorAttemptId;
    }

    public void setVectorizedAt(LocalDateTime vectorizedAt) {
        this.vectorizedAt = vectorizedAt;
    }

    public QuestionGenStatus getQuestionGenStatus() {
        return questionGenStatus;
    }

    public void setQuestionGenStatus(QuestionGenStatus questionGenStatus) {
        this.questionGenStatus = questionGenStatus;
    }

    public String getQuestionGenError() {
        return questionGenError;
    }

    public void setQuestionGenError(String questionGenError) {
        this.questionGenError = questionGenError;
    }

    public String getQuestionGenTaskId() {
        return questionGenTaskId;
    }

    public void setQuestionGenTaskId(String questionGenTaskId) {
        this.questionGenTaskId = questionGenTaskId;
    }

    public String getQuestionGenConfig() {
        return questionGenConfig;
    }

    public void setQuestionGenConfig(String questionGenConfig) {
        this.questionGenConfig = questionGenConfig;
    }

    public String getQuestionGenMessage() {
        return questionGenMessage;
    }

    public void setQuestionGenMessage(String questionGenMessage) {
        this.questionGenMessage = questionGenMessage;
    }

    public Integer getQuestionGenSavedCount() {
        return questionGenSavedCount;
    }

    public void setQuestionGenSavedCount(Integer questionGenSavedCount) {
        this.questionGenSavedCount = questionGenSavedCount;
    }

    public Integer getQuestionGenSkippedCount() {
        return questionGenSkippedCount;
    }

    public void setQuestionGenSkippedCount(Integer questionGenSkippedCount) {
        this.questionGenSkippedCount = questionGenSkippedCount;
    }

    public LocalDateTime getQuestionGenUpdatedAt() {
        return questionGenUpdatedAt;
    }

    public void setQuestionGenUpdatedAt(LocalDateTime questionGenUpdatedAt) {
        this.questionGenUpdatedAt = questionGenUpdatedAt;
    }
}
