package interview.guide.modules.interview.skill;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试方向 Skill 管理：分类分配、References 注入、自定义 Skill 构建。
 *
 * 与 SkillsTool 互补：SkillsTool 负责 LLM 按需加载 persona（SKILL.md body），
 * 本类负责后端解析分类配置（skill.meta.yml）并批量注入 references 到 Prompt。
 */
@Slf4j
@Service
public class InterviewSkillService {

    public static final String CUSTOM_SKILL_ID = "custom";

    private static final int MIN_JD_LENGTH = 50;

    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$");
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
    private static final String SKILL_META_FILE = "skill.meta.yml";
    private static final String JD_PARSE_SYSTEM_PROMPT_PATH = "classpath:prompts/jd-parse-system.st";

    private static final int MAX_REFERENCE_SECTION_CHARS = 12000;
    private static final int MAX_EVALUATION_REFERENCE_SECTION_CHARS = 6000;
    private static final int MAX_SINGLE_REFERENCE_CHARS = 3000;

    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final BeanOutputConverter<CategoryListDTO> jdOutputConverter;
    private final PromptTemplate jdSystemPromptTemplate;
    private final ResourceLoader resourceLoader;

    /** 预设 Skill 注册表，启动时从 classpath:skills/{skillId}/SKILL.md 加载 */
    private final Map<String, InterviewSkillProperties.SkillDefinition> presetRegistry = new TreeMap<>();

    /** 参考内容缓存（classpath 资源不可变，加载一次后复用） */
    private final Map<String, String> referenceCache = new ConcurrentHashMap<>();

    /** 全局 category key → (ref文件名, 是否shared) 映射，启动时构建，之后只读 */
    private final Map<String, RefMapping> categoryRefIndex = new HashMap<>();

    /** JD 解析用的参考文件清单 Markdown 表格，启动时生成一次 */
    private String cachedReferenceFileList;

    record RefMapping(String ref, boolean shared, String sourceSkillId) {}

    public InterviewSkillService(LlmProviderRegistry llmProviderRegistry,
                                 StructuredOutputInvoker structuredOutputInvoker,
                                 ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.jdOutputConverter = new BeanOutputConverter<>(CategoryListDTO.class) {};
        this.jdSystemPromptTemplate = new PromptTemplate(loadClasspathPrompt(JD_PARSE_SYSTEM_PROMPT_PATH));
    }

    @PostConstruct
    void loadPresetSkills() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
        Yaml yaml = new Yaml();

        for (Resource resource : resources) {
            String skillId = extractSkillId(resource);
            if (skillId == null || "_shared".equals(skillId)) {
                continue;
            }

            InterviewSkillProperties.SkillDefinition def = parseSkillDefinition(skillId, resource, yaml);
            if (def.getName() == null || def.getName().isBlank()) {
                log.warn("跳过无效 Skill（缺少 name）: {}", skillId);
                continue;
            }

            presetRegistry.put(skillId, def);
            log.info("加载预设 Skill: {} ({})", skillId, def.getName());
        }

        log.info("共加载 {} 个预设 Skill", presetRegistry.size());

