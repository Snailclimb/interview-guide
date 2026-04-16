package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Session metadata for list display
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetaDTO {
    private Long sessionId;
    private String roleType;
    private String status;
    private String currentPhase;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer actualDuration;
    private Long messageCount;
    private String evaluateStatus;
    private String evaluateError;
}
