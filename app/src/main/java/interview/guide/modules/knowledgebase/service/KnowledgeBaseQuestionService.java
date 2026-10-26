package interview.guide.modules.knowledgebase.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.listener.QuestionGenStreamProducer;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.model.QuestionGenStatusResponse;
import interview.guide.modules.knowledgebase.model.QuestionGenerationConfig;
import interview.guide.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQuestionService {

  private static final int DEFAULT_FOLLOW_UP_COUNT = 2;
  private static final int DEFAULT_CATEGORY_LIMIT = 3;
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
  };
  private static final TypeReference<List<KnowledgeBaseQuestionFollowUpDTO>> FOLLOW_UP_LIST_TYPE =
      new TypeReference<>() {
      };

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final KnowledgeBaseQuestionRepository questionRepository;
  private final ObjectMapper objectMapper;
  private final QuestionGenStreamProducer questionGenStreamProducer;
  private final QuestionGenerationStateService questionGenerationStateService;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private KnowledgeBaseAccessService accessService;

  @Transactional(readOnly = true)
  public List<KnowledgeBaseQuestionDTO> listQuestions(Long knowledgeBaseId,
                                                      KnowledgeBaseQuestionStatus status,
                                                      String category,
                                                      String difficulty,
                                                      String keyword) {
    requireReadableIfAuthenticated(knowledgeBaseId);
    List<KnowledgeBaseQuestionEntity> questions = status == null
        ? questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(knowledgeBaseId)
        : questionRepository.findByKnowledgeBase_IdAndStatusOrderByUpdatedAtDesc(knowledgeBaseId, status);
    String categoryFilter = trimToNull(category);
    String difficultyFilter = trimToNull(difficulty);
    String keywordFilter = trimToNull(keyword);
    return questions.stream()
        .filter(q -> categoryFilter == null || categoryFilter.equals(q.getCategory()))
        .filter(q -> difficultyFilter == null || difficultyFilter.equals(q.getDifficulty()))
        .filter(q -> keywordFilter == null || containsKeyword(q, keywordFilter))
        .map(this::toDTO)
        .toList();
  }

  /**
   * 列出某知识库下出现过的方向（含题目计数），按出现频次降序。
   * 用于前端筛选下拉、开始面试弹窗的方向选择。
   */
  @Transactional(readOnly = true)
  public List<CategoryCount> listCategories(Long knowledgeBaseId) {
    requireReadableIfAuthenticated(knowledgeBaseId);
    return questionRepository.findCategoryCounts(knowledgeBaseId);
  }

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseQuestionDTO createQuestion(Long knowledgeBaseId,
                                                 CreateKnowledgeBaseQuestionRequest request) {
    KnowledgeBaseEntity kb = getKnowledgeBase(knowledgeBaseId);
    KnowledgeBaseQuestionEntity question = new KnowledgeBaseQuestionEntity();
    question.setKnowledgeBase(kb);
    applyCreateRequest(question, request);
    question.setKbContentHash(kb.getFileHash());
    question.setStatus(request.status() != null ? request.status() : KnowledgeBaseQuestionStatus.DRAFT);
    return toDTO(questionRepository.save(question));
  }

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseQuestionDTO updateQuestion(Long questionId, UpdateKnowledgeBaseQuestionRequest request) {
    KnowledgeBaseQuestionEntity question = getQuestion(questionId);
    if (request.difficulty() != null) {
      question.setDifficulty(normalizeDifficulty(request.difficulty()));
    }
    if (request.type() != null) {
      question.setType(trimToNull(request.type()));
    }
    if (request.category() != null) {
      if (request.category().isBlank()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "面试方向不能为空");
      }
      question.setCategory(request.category().trim());
    }
    if (request.question() != null) {
      if (request.question().isBlank()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "题干不能为空");
      }
      question.setQuestion(request.question().trim());
    }
    if (request.topicSummary() != null) {
      question.setTopicSummary(trimToNull(request.topicSummary()));
    }
    if (request.referenceAnswer() != null) {
      question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
    }
    if (request.keyPoints() != null) {
      question.setKeyPointsJson(writeStringList(request.keyPoints()));
    }
    if (request.scoringRubric() != null) {
      question.setScoringRubric(trimToNull(request.scoringRubric()));
    }
    if (request.followUps() != null) {
      question.setFollowUpsJson(writeFollowUps(request.followUps()));
    }
    if (request.sourceContext() != null) {
      question.setSourceContext(trimToNull(request.sourceContext()));
    }
    if (request.status() != null) {
      question.setStatus(request.status());
    }
    return toDTO(questionRepository.save(question));
  }

  @Transactional(rollbackFor = Exception.class)
  public KnowledgeBaseQuestionDTO updateStatus(Long questionId, KnowledgeBaseQuestionStatus status) {
    KnowledgeBaseQuestionEntity question = getQuestion(questionId);
    question.setStatus(status);
    return toDTO(questionRepository.save(question));
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteQuestion(Long questionId) {
    getQuestion(questionId);
    questionRepository.deleteById(questionId);
  }

  /**
   * 提交异步问题生成任务。
   * 校验知识库存在且状态允许，更新状态为 QUEUED，然后向 Redis Stream 投递任务。
   */
  public QuestionGenStatusResponse submitGenerationTask(
      Long knowledgeBaseId,
      GenerateKnowledgeBaseQuestionsRequest request) {
    requireReadableIfAuthenticated(knowledgeBaseId);
    String difficulty = normalizeDifficulty(request.difficulty());
    int followUpCount = request.followUpCount() == null
        ? DEFAULT_FOLLOW_UP_COUNT
        : Math.max(0, Math.min(request.followUpCount(), 5));
    int categoryLimit = request.categoryLimit() == null
        ? DEFAULT_CATEGORY_LIMIT
        : Math.max(1, Math.min(request.categoryLimit(), 5));
    QuestionGenerationConfig config = new QuestionGenerationConfig(
        difficulty,
        Math.max(1, request.questionCount()),
        followUpCount,
        categoryLimit,
        trimToNull(request.llmProvider())
    );
    QuestionGenStatusResponse response =
        questionGenerationStateService.createTask(knowledgeBaseId, config);
    boolean sent = questionGenStreamProducer.sendGenerateTask(
        knowledgeBaseId, response.questionGenTaskId());
    return sent ? response : questionGenerationStateService.getStatus(knowledgeBaseId);
  }

  @Transactional(readOnly = true)
  public QuestionGenStatusResponse getGenerationStatus(Long knowledgeBaseId) {
    requireReadableIfAuthenticated(knowledgeBaseId);
    return questionGenerationStateService.getStatus(knowledgeBaseId);
  }

  private void applyCreateRequest(KnowledgeBaseQuestionEntity question,
                                  CreateKnowledgeBaseQuestionRequest request) {
    question.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
    question.setDifficulty(normalizeDifficulty(request.difficulty()));
    question.setType(trimToNull(request.type()));
    question.setCategory(normalizeCategory(request.category(), null));
    question.setQuestion(request.question().trim());
    question.setTopicSummary(trimToNull(request.topicSummary()));
    question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
    question.setKeyPointsJson(writeStringList(request.keyPoints()));
    question.setScoringRubric(trimToNull(request.scoringRubric()));
    question.setFollowUpsJson(writeFollowUps(request.followUps()));
    question.setSourceContext(trimToNull(request.sourceContext()));
  }

  private KnowledgeBaseQuestionDTO toDTO(KnowledgeBaseQuestionEntity question) {
    // FK 列声明为 insertable=false/updatable=false，save 后不一定回填；
    // 优先取关联实体的 ID（saveAll 后 KnowledgeBase 已在持久态），FK 列只做兜底
    Long knowledgeBaseId = question.getKnowledgeBase() != null && question.getKnowledgeBase().getId() != null
        ? question.getKnowledgeBase().getId()
        : question.getKnowledgeBaseId();
    return new KnowledgeBaseQuestionDTO(
        question.getId(),
        knowledgeBaseId,
        question.getKnowledgeBase() != null ? question.getKnowledgeBase().getName() : null,
        question.getSkillId(),
        question.getDifficulty(),
        question.getType(),
        question.getCategory(),
        question.getQuestion(),
        question.getTopicSummary(),
        question.getReferenceAnswer(),
        readStringList(question.getKeyPointsJson()),
        question.getScoringRubric(),
        readFollowUps(question.getFollowUpsJson()),
        question.getSourceContext(),
        question.getStatus(),
        question.getCreatedAt(),
        question.getUpdatedAt()
    );
  }

  private KnowledgeBaseEntity getKnowledgeBase(Long knowledgeBaseId) {
    KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    if (accessService != null) {
      accessService.requireModifiable(knowledgeBase);
    }
    return knowledgeBase;
  }

  private KnowledgeBaseQuestionEntity getQuestion(Long questionId) {
    KnowledgeBaseQuestionEntity question = questionRepository.findById(questionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND));
    if (accessService != null && accessService.hasAuthenticatedUser()) {
      KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(question.getKnowledgeBaseId())
          .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
      accessService.requireModifiable(knowledgeBase);
    }
    return question;
  }

  private void requireReadableIfAuthenticated(Long knowledgeBaseId) {
    if (accessService == null || !accessService.hasAuthenticatedUser()) {
      return;
    }
    KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    accessService.requireReadable(knowledgeBase);
  }

  private boolean containsKeyword(KnowledgeBaseQuestionEntity question, String keyword) {
    String lower = keyword.toLowerCase(Locale.ROOT);
    return contains(question.getQuestion(), lower)
        || contains(question.getReferenceAnswer(), lower)
        || contains(question.getScoringRubric(), lower)
        || contains(question.getTopicSummary(), lower)
        || contains(question.getCategory(), lower);
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
  }

  private List<String> readStringList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, STRING_LIST_TYPE);
    } catch (JacksonException e) {
      log.warn("解析题目字符串列表失败: {}", e.getMessage());
      return List.of();
    }
  }

  private List<KnowledgeBaseQuestionFollowUpDTO> readFollowUps(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(value, FOLLOW_UP_LIST_TYPE);
    } catch (JacksonException e) {
      log.warn("解析追问列表失败: {}", e.getMessage());
      return List.of();
    }
  }

  private String writeStringList(List<String> values) {
    try {
      List<String> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && !value.isBlank())
          .map(String::trim)
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目列表字段失败", e);
    }
  }

  private String writeFollowUps(List<KnowledgeBaseQuestionFollowUpDTO> values) {
    try {
      List<KnowledgeBaseQuestionFollowUpDTO> sanitized = values == null ? List.of() : values.stream()
          .filter(value -> value != null && value.question() != null && !value.question().isBlank())
          .map(value -> new KnowledgeBaseQuestionFollowUpDTO(
              value.question().trim(),
              trimToNull(value.referenceAnswer()),
              value.keyPoints() == null ? List.of() : value.keyPoints().stream()
                  .filter(item -> item != null && !item.isBlank())
                  .map(String::trim)
                  .toList(),
              trimToNull(value.scoringRubric())
          ))
          .toList();
      return objectMapper.writeValueAsString(sanitized);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化追问字段失败", e);
    }
  }

  private String normalizeDifficulty(String difficulty) {
    if (difficulty == null || difficulty.isBlank()) {
      return InterviewDefaults.DIFFICULTY;
    }
    return difficulty.trim();
  }

  /**
   * 归一化方向名：模型输出兜底。
   * - 空白时用知识库名（生成场景）或"未分类"（手动创建场景）
   * - 否则去掉首尾空白
   */
  private String normalizeCategory(String category, String fallback) {
    if (category == null || category.isBlank()) {
      return fallback != null && !fallback.isBlank() ? fallback.trim() : "未分类";
    }
    return category.trim();
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

}
