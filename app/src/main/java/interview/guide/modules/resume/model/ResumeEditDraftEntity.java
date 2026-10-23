package interview.guide.modules.resume.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 简历的可编辑草稿，与原始上传文件和分析记录隔离保存。 */
@Entity
@Table(name = "resume_edit_drafts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeEditDraftEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_id", nullable = false, unique = true)
    private ResumeEntity resume;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ResumeEditorSource source = ResumeEditorSource.ORIGINAL;

    @Column(length = 200)
    private String name;

    @Column(length = 200)
    private String email;

    @Column(length = 80)
    private String phone;

    @Column(length = 200)
    private String location;

    @Column(name = "target_position", length = 200)
    private String targetPosition;

    @Column(columnDefinition = "TEXT")
    private String skills;

    @Column(name = "work_experience", columnDefinition = "TEXT")
    private String workExperience;

    @Column(columnDefinition = "TEXT")
    private String projects;

    @Column(columnDefinition = "TEXT")
    private String education;

    @Column(name = "self_evaluation", columnDefinition = "TEXT")
    private String selfEvaluation;

    @Column(name = "raw_text", columnDefinition = "TEXT")
    private String rawText;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
