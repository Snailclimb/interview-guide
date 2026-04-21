package interview.guide.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ========== 通用错误码 1xxx ==========
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权访问"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不允许"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // ========== 简历模块错误码 2xxx ==========
    RESUME_NOT_FOUND(2001, "简历不存在"),
    RESUME_PARSE_FAILED(2002, "简历解析失败"),
    RESUME_UPLOAD_FAILED(2003, "简历上传失败"),
    RESUME_DUPLICATE(2004, "简历重复"),
    RESUME_FILE_TYPE_NOT_SUPPORTED(2006, "文件类型不支持"),
    RESUME_ANALYSIS_FAILED(2007, "简历分析失败"),
    RESUME_ANALYSIS_NOT_FOUND(2008, "简历分析结果不存在"),

    // ========== 模拟面试错误码 3xxx ==========
    INTERVIEW_SESSION_NOT_FOUND(3001, "面试会话不存在"),
    INTERVIEW_SESSION_EXPIRED(3002, "面试会话已过期"),
    INTERVIEW_QUESTION_NOT_FOUND(3003, "面试题目不存在"),
    INTERVIEW_ALREADY_COMPLETED(3004, "面试已完成"),
    INTERVIEW_EVALUATION_FAILED(3005, "面试评估失败"),
    INTERVIEW_QUESTION_GENERATION_FAILED(3006, "面试题目生成失败"),
    INTERVIEW_NOT_COMPLETED(3007, "面试尚未完成"),

    // ========== 存储模块错误码 4xxx ==========
    STORAGE_UPLOAD_FAILED(4001, "文件上传失败"),
    STORAGE_DOWNLOAD_FAILED(4002, "文件下载失败"),
    STORAGE_DELETE_FAILED(4003, "文件删除失败"),

    // ========== 导出模块错误码 5xxx ==========
    EXPORT_PDF_FAILED(5001, "PDF 导出失败"),

    // ========== 知识库模块错误码 6xxx ==========
    KNOWLEDGE_BASE_NOT_FOUND(6001, "知识库不存在"),
    KNOWLEDGE_BASE_PARSE_FAILED(6002, "知识库文档解析失败"),
    KNOWLEDGE_BASE_QUERY_FAILED(6004, "知识库查询失败"),
    KNOWLEDGE_BASE_DELETE_FAILED(6005, "知识库删除失败"),
    KNOWLEDGE_BASE_VECTORIZATION_FAILED(6006, "知识库向量化失败"),

    // ========== AI 服务错误码 7xxx ==========
    AI_SERVICE_UNAVAILABLE(7001, "AI 服务不可用"),
    AI_SERVICE_TIMEOUT(7002, "AI 服务超时"),
    AI_SERVICE_ERROR(7003, "AI 服务异常"),
    AI_API_KEY_INVALID(7004, "AI API Key 无效"),
    AI_RATE_LIMIT_EXCEEDED(7005, "AI 服务限流"),

    // ========== 限流错误码 8xxx ==========
    RATE_LIMIT_EXCEEDED(8001, "请求过于频繁，请稍后重试"),

    // ========== 面试日程错误码 9xxx ==========
    INTERVIEW_SCHEDULE_NOT_FOUND(9001, "面试日程不存在"),

    // ========== 语音面试错误码 10xxx ==========
    VOICE_SESSION_NOT_FOUND(10001, "语音面试会话不存在"),
    VOICE_EVALUATION_FAILED(10004, "语音面试评估失败"),
    VOICE_EVALUATION_IN_PROGRESS(10005, "语音面试评估进行中"),
    VOICE_EVALUATION_NOT_FOUND(10006, "语音面试评估结果不存在"),

    // ========== 面试复盘错误码 11xxx ==========
    REVIEW_NOT_FOUND(11001, "面试复盘不存在"),
    ARTIFACT_NOT_FOUND(11002, "复盘分析结果不存在"),
    REVIEW_UPLOAD_FAILED(11003, "面试复盘上传失败"),
    REVIEW_ANALYSIS_FAILED(11004, "面试复盘分析失败");

    private final Integer code;
    private final String message;
}
