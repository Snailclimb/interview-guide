package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Voice evaluation status response DTO
 * 语音面试评估状态响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationStatusDTO {

    /**
     * Evaluation task status: PENDING / PROCESSING / COMPLETED / FAILED
     */
    private String evaluateStatus;

    /**
     * Error message when status is FAILED
     */
    private String evaluateError;

    /**
     * Full evaluation result, only present when status is COMPLETED
     */
    private VoiceEvaluationDetailDTO evaluation;
}
