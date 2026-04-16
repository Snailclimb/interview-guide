package interview.guide.common.evaluation;

/**
 * 通用面试问答记录（文字面试和语音面试共用）
 */
public record QaRecord(
    int questionIndex,
    String question,
    String category,
    String userAnswer   // null 表示未回答
) {}
