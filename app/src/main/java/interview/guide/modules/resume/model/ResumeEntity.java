package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 简历实体
 * Resume Entity for deduplication and persistence
 */
@Entity
@Table(name = "resumes", indexes = {
    @Index(name = "idx_resume_owner_hash", columnList = "owner_id,fileHash", unique = true),
    @Index(name = "idx_resume_owner_uploaded", columnList = "owner_id,uploadedAt")
})
public class ResumeEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 服务端用户主体；旧数据允许为空，正式模式下不会向未归属数据开放访问。 */
    @Column(name = "owner_id", length = 36)
    private String ownerId;
    
    // 文件内容的SHA-256哈希值，用于去重
    @Column(nullable = false, length = 64)
    private String fileHash;
    
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
    
    // 解析后的简历文本
    @Column(columnDefinition = "TEXT")
    private String resumeText;

    /** 首次解析得到的原始文本，编辑版应用后仍保留用于恢复和对照。 */
    @Column(name = "original_resume_text", columnDefinition = "TEXT")
    private String originalResumeText;
    
    // 上传时间
    @Column(nullable = false)
    private LocalDateTime uploadedAt;
    
    // 最后访问时间
    private LocalDateTime lastAccessedAt;
    
    // 访问次数
    private Integer accessCount = 0;

    // 分析状态（新上传时为 PENDING，异步分析完成后变为 COMPLETED）
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AsyncTaskStatus analyzeStatus = AsyncTaskStatus.PENDING;

    /**
     * 最后一次有效进展时间（状态变化与心跳都推进），用于卡住任务恢复判定。
     */
    @jakarta.persistence.Column(name = "analyze_updated_at")
    private java.time.LocalDateTime analyzeUpdatedAt;

    /**
     * 恢复调度器自动补投次数（手动重试清零），与 Stream retryCount 独立。
     */
    @jakarta.persistence.Column(name = "analyze_recovery_count", nullable = false)
    private int analyzeRecoveryCount = 0;

    /**
     * 当前 PROCESSING 执行代次；条件心跳和终态写入必须同时匹配该值。
     */
    @Column(name = "analyze_attempt_id", length = 36)
    private String analyzeAttemptId;

    // 分析错误信息（失败时记录）
    @Column(length = 500)
    private String analyzeError;
    
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

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
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
    
    public String getResumeText() {
        return resumeText;
    }
    
    public void setResumeText(String resumeText) {
        this.resumeText = resumeText;
    }

    public String getOriginalResumeText() {
        return originalResumeText;
    }

    public void setOriginalResumeText(String originalResumeText) {
        this.originalResumeText = originalResumeText;
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
    
    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public AsyncTaskStatus getAnalyzeStatus() {
        return analyzeStatus;
    }

    public java.time.LocalDateTime getAnalyzeUpdatedAt() {
        return analyzeUpdatedAt;
    }

    public void setAnalyzeUpdatedAt(java.time.LocalDateTime analyzeUpdatedAt) {
        this.analyzeUpdatedAt = analyzeUpdatedAt;
    }

    public int getAnalyzeRecoveryCount() {
        return analyzeRecoveryCount;
    }

    public void setAnalyzeRecoveryCount(int analyzeRecoveryCount) {
        this.analyzeRecoveryCount = analyzeRecoveryCount;
    }

    public String getAnalyzeAttemptId() {
        return analyzeAttemptId;
    }

    public void setAnalyzeAttemptId(String analyzeAttemptId) {
        this.analyzeAttemptId = analyzeAttemptId;
    }

    public void setAnalyzeStatus(AsyncTaskStatus analyzeStatus) {
        this.analyzeStatus = analyzeStatus;
    }

    public String getAnalyzeError() {
        return analyzeError;
    }

    public void setAnalyzeError(String analyzeError) {
        this.analyzeError = analyzeError;
    }
}
