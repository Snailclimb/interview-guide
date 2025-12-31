package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO.QuestionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试问题生成服务
 * 基于简历内容生成针对性的面试问题
 */
@Service
public class InterviewQuestionService {
    
    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);
    
    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<QuestionListDTO> outputConverter;
    
    // 问题类型权重分配（按优先级）
    private static final double PROJECT_RATIO = 0.20;      // 20% 项目经历
    private static final double MYSQL_RATIO = 0.20;        // 20% MySQL
    private static final double REDIS_RATIO = 0.20;        // 20% Redis
    private static final double JAVA_BASIC_RATIO = 0.10;   // 10% Java基础
    private static final double JAVA_COLLECTION_RATIO = 0.10; // 10% 集合
    private static final double JAVA_CONCURRENT_RATIO = 0.10; // 10% 并发
    private static final double SPRING_RATIO = 0.10;       // 10% Spring/SpringBoot
    
    // 中间DTO用于接收AI响应
    private record QuestionListDTO(
        List<QuestionDTO> questions
    ) {}
    
    private record QuestionDTO(
        String question,
        String type,
        String category
    ) {}
    
    public InterviewQuestionService(
            ChatClient.Builder chatClientBuilder,
            @Value("classpath:prompts/interview-question-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/interview-question-user.st") Resource userPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
    }
    
    /**
     * 生成面试问题
     * 
     * @param resumeText 简历文本
     * @param questionCount 问题数量
     * @return 面试问题列表
     */
    public List<InterviewQuestionDTO> generateQuestions(String resumeText, int questionCount) {
        log.info("开始生成面试问题，简历长度: {}, 问题数量: {}", resumeText.length(), questionCount);
        
        // 计算各类型问题数量
        QuestionDistribution distribution = calculateDistribution(questionCount);
        
        try {
            // 加载系统提示词
            String systemPrompt = systemPromptTemplate.render();
            
            // 加载用户提示词并填充变量
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("projectCount", distribution.project);
            variables.put("mysqlCount", distribution.mysql);
            variables.put("redisCount", distribution.redis);
            variables.put("javaBasicCount", distribution.javaBasic);
            variables.put("javaCollectionCount", distribution.javaCollection);
            variables.put("javaConcurrentCount", distribution.javaConcurrent);
            variables.put("springCount", distribution.spring);
            variables.put("resumeText", resumeText);
            String userPrompt = userPromptTemplate.render(variables);
            
            // 添加格式指令到系统提示词
            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
            
            // 调用AI
            QuestionListDTO dto;
            try {
                dto = chatClient.prompt()
                    .system(systemPromptWithFormat)
                    .user(userPrompt)
                    .call()
                    .entity(outputConverter);
                log.debug("AI响应解析成功: questions count={}", dto.questions().size());
            } catch (Exception e) {
                log.error("面试问题生成AI调用失败: {}", e.getMessage(), e);
                throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, 
                    "面试问题生成失败：" + e.getMessage());
            }
            
            // 转换为业务对象
            List<InterviewQuestionDTO> questions = convertToQuestions(dto);
            log.info("成功生成 {} 个面试问题", questions.size());
            
            return questions;
            
        } catch (Exception e) {
            log.error("生成面试问题失败: {}", e.getMessage(), e);
            // 返回默认问题集
            return generateDefaultQuestions(questionCount);
        }
    }
    
    /**
     * 计算各类型问题分布
     */
    private QuestionDistribution calculateDistribution(int total) {
        int project = Math.max(1, (int) Math.round(total * PROJECT_RATIO));
        int mysql = Math.max(1, (int) Math.round(total * MYSQL_RATIO));
        int redis = Math.max(1, (int) Math.round(total * REDIS_RATIO));
        int javaBasic = Math.max(1, (int) Math.round(total * JAVA_BASIC_RATIO));
        int javaCollection = (int) Math.round(total * JAVA_COLLECTION_RATIO);
        int javaConcurrent = (int) Math.round(total * JAVA_CONCURRENT_RATIO);
        int spring = total - project - mysql - redis - javaBasic - javaCollection - javaConcurrent;
        
        // 确保至少有1个
        spring = Math.max(0, spring);
        
        return new QuestionDistribution(project, mysql, redis, javaBasic, javaCollection, javaConcurrent, spring);
    }
    
    private record QuestionDistribution(
        int project, int mysql, int redis, 
        int javaBasic, int javaCollection, int javaConcurrent, int spring
    ) {}
    
    /**
     * 转换DTO为业务对象
     */
    private List<InterviewQuestionDTO> convertToQuestions(QuestionListDTO dto) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;
        
        for (QuestionDTO q : dto.questions()) {
            QuestionType type = parseQuestionType(q.type());
            questions.add(InterviewQuestionDTO.create(index++, q.question(), type, q.category()));
        }
        
        return questions;
    }
    
    private QuestionType parseQuestionType(String typeStr) {
        try {
            return QuestionType.valueOf(typeStr.toUpperCase());
        } catch (Exception e) {
            return QuestionType.JAVA_BASIC;
        }
    }
    
    /**
     * 生成默认问题（备用）
     */
    private List<InterviewQuestionDTO> generateDefaultQuestions(int count) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        
        String[][] defaultQuestions = {
            {"请介绍一下你在简历中提到的最重要的项目，你在其中承担了什么角色？", "PROJECT", "项目经历"},
            {"MySQL的索引有哪些类型？B+树索引的原理是什么？", "MYSQL", "MySQL"},
            {"Redis支持哪些数据结构？各自的使用场景是什么？", "REDIS", "Redis"},
            {"Java中HashMap的底层实现原理是什么？JDK8做了哪些优化？", "JAVA_COLLECTION", "Java集合"},
            {"synchronized和ReentrantLock有什么区别？", "JAVA_CONCURRENT", "Java并发"},
            {"Spring的IoC和AOP原理是什么？", "SPRING", "Spring"},
            {"MySQL事务的ACID特性是什么？隔离级别有哪些？", "MYSQL", "MySQL"},
            {"Redis的持久化机制有哪些？RDB和AOF的区别？", "REDIS", "Redis"},
            {"Java的垃圾回收机制是怎样的？常见的GC算法有哪些？", "JAVA_BASIC", "Java基础"},
            {"线程池的核心参数有哪些？如何合理配置？", "JAVA_CONCURRENT", "Java并发"},
        };
        
        for (int i = 0; i < Math.min(count, defaultQuestions.length); i++) {
            questions.add(InterviewQuestionDTO.create(
                i,
                defaultQuestions[i][0],
                QuestionType.valueOf(defaultQuestions[i][1]),
                defaultQuestions[i][2]
            ));
        }
        
        return questions;
    }
}
