package interview.guide.common.evaluation;

/**
 * 单题问答记录，用于将面试问答数据传递给 AI 评估模型。
 * <p>
 * 封装一道面试题的完整上下文：题目序号、问题内容、所属维度和候选人回答。
 * 作为 {@link EvaluationReport} 评估流程的输入数据载体。
 *
 * @param questionIndex 题目序号（从 1 开始），用于保持题目顺序
 * @param question      面试问题内容
 * @param category      题目所属评估维度（如"技术能力"、"项目经验"、"沟通表达"）
 * @param userAnswer    候选人的回答内容；null 表示候选人未回答该题
 */
public record QaRecord(
    int questionIndex,
    String question,
    String category,
    String userAnswer
) {}
