package interview.guide.modules.review.model;

import interview.guide.common.model.AsyncTaskStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "review_artifact",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_review_artifact_review_type",
        columnNames = {"review_id", "type"}
    )
)
@Data
public class ReviewArtifactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private InterviewReviewEntity review;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ArtifactType type;

    @Column(columnDefinition = "TEXT")
    private String content;

    private Integer version = 1;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AsyncTaskStatus status;

    @Column(length = 500)
    private String error;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
