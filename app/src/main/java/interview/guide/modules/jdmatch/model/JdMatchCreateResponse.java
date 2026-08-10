package interview.guide.modules.jdmatch.model;

/**
 * 创建 JD 匹配分析响应
 */
public record JdMatchCreateResponse(
    // 分析ID（可用于轮询状态）
    Long id,

    // 初始状态（PENDING）
    String status
) {
}
