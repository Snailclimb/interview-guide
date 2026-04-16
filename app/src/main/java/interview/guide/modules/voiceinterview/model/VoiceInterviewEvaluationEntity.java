package interview.guide.modules.voiceinterview.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Voice Interview Evaluation Entity
 * 语音面试评估实体
 * <p>
 * Stores evaluation results in a format aligned with text-based interviews:
 * per-question evaluations, overall feedback, strengths, improvements, and reference answers.
 * All structured data (arrays/objects) is stored as JSON TEXT columns.
 * </p>
 */
@Entity
@Table(name = "voice_interview_evaluations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", unique = true)
    private Long sessionId;

    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(name = "overall_feedback", columnDefinition = "TEXT")
    private String overallFeedback;

    @Column(name = "question_evaluations_json", columnDefinition = "TEXT")
    private String questionEvaluationsJson;

    @Column(name = "strengths_json", columnDefinition = "TEXT")
    private String strengthsJson;

    @Column(name = "improvements_json", columnDefinition = "TEXT")
    private String improvementsJson;

    @Column(name = "reference_answers_json", columnDefinition = "TEXT")
    private String referenceAnswersJson;

    @Column(name = "interviewer_role")
    private String interviewerRole;

    @Column(name = "interview_date")
    private LocalDateTime interviewDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
