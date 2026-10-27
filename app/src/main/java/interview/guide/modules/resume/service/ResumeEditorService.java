package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEditDraftEntity;
import interview.guide.modules.resume.model.ResumeEditorContent;
import interview.guide.modules.resume.model.ResumeEditorDTO;
import interview.guide.modules.resume.model.ResumeEditorSource;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.SaveResumeEditorRequest;
import interview.guide.modules.resume.repository.ResumeEditDraftRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 简历编辑版服务：草稿、AI 优化和显式应用到后续面试。 */
@Slf4j
@Service
public class ResumeEditorService {

    private final ResumeRepository resumeRepository;
    private final ResumeEditDraftRepository draftRepository;
    private final ResumePersistenceService persistenceService;
    private final LlmProviderRegistry providerRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final PromptSanitizer promptSanitizer;
    private final ObjectMapper objectMapper;
    private final PromptTemplate systemPrompt;
    private final PromptTemplate userPrompt;
    private final BeanOutputConverter<AiResumeEditorContent> outputConverter;

    private record AiResumeEditorContent(
        String name,
        String email,
        String phone,
        String location,
        String targetPosition,
        String skills,
        String workExperience,
        String projects,
        String education,
        String selfEvaluation,
        String rawText
    ) {}

    public ResumeEditorService(
        ResumeRepository resumeRepository,
        ResumeEditDraftRepository draftRepository,
        ResumePersistenceService persistenceService,
        LlmProviderRegistry providerRegistry,
        StructuredOutputInvoker structuredOutputInvoker,
        PromptSanitizer promptSanitizer,
        ObjectMapper objectMapper,
        ResourceLoader resourceLoader
    ) throws IOException {
        this.resumeRepository = resumeRepository;
        this.draftRepository = draftRepository;
        this.persistenceService = persistenceService;
        this.providerRegistry = providerRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.promptSanitizer = promptSanitizer;
        this.objectMapper = objectMapper;
        this.systemPrompt = new PromptTemplate(resourceLoader
            .getResource("classpath:prompts/resume-editor-system.st")
            .getContentAsString(StandardCharsets.UTF_8));
        this.userPrompt = new PromptTemplate(resourceLoader
            .getResource("classpath:prompts/resume-editor-user.st")
            .getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(AiResumeEditorContent.class);
    }

    @Transactional(readOnly = true)
    public ResumeEditorDTO getEditor(Long resumeId) {
        ResumeEntity resume = findResume(resumeId);
        return draftRepository.findByResumeId(resumeId)
            .map(draft -> toDto(resume, draft))
            .orElseGet(() -> new ResumeEditorDTO(
                resumeId,
                0,
                ResumeEditorSource.ORIGINAL,
                resume.getUploadedAt(),
                new ResumeEditorContent(null, null, null, null, null, null, null, null, null, null,
                    resume.getResumeText()),
                originalText(resume),
                false,
                false
            ));
    }

    @Transactional
    public ResumeEditorDTO save(Long resumeId, SaveResumeEditorRequest request) {
        ResumeEntity resume = findResume(resumeId);
        ResumeEditorContent content = requireContent(request);
        ResumeEditDraftEntity draft = draftRepository.findByResumeId(resumeId)
            .orElseGet(() -> ResumeEditDraftEntity.builder().resume(resume).version(0).build());
        copy(content, draft);
        draft.setVersion(draft.getVersion() + 1);
        draft.setSource(ResumeEditorSource.MANUAL);
        ResumeEditDraftEntity saved = draftRepository.save(draft);
        return toDto(resume, saved);
    }

    /**
     * 保存 Agent 生成的简历优化内容为独立编辑版草稿。
     *
     * <p>Agent 只写入编辑草稿的原文区，原始上传文件和已应用到面试的正文保持不变；
     * 用户可以在简历管理页继续结构化编辑，确认后再应用到后续面试。</p>
     */
    @Transactional
    public ResumeEditorDTO saveAgentDraftForOwner(Long resumeId, String ownerId, String content) {
        ResumeEntity resume = findResumeForOwner(resumeId, ownerId);
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 生成的简历内容不能为空");
        }
        if (normalized.length() > 60_000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 生成的简历内容过长");
        }
        ResumeEditDraftEntity draft = draftRepository.findByResumeId(resumeId)
            .orElseGet(() -> ResumeEditDraftEntity.builder().resume(resume).version(0).build());
        copy(new ResumeEditorContent(
            null, null, null, null, null, null, null, null, null, null, normalized
        ), draft);
        draft.setVersion(draft.getVersion() + 1);
        draft.setSource(ResumeEditorSource.AI_DRAFT);
        return toDto(resume, draftRepository.save(draft));
    }

