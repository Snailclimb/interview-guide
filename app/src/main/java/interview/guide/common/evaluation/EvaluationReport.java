package interview.guide.common.evaluation;

import java.util.List;

/**
 * 通用面试评估报告（文字面试和语音面试共用）。
 * <p>
 * 由 AI 模型根据候选人的回答生成，包含整体评分、各维度得分、逐题评价和参考答案。
 * 用于前端展示面试结果和 PDF 报告导出。
 */
public record EvaluationReport(
    /* 面试会话 ID，关联 InterviewSessionEntity 或 VoiceInterviewSessionEntity */
    String sessionId,
    /* 本次面试的总题数 */
    int totalQuestions,
    /* 综合得分（0-100），由 AI 根据各题表现加权计算 */
    int overallScore,
    /* 各评估维度得分（如：技术能力、项目经验、沟通表达等） */
    List<CategoryScore> categoryScores,
    /* 逐题评价详情，包含候选人回答、得分和反馈 */
    List<QuestionEvaluation> questionDetails,
    /* 整体评价总结，概括候选人的综合表现 */
    String overallFeedback,
    /* 候选人优势列表（如："基础扎实"、"表达清晰"） */
    List<String> strengths,
    /* 待改进项列表（如："缺乏分布式经验"、"项目描述过于简略"） */
    List<String> improvements,
    /* 参考答案列表，供候选人对照学习 */
    List<ReferenceAnswer> referenceAnswers
) {
    /**
     * 评估维度得分。
     * <p>
     * 例如：技术能力 85 分（3 题）、项目经验 78 分（2 题）、沟通表达 90 分（1 题）
     *
     * @param category      维度名称（如"技术能力"、"项目经验"、"沟通表达"）
     * @param score         该维度的得分（0-100）
     * @param questionCount 该维度下的题目数量
     */
    public record CategoryScore(
        String category,
        int score,
        int questionCount
    ) {}

    /**
     * 单题评价详情。
     * <p>
     * 记录每道题的问题内容、候选人回答、AI 评分和反馈意见。
     *
     * @param questionIndex 题目序号（从 1 开始）
     * @param question      面试问题内容
     * @param category      题目所属维度（如"技术能力"）
     * @param userAnswer    候选人的回答内容
     * @param score         该题得分（0-100）
     * @param feedback      AI 对该回答的具体反馈
     */
    public record QuestionEvaluation(
        int questionIndex,
        String question,
        String category,
        String userAnswer,
        int score,
        String feedback
    ) {}

    /**
     * 参考答案。
     * <p>
     * 为每道题提供标准答案和关键要点，帮助候选人了解理想回答应包含的内容。
     *
     * @param questionIndex  题目序号（从 1 开始）
     * @param question       面试问题内容
     * @param referenceAnswer 参考答案全文
     * @param keyPoints      关键要点列表（如："提到索引优化"、"解释事务隔离级别"）
     */
    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
}
