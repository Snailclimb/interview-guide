package interview.guide.modules.voiceinterview.repository;

import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 语音面试评估Repository
 */
@Repository
public interface VoiceInterviewEvaluationRepository extends JpaRepository<VoiceInterviewEvaluationEntity, Long> {

    /**
     * 根据会话ID查找评估结果（一对一关系）
     */
    Optional<VoiceInterviewEvaluationEntity> findBySessionId(Long sessionId);
}