    /** 生成并保存 AI 草稿，用户仍可在前端继续手动修改后再应用。 */
    public ResumeEditorDTO optimize(Long resumeId, SaveResumeEditorRequest request) {
        ResumeEntity resume = findResume(resumeId);
        ResumeEditorContent content = request == null ? null : request.content();
        String sourceText = content == null || content.isEmpty()
            ? resume.getResumeText()
            : composeText(content);
        if (sourceText == null || sourceText.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "没有可供优化的简历内容");
        }

        String suggestions = loadSuggestions(resumeId);
        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", promptSanitizer.wrapWithDelimiters(
            "resume-editor-input", promptSanitizer.sanitize(sourceText)));
        variables.put("suggestions", promptSanitizer.wrapWithDelimiters(
            "resume-editor-suggestions", promptSanitizer.sanitize(suggestions)));

        try {
            String system = systemPrompt.render() + "\n\n" + outputConverter.getFormat();
            String user = userPrompt.render(variables);
            ChatClient client = providerRegistry.getPlainChatClient();
            AiResumeEditorContent aiContent = structuredOutputInvoker.invoke(
                client,
                system,
                user,
                outputConverter,
                ErrorCode.RESUME_ANALYSIS_FAILED,
                "简历 AI 优化失败：",
                "简历编辑优化",
                log
            );

            ResumeEditDraftEntity draft = draftRepository.findByResumeId(resumeId)
                .orElseGet(() -> ResumeEditDraftEntity.builder().resume(resume).version(0).build());
            copy(toContent(aiContent), draft);
            draft.setVersion(draft.getVersion() + 1);
            draft.setSource(ResumeEditorSource.AI_DRAFT);
            return toDto(resume, draftRepository.save(draft));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("简历 AI 优化失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历 AI 优化失败，请稍后重试");
        }
    }

    @Transactional
    public ResumeEditorDTO applyToInterview(Long resumeId) {
        ResumeEntity resume = AuthUserContext.currentUserId()
            .map(ownerId -> resumeRepository.findByIdForUpdateAndOwnerId(resumeId, ownerId))
            .orElseGet(() -> resumeRepository.findByIdForUpdate(resumeId))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        ResumeEditDraftEntity draft = draftRepository.findByResumeId(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "还没有保存简历编辑版"));
        String text = composeText(toContent(draft));
        if (text.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历编辑版没有可应用的内容");
        }
        resume.setResumeText(text);
        resumeRepository.save(resume);
        draft.setVersion(draft.getVersion() + 1);
        draft.setSource(ResumeEditorSource.APPLIED);
        draftRepository.save(draft);
        return toDto(resume, draft);
    }

    private ResumeEntity findResume(Long resumeId) {
        Optional<ResumeEntity> resume = AuthUserContext.currentUserId()
            .map(ownerId -> resumeRepository.findByIdAndOwnerId(resumeId, ownerId))
            .orElseGet(() -> resumeRepository.findById(resumeId));
        return resume
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
    }

    private ResumeEntity findResumeForOwner(Long resumeId, String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "简历归属身份不能为空");
        }
        Optional<ResumeEntity> resume = AuthUserContext.currentUserId().isPresent()
            ? resumeRepository.findByIdAndOwnerId(resumeId, ownerId)
            : resumeRepository.findById(resumeId)
                .filter(item -> item.getOwnerId() == null || ownerId.equals(item.getOwnerId()));
        return resume.orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
    }

    private ResumeEditorContent requireContent(SaveResumeEditorRequest request) {
        if (request == null || request.content() == null || request.content().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历编辑内容不能为空");
        }
        return request.content();
    }

    private String loadSuggestions(Long resumeId) {
        Optional<ResumeAnalysisResponse> analysis = persistenceService.getLatestAnalysisAsDTO(resumeId);
        if (analysis.isEmpty() || analysis.get().suggestions() == null) {
            return "暂无分析建议，请只做事实内的结构和表达优化。";
        }
        try {
            return objectMapper.writeValueAsString(analysis.get().suggestions());
        } catch (JacksonException e) {
            log.warn("简历分析建议序列化失败，AI 优化将忽略建议", e);
            return "暂无可用分析建议，请只做事实内的结构和表达优化。";
        }
    }

    private ResumeEditorDTO toDto(ResumeEntity resume, ResumeEditDraftEntity draft) {
        return new ResumeEditorDTO(
            resume.getId(),
            draft.getVersion(),
            draft.getSource(),
            draft.getUpdatedAt(),
            toContent(draft),
            originalText(resume),
            draft.getSource() == ResumeEditorSource.APPLIED,
            isAnalysisOutdated(resume.getId(), draft)
        );
    }

    private String originalText(ResumeEntity resume) {
        return resume.getOriginalResumeText() == null
            ? resume.getResumeText()
            : resume.getOriginalResumeText();
    }

    private boolean isAnalysisOutdated(Long resumeId, ResumeEditDraftEntity draft) {
        if (draft.getSource() != ResumeEditorSource.APPLIED) {
            return false;
        }
        return persistenceService.getLatestAnalysis(resumeId)
            .map(analysis -> analysis.getAnalyzedAt() == null
                || analysis.getAnalyzedAt().isBefore(draft.getUpdatedAt()))
            .orElse(false);
    }

    private ResumeEditorContent toContent(ResumeEditDraftEntity draft) {
        return new ResumeEditorContent(
            draft.getName(), draft.getEmail(), draft.getPhone(), draft.getLocation(),
            draft.getTargetPosition(), draft.getSkills(), draft.getWorkExperience(),
            draft.getProjects(), draft.getEducation(), draft.getSelfEvaluation(), draft.getRawText()
        );
    }

    private ResumeEditorContent toContent(AiResumeEditorContent content) {
        return new ResumeEditorContent(
            content.name(), content.email(), content.phone(), content.location(),
            content.targetPosition(), content.skills(), content.workExperience(),
            content.projects(), content.education(), content.selfEvaluation(), content.rawText()
        );
    }

    private void copy(ResumeEditorContent content, ResumeEditDraftEntity draft) {
        draft.setName(trimToNull(content.name()));
        draft.setEmail(trimToNull(content.email()));
        draft.setPhone(trimToNull(content.phone()));
        draft.setLocation(trimToNull(content.location()));
        draft.setTargetPosition(trimToNull(content.targetPosition()));
        draft.setSkills(trimToNull(content.skills()));
        draft.setWorkExperience(trimToNull(content.workExperience()));
        draft.setProjects(trimToNull(content.projects()));
        draft.setEducation(trimToNull(content.education()));
        draft.setSelfEvaluation(trimToNull(content.selfEvaluation()));
        draft.setRawText(trimToNull(content.rawText()));
    }

    public static String composeText(ResumeEditorContent content) {
        if (content == null) {
            return "";
        }
        List<String> sections = new java.util.ArrayList<>();
        String basic = joinNonBlank(
            content.name(), content.email(), content.phone(), content.location(), content.targetPosition());
        addSection(sections, "基本信息", basic);
        addSection(sections, "专业技能", content.skills());
        addSection(sections, "工作经历", content.workExperience());
        addSection(sections, "项目经历", content.projects());
        addSection(sections, "教育经历", content.education());
        addSection(sections, "个人评价", content.selfEvaluation());
        if (sections.isEmpty() && content.rawText() != null) {
            return content.rawText().trim();
        }
        return String.join("\n\n", sections).trim();
    }

    private static String joinNonBlank(String... values) {
        return java.util.Arrays.stream(values)
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .reduce((left, right) -> left + " | " + right)
            .orElse("");
    }

    private static void addSection(List<String> sections, String title, String value) {
        if (value != null && !value.isBlank()) {
            sections.add("## " + title + "\n" + value.trim());
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
