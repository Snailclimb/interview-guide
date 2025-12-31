package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    
    private final ChatClient chatClient;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    
    public KnowledgeBaseQueryService(
            ChatClient.Builder chatClientBuilder,
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService) {
        this.chatClient = chatClientBuilder.build();
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.vectorService = vectorService;
        this.listService = listService;
    }
    
    /**
     * 基于单个知识库回答用户问题
     * 
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }
    
    /**
     * 基于多个知识库回答用户问题（RAG）
     * 
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        
        // 1. 验证知识库是否存在并更新问题计数（合并数据库操作）
        updateQuestionCounts(knowledgeBaseIds);
        
        // 2. 使用向量搜索检索相关文档（RAG）
        List<Document> relevantDocs = vectorService.similaritySearch(question, knowledgeBaseIds, 5);
        
        if (relevantDocs.isEmpty()) {
            return "抱歉，在选定的知识库中没有找到相关信息。请尝试调整问题或选择其他知识库。";
        }
        
        // 3. 构建上下文（合并检索到的文档）
        String context = relevantDocs.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n---\n\n"));
        
        log.debug("检索到 {} 个相关文档片段", relevantDocs.size());
        
        // 4. 构建提示词
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question, knowledgeBaseIds);
        
        try {
            // 5. 调用AI生成回答
            String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
            
            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;
            
        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }
    
    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return """
            你是一个专业的知识库问答助手。你的任务是基于提供的知识库内容，准确、详细地回答用户的问题。
            
            要求：
            1. 只基于提供的知识库内容回答问题，不要编造信息
            2. 如果知识库中没有相关信息，明确告知用户
            3. 回答要清晰、有条理，尽量引用知识库中的具体内容
            4. 如果问题涉及多个方面，请分点说明
            5. 如果涉及多个知识库，需要综合多个知识库的信息
            6. 使用中文回答
            
            **格式要求（非常重要）**：
            - 使用标准 Markdown 格式
            - 每个段落之间必须空一行
            - 使用数字列表（1. 2. 3.）或无序列表（- ）来组织要点
            - 每个列表项独立成行
            - 重要内容使用 **加粗** 标记
            - 代码或技术名词使用 `反引号` 包裹
            - 段落不要过长，适当分段，提高可读性
            """;
    }
    
    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question, List<Long> knowledgeBaseIds) {
        String kbInfo = knowledgeBaseIds.size() == 1 
            ? "知识库ID: " + knowledgeBaseIds.get(0)
            : "知识库ID: " + knowledgeBaseIds;
        
        return String.format("""
            相关文档内容（来自向量检索）：
            %s
            
            用户问题：
            %s
            
            请基于上述知识库内容回答用户的问题。如果知识库中没有相关信息，请明确说明。
            """, context, question);
    }
    
    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());
        
        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);
        
        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().get(0);
        
        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }
    
    /**
     * 流式查询知识库（SSE）
     * 
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库流式提问: kbIds={}, question={}", knowledgeBaseIds, question);
        
        try {
            // 1. 验证知识库是否存在并更新问题计数
            updateQuestionCounts(knowledgeBaseIds);
            
            // 2. 使用向量搜索检索相关文档
            List<Document> relevantDocs = vectorService.similaritySearch(question, knowledgeBaseIds, 5);
            
            if (relevantDocs.isEmpty()) {
                return Flux.just("抱歉，在选定的知识库中没有找到相关信息。请尝试调整问题或选择其他知识库。");
            }
            
            // 3. 构建上下文
            String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
            
            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());
            
            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question, knowledgeBaseIds);
            
            // 5. 流式调用AI生成回答
            Flux<String> responseFlux = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content();
            
            log.info("开始流式输出知识库回答: kbIds={}", knowledgeBaseIds);
            
            return responseFlux
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });
                
        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }
    
    /**
     * 批量更新知识库问题计数（合并数据库操作）
     */
    @Transactional(rollbackFor = Exception.class)
    private void updateQuestionCounts(List<Long> knowledgeBaseIds) {
        for (Long kbId : knowledgeBaseIds) {
            KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + kbId));
            kb.incrementQuestionCount();
            knowledgeBaseRepository.save(kb);
        }
    }
    
}

