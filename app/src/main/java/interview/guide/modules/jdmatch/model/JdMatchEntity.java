package interview.guide.modules.jdmatch.model;

import interview.guide.common.model.AsyncTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 简历与岗位JD匹配分析实体
 * 保存一次"简历 x JD"匹配分析的结果与状态
 */
@Entity
@Table(name = "jd_match_analyses")
public class JdMatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 关联的简历ID（快照，不建立外键，简历删除后历史分析仍可查看）
    @Column(nullable = false)
    private Long resumeId;

    // 简历文件名快照
    @Column(nullable = false, length = 255)
    private String resumeFilename;

    // JD 标题（岗位名称，可选）
    @Column(length = 255)
    private String jdTitle;

    // JD 原文
    @Column(columnDefinition = "TEXT", nullable = false)
    private String jdText;

    // 匹配总分 (0-100)
    private Integer overallScore;

    // 各维度评分
    private Integer hardRequirementScore;  // 硬性要求匹配度 (0-25)
    private Integer skillMatchScore;       // 技能栈匹配度 (0-25)
    private Integer experienceScore;       // 经验与业务匹配度 (0-20)
    private Integer projectScore;          // 项目经历匹配度 (0-20)
    private Integer softSkillScore;        // 软素质匹配度 (0-10)

    // 匹配结论摘要
    @Column(columnDefinition = "TEXT")
    private String summary;

    // JD 核心要求清单 (JSON格式)
    @Column(columnDefinition = "TEXT")
    private String jdRequirementsJson;

    // 匹配优势点列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    private String strengthsJson;

    // 差距与风险列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    private String gapsJson;

    // 针对该JD的简历优化建议列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    private String suggestionsJson;

    // 可能被追问的面试问题列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    private String interviewQuestionsJson;

    // 分析状态（PENDING -> PROCESSING -> COMPLETED / FAILED）
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AsyncTaskStatus status = AsyncTaskStatus.PENDING;

    // 失败原因
    @Column(length = 500)
    private String error;

    // 创建时间
    @Column(nullable = false)
    private LocalDateTime createdAt;

    // 更新时间
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public String getResumeFilename() {
        return resumeFilename;
    }

    public void setResumeFilename(String resumeFilename) {
        this.resumeFilename = resumeFilename;
    }

    public String getJdTitle() {
        return jdTitle;
    }

    public void setJdTitle(String jdTitle) {
        this.jdTitle = jdTitle;
    }

    public String getJdText() {
        return jdText;
    }

    public void setJdText(String jdText) {
        this.jdText = jdText;
    }

    public Integer getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }

    public Integer getHardRequirementScore() {
        return hardRequirementScore;
    }

    public void setHardRequirementScore(Integer hardRequirementScore) {
        this.hardRequirementScore = hardRequirementScore;
    }

    public Integer getSkillMatchScore() {
        return skillMatchScore;
    }

    public void setSkillMatchScore(Integer skillMatchScore) {
        this.skillMatchScore = skillMatchScore;
    }

    public Integer getExperienceScore() {
        return experienceScore;
    }

    public void setExperienceScore(Integer experienceScore) {
        this.experienceScore = experienceScore;
    }

    public Integer getProjectScore() {
        return projectScore;
    }

    public void setProjectScore(Integer projectScore) {
        this.projectScore = projectScore;
    }

    public Integer getSoftSkillScore() {
        return softSkillScore;
    }

    public void setSoftSkillScore(Integer softSkillScore) {
        this.softSkillScore = softSkillScore;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getJdRequirementsJson() {
        return jdRequirementsJson;
    }

    public void setJdRequirementsJson(String jdRequirementsJson) {
        this.jdRequirementsJson = jdRequirementsJson;
    }

    public String getStrengthsJson() {
        return strengthsJson;
    }

    public void setStrengthsJson(String strengthsJson) {
        this.strengthsJson = strengthsJson;
    }

    public String getGapsJson() {
        return gapsJson;
    }

    public void setGapsJson(String gapsJson) {
        this.gapsJson = gapsJson;
    }

    public String getSuggestionsJson() {
        return suggestionsJson;
    }

    public void setSuggestionsJson(String suggestionsJson) {
        this.suggestionsJson = suggestionsJson;
    }

    public String getInterviewQuestionsJson() {
        return interviewQuestionsJson;
    }

    public void setInterviewQuestionsJson(String interviewQuestionsJson) {
        this.interviewQuestionsJson = interviewQuestionsJson;
    }

    public AsyncTaskStatus getStatus() {
        return status;
    }

    public void setStatus(AsyncTaskStatus status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
