package interview.guide.modules.voiceinterview.model;

/**
 * Voice Interview Session Status
 * 语音面试会话状态
 */
public enum VoiceInterviewSessionStatus {
    /**
     * In progress - WebSocket connected, interview ongoing
     */
    IN_PROGRESS,

    /**
     * Paused - User paused or timeout, state saved to DB
     */
    PAUSED,

    /**
     * Completed - Interview finished
     */
    COMPLETED,

    /**
     * Failed - Error occurred
     */
    FAILED
}
