package interview.guide.common.constant;

/**
 * 通用常量定义
 */
public final class CommonConstants {
    
    private CommonConstants() {}
    
    /**
     * 状态码
     */
    public static final class StatusCode {
        public static final int SUCCESS = 200;
        public static final int BAD_REQUEST = 400;
        public static final int UNAUTHORIZED = 401;
        public static final int FORBIDDEN = 403;
        public static final int NOT_FOUND = 404;
        public static final int SERVER_ERROR = 500;
        
        private StatusCode() {}
    }
    
    /**
     * 分页默认值
     */
    public static final class Pagination {
        public static final int DEFAULT_PAGE = 1;
        public static final int DEFAULT_SIZE = 20;
        public static final int MAX_SIZE = 100;

        private Pagination() {}
    }

    /**
     * 面试默认值
     */
    public static final class InterviewDefaults {
        public static final String SKILL_ID = "java-backend";
        public static final String DIFFICULTY = "mid";
        public static final String LLM_PROVIDER = "dashscope";

        private InterviewDefaults() {}
    }
}