        buildCategoryRefIndex();
        cachedReferenceFileList = buildReferenceFileList();
    }

    private void buildCategoryRefIndex() {
        categoryRefIndex.clear();
        for (var entry : presetRegistry.entrySet()) {
            InterviewSkillProperties.SkillDefinition def = entry.getValue();
            if (def.getCategories() == null) continue;
            for (InterviewSkillProperties.CategoryDef cat : def.getCategories()) {
                if (cat.getRef() != null && !cat.getRef().isBlank() && cat.getKey() != null) {
                    categoryRefIndex.putIfAbsent(cat.getKey(),
                        new RefMapping(cat.getRef(), Boolean.TRUE.equals(cat.getShared()), entry.getKey()));
                }
            }
        }
        log.info("构建 category→reference 映射: {} 个条目", categoryRefIndex.size());
    }

    public List<SkillDTO> getAllSkills() {
        return presetRegistry.entrySet().stream()
            .map(e -> toSkillDTO(e.getKey(), e.getValue()))
            .toList();
    }

    public SkillDTO getSkill(String skillId) {
        InterviewSkillProperties.SkillDefinition preset = presetRegistry.get(skillId);
        if (preset != null) {
            return toSkillDTO(skillId, preset);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "未找到面试主题: " + skillId);
    }

    /**
     * 从 JD 解析结果构建自定义 SkillDTO。
     * 遍历 customCategories，尝试在 categoryRefIndex 中匹配参考文件。
     */
    public SkillDTO buildCustomSkill(List<CategoryDTO> customCategories, String jdText) {
        List<SkillCategoryDTO> categories = customCategories.stream()
            .map(cat -> {
                RefMapping refMapping = categoryRefIndex.get(cat.key());
                if (refMapping != null) {
                    if (!refMapping.ref().equals(cat.ref())
                        || refMapping.shared() != Boolean.TRUE.equals(cat.shared())) {
                        log.info("JD 分类 reference 已按本地映射纠正: key={}, modelRef={}, modelShared={}, mappedRef={}, mappedShared={}",
                            cat.key(), cat.ref(), cat.shared(), refMapping.ref(), refMapping.shared());
                    }
                    return new SkillCategoryDTO(cat.key(), cat.label(), cat.priority(),
                        refMapping.ref(), refMapping.shared());
                }
                return new SkillCategoryDTO(cat.key(), cat.label(), cat.priority(),
                    cat.ref(), Boolean.TRUE.equals(cat.shared()));
            })
            .toList();

        long matchedCount = categories.stream().filter(c -> c.ref() != null && !c.ref().isBlank()).count();
        log.info("构建自定义 Skill: {} 个分类, {} 个匹配到参考文件", categories.size(), matchedCount);

        return new SkillDTO(CUSTOM_SKILL_ID, "自定义面试（JD 解析）",
            "基于职位描述提取的面试方向", categories,
            false, jdText, null, null);
    }

    public List<CategoryDTO> parseJd(String jdText) {
        if (jdText == null || jdText.length() < MIN_JD_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 内容太少（至少 " + MIN_JD_LENGTH + " 字），请补充后重试");
        }

        log.info("开始解析 JD，长度: {}", jdText.length());

        ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
        String systemPrompt = jdSystemPromptTemplate.render(Map.of(
            "referenceFileList", cachedReferenceFileList
        )) + "\n\n" + jdOutputConverter.getFormat();
        String userPrompt = "职位描述：\n" + jdText;

        try {
            CategoryListDTO result = structuredOutputInvoker.invoke(
                chatClient, systemPrompt, userPrompt, jdOutputConverter,
                ErrorCode.AI_SERVICE_ERROR, "JD 解析失败：", "JD 解析", log
            );

            if (result == null || result.categories() == null || result.categories().isEmpty()) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析结果为空，请重试");
            }

            long refMatched = result.categories().stream().filter(c -> c.ref() != null && !c.ref().isBlank()).count();
            log.info("JD 解析完成: {} 个方向, {} 个匹配到参考文件", result.categories().size(), refMatched);
            return result.categories();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("JD 解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析失败，请重试或选择预设主题");
        }
    }

    private String buildReferenceFileList() {
        // 收集所有去重的参考文件（文件名 → Markdown 表格行）
        Map<String, String> refDescriptions = new LinkedHashMap<>();
        for (var entry : presetRegistry.entrySet()) {
            String skillName = entry.getValue().getDisplayName() != null
                ? entry.getValue().getDisplayName() : entry.getValue().getName();
            if (entry.getValue().getCategories() == null) continue;
            for (InterviewSkillProperties.CategoryDef cat : entry.getValue().getCategories()) {
                if (cat.getRef() != null && !cat.getRef().isBlank()) {
                    refDescriptions.putIfAbsent(cat.getRef(),
                        "| " + cat.getRef()
                            + " | " + (Boolean.TRUE.equals(cat.getShared()) ? "shared" : "skill-local")
                            + " | " + skillName
                            + " | " + cat.getLabel() + " |\n");
                }
            }
        }

        if (refDescriptions.isEmpty()) {
            return "（无可用参考文件）";
        }

        StringBuilder sb = new StringBuilder("| 文件名 | 范围 | 来源 Skill | 覆盖内容 |\n");
        sb.append("|--------|------|-------------|----------|\n");
        for (String row : refDescriptions.values()) {
            sb.append(row);
        }
        return sb.toString();
    }

    public Map<String, Integer> calculateAllocation(String skillId, int totalQuestions) {
        return calculateAllocation(getSkill(skillId).categories(), totalQuestions);
    }

    public Map<String, Integer> calculateAllocation(List<SkillCategoryDTO> categories, int totalQuestions) {
        List<SkillCategoryDTO> alwaysOneCats = new ArrayList<>();
        List<SkillCategoryDTO> coreCats = new ArrayList<>();
        List<SkillCategoryDTO> normalCats = new ArrayList<>();

        for (SkillCategoryDTO cat : categories) {
            switch (cat.priority()) {
                case "ALWAYS_ONE" -> alwaysOneCats.add(cat);
                case "CORE" -> coreCats.add(cat);
                default -> normalCats.add(cat);
            }
        }

        Map<String, Integer> allocation = new LinkedHashMap<>();
        int remaining = totalQuestions;

        // Phase 1: ALWAYS_ONE 保底各 1 题
        for (SkillCategoryDTO cat : alwaysOneCats) {
            if (remaining > 0) {
                allocation.put(cat.key(), 1);
                remaining--;
            }
        }

        // Phase 2: 先给所有类目各 1 题（CORE 优先），保证覆盖率
        for (SkillCategoryDTO cat : coreCats) {
            if (remaining > 0) {
                allocation.put(cat.key(), 1);
                remaining--;
            }
        }
        for (SkillCategoryDTO cat : normalCats) {
            if (remaining > 0) {
                allocation.put(cat.key(), 1);
                remaining--;
            }
        }

        // Phase 3: 剩余名额按 CORE 优先轮转分配
        while (remaining > 0) {
            for (SkillCategoryDTO cat : coreCats) {
                if (remaining <= 0) break;
                allocation.merge(cat.key(), 1, Integer::sum);
                remaining--;
            }
            for (SkillCategoryDTO cat : normalCats) {
                if (remaining <= 0) break;
                allocation.merge(cat.key(), 1, Integer::sum);
                remaining--;
            }
            if (coreCats.isEmpty() && normalCats.isEmpty()) break;
        }

        // 确保所有类目都出现在 allocation 中
        for (SkillCategoryDTO cat : coreCats) {
            allocation.putIfAbsent(cat.key(), 0);
        }
        for (SkillCategoryDTO cat : normalCats) {
            allocation.putIfAbsent(cat.key(), 0);
        }

        log.debug("题目分配: total={}, allocation={}", totalQuestions, allocation);
        return allocation;
    }

    public String buildAllocationDescription(Map<String, Integer> allocation, List<SkillCategoryDTO> categories) {
        StringBuilder sb = new StringBuilder();
        for (SkillCategoryDTO cat : categories) {
            int count = allocation.getOrDefault(cat.key(), 0);
            if (count > 0) {
                sb.append("| ").append(cat.label()).append(" | ").append(count).append(" 题 | ").append(cat.priority()).append(" |\n");
            }
        }
        return sb.toString();
    }

    public String buildReferenceSection(SkillDTO skill, Map<String, Integer> allocation) {
        return buildReferenceSectionInternal(
            skill,
            category -> allocation.getOrDefault(category.key(), 0) > 0,
            MAX_REFERENCE_SECTION_CHARS
        );
    }

    /**
     * 评估阶段参考基线：不限制题量分配，覆盖该 skill 下所有配置了 reference 的分类。
     */
    public String buildEvaluationReferenceSection(String skillId) {
        SkillDTO skill = getSkill(skillId);
        return buildReferenceSectionInternal(
            skill,
            category -> true,
            MAX_EVALUATION_REFERENCE_SECTION_CHARS
        );
    }

    /**
     * 安全版本的评估参考基线：skillId 为空或加载失败时返回空字符串，不抛异常。
     */
    public String buildEvaluationReferenceSectionSafe(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return "";
        }
        try {
            return buildEvaluationReferenceSection(skillId);
        } catch (Exception e) {
            log.warn("加载评估参考基线失败，降级为无参考: skillId={}, error={}", skillId, e.getMessage());
            return "";
        }
    }

    private String buildReferenceSectionInternal(SkillDTO skill,
                                                 Predicate<SkillCategoryDTO> categoryFilter,
                                                 int maxChars) {
        StringBuilder sb = new StringBuilder();

        for (SkillCategoryDTO category : skill.categories()) {
            if (!categoryFilter.test(category)) {
                continue;
            }
            if (category.ref() == null || category.ref().isBlank()) {
                continue;
            }

            // custom 模式下非 shared 的 ref 需要查原始 skillId 拼路径
            String effectiveSkillId = skill.id();
            if (CUSTOM_SKILL_ID.equals(skill.id()) && !category.shared() && category.ref() != null) {
                RefMapping mapping = categoryRefIndex.get(category.key());
                if (mapping != null) {
                    effectiveSkillId = mapping.sourceSkillId();
                }
            }
            String referenceContent = loadReferenceContent(effectiveSkillId, category.ref(), category.shared());
            if (referenceContent.isBlank()) {
                continue;
            }

            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("### ").append(category.label()).append(" (").append(category.key()).append(")\n");
            sb.append(referenceContent);

            if (sb.length() >= maxChars) {
                sb.setLength(maxChars);
                sb.append("\n...（references 已截断）");
                break;
            }
        }

        return sb.isEmpty() ? "未配置 references。" : sb.toString();
    }

    private String loadClasspathPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private InterviewSkillProperties.SkillDefinition parseSkillDefinition(String skillId, Resource resource, Yaml yaml) {
        try {
            String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
            Matcher matcher = FRONT_MATTER_PATTERN.matcher(markdown);
            if (!matcher.matches()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Skill 文件格式错误（缺少 front matter）: " + resource.getDescription());
            }

            String frontMatter = matcher.group(1);
            String body = matcher.group(2) != null ? matcher.group(2).trim() : "";

            InterviewSkillProperties.SkillFrontMatterDefinition frontMatterDef =
                yaml.loadAs(frontMatter, InterviewSkillProperties.SkillFrontMatterDefinition.class);

            InterviewSkillProperties.SkillDefinition definition = new InterviewSkillProperties.SkillDefinition();
            if (frontMatterDef != null) {
                definition.setName(frontMatterDef.getName());
                definition.setDescription(frontMatterDef.getDescription());
            }
            if (!body.isBlank()) {
                definition.setPersona(body);
            }

            InterviewSkillProperties.SkillMetaDefinition metaDef = loadSkillMetaDefinition(skillId, yaml);
            if (metaDef != null) {
                definition.setDisplayName(metaDef.getDisplayName());
                definition.setDisplay(metaDef.getDisplay());
                definition.setCategories(metaDef.getCategories());
            }

            if (definition.getCategories() == null) {
                definition.setCategories(List.of());
            }
            return definition;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "读取 Skill 文件失败: " + resource.getDescription());
        }
    }

    private InterviewSkillProperties.SkillMetaDefinition loadSkillMetaDefinition(String skillId, Yaml yaml) {
        String location = "classpath:skills/" + skillId + "/" + SKILL_META_FILE;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("skill meta 文件不存在，使用默认配置: skillId={}, location={}", skillId, location);
            return null;
        }

        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            InterviewSkillProperties.SkillMetaDefinition meta = yaml.loadAs(content, InterviewSkillProperties.SkillMetaDefinition.class);
            return meta != null ? meta : new InterviewSkillProperties.SkillMetaDefinition();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取 skill meta 文件失败: " + location);
        }
    }

    private String extractSkillId(Resource resource) {
        try {
            String normalized = resource.getURL().toString().replace('\\', '/');
            Matcher matcher = SKILL_ID_PATTERN.matcher(normalized);
            if (matcher.matches()) {
                return matcher.group(1);
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private String loadReferenceContent(String skillId, String referenceFile, boolean shared) {
        if (!isSafeReferencePath(referenceFile)) {
            log.warn("忽略不安全的 reference 路径: skillId={}, ref={}", skillId, referenceFile);
            return "";
        }

        List<String> candidateLocations = resolveReferenceLocations(skillId, referenceFile, shared);
        for (String location : candidateLocations) {
            String content = referenceCache.computeIfAbsent(location, this::readReferenceContent);
            if (!content.isBlank()) {
                return content;
            }
        }

        log.warn("未找到 reference: skillId={}, ref={}, shared={}, locations={}",
            skillId, referenceFile, shared, candidateLocations);
        return "";
    }

    private List<String> resolveReferenceLocations(String skillId, String referenceFile, boolean shared) {
        LinkedHashSet<String> locations = new LinkedHashSet<>();
        if (shared) {
            locations.add(buildSharedReferenceLocation(referenceFile));
        }

        addSkillReferenceLocations(locations, skillId, referenceFile);

        if (!shared) {
            locations.add(buildSharedReferenceLocation(referenceFile));
        }

        if (CUSTOM_SKILL_ID.equals(skillId) || shared) {
            for (String presetSkillId : presetRegistry.keySet()) {
                addSkillReferenceLocations(locations, presetSkillId, referenceFile);
            }
        }

        return List.copyOf(locations);
    }

    private void addSkillReferenceLocations(LinkedHashSet<String> locations, String skillId, String referenceFile) {
        if (skillId == null || skillId.isBlank() || CUSTOM_SKILL_ID.equals(skillId)) {
            return;
        }
        locations.add("classpath:skills/" + skillId + "/references/" + referenceFile);
        locations.add("classpath:skills/" + skillId + "/" + referenceFile);
    }

    private String buildSharedReferenceLocation(String referenceFile) {
        return "classpath:skills/_shared/references/" + referenceFile;
    }

    private String readReferenceContent(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            return "";
        }

        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
            if (content.length() > MAX_SINGLE_REFERENCE_CHARS) {
                return content.substring(0, MAX_SINGLE_REFERENCE_CHARS) + "\n...（单文件内容已截断）";
            }
            return content;
        } catch (IOException e) {
            log.warn("读取 reference 失败: location={}", location, e);
            return "";
        }
    }

    private boolean isSafeReferencePath(String referenceFile) {
        return !referenceFile.contains("..")
            && !referenceFile.startsWith("/")
            && !referenceFile.startsWith("\\")
            && referenceFile.matches("[a-zA-Z0-9._/-]+");
    }

    private SkillDTO toSkillDTO(String id, InterviewSkillProperties.SkillDefinition def) {
        String skillDisplayName = (def.getDisplayName() != null && !def.getDisplayName().isBlank())
            ? def.getDisplayName()
            : def.getName();

        InterviewSkillProperties.DisplayDef disp = def.getDisplay();
        DisplayDTO displayDTO = disp != null
            ? new DisplayDTO(disp.getIcon(), disp.getGradient(), disp.getIconBg(), disp.getIconColor())
            : null;

        List<SkillCategoryDTO> categories = def.getCategories() == null
            ? List.of()
            : def.getCategories().stream()
                .map(c -> new SkillCategoryDTO(
                    c.getKey(),
                    c.getLabel(),
                    c.getPriority(),
                    c.getRef(),
                    Boolean.TRUE.equals(c.getShared())
                ))
                .toList();

        return new SkillDTO(
            id,
            skillDisplayName,
            def.getDescription(),
            categories,
            true,
            null,
            def.getPersona(),
            displayDTO
        );
    }

    public record SkillDTO(String id, String name, String description,
                           List<SkillCategoryDTO> categories,
                           boolean isPreset, String sourceJd, String persona, DisplayDTO display) {}

    public record DisplayDTO(String icon, String gradient, String iconBg, String iconColor) {}

    /**
     * 预设 Skill 分类（可携带 references 绑定信息）
     */
    public record SkillCategoryDTO(String key, String label, String priority, String ref, boolean shared) {}

    /**
     * JD 解析返回分类（可携带 LLM 匹配的 ref/shared 信息，后端会按本地 categoryRefIndex 纠正）
     */
    public record CategoryDTO(String key, String label, String priority,
                               String ref, Boolean shared) {}

    private record CategoryListDTO(List<CategoryDTO> categories) {}
}
