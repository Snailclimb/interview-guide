package interview.guide.common.ai;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * spring-ai-agent-utils 工具配置。
 * 当前先接入 SkillsTool，复用 resources/skills/{skillId}/SKILL.md。
 */
@Configuration
@Slf4j
public class AgentUtilsConfiguration {

    private final ResourceLoader resourceLoader;
    private final AgentUtilsProperties agentUtilsProperties;

    public AgentUtilsConfiguration(
        ResourceLoader resourceLoader, AgentUtilsProperties agentUtilsProperties
    ) {
        this.resourceLoader = resourceLoader;
        this.agentUtilsProperties = agentUtilsProperties;
    }

    /**
     * 创建 SkillsTool，并注册为 Spring Bean。
     */
    @Bean("interviewSkillsToolCallback")
    public ToolCallback interviewSkillsToolCallback() {
        String configuredSkillsRoot = agentUtilsProperties.getSkillsRoot();
        // 规范化路径：处理 null/空白、\\ 分隔符、/SKILL.md 后缀、通配符、末尾斜杠等边界情况
        String normalizedSkillsRoot = normalizeSkillsRoot(configuredSkillsRoot);
        Resource skillsRootResource = resourceLoader.getResource(normalizedSkillsRoot);

        // 技能目录不存在时直接拦停启动，避免 Agent 调用时静默报错难以排查
        if (!skillsRootResource.exists()) {
            throw new IllegalStateException("未找到 skills 根目录，请检查配置: " + normalizedSkillsRoot);
        }

        log.info("AgentUtils SkillsTool 已启用，skillsRoot={}, configured={}", normalizedSkillsRoot, configuredSkillsRoot);

        return SkillsTool.builder()
            .addSkillsResource(skillsRootResource)
            .build();
    }

    /**
     * 规范化 skills 路径：兼容用户配置可能携带的各种后缀和格式。
     * 用户可能配置 classpath:skills、classpath:skills/*、classpath:skills/SKILL.md 等变体，
     * 统一归约到目录形式后再交给 ResourceLoader。
     */
    private String normalizeSkillsRoot(String raw) {
        // 未配置时使用默认值，确保 SkillsTool 能找到内置技能
        if (raw == null || raw.isBlank()) {
            return "classpath:skills";
        }

        String normalized = raw.trim();
        normalized = normalized.replace('\\', '/');

        // 用户可能直接指向某个 SKILL.md 文件而非目录
        if (normalized.endsWith("/SKILL.md")) {
            // 去掉 SKILL.md
            normalized = normalized.substring(0, normalized.length() - "/SKILL.md".length());
        }

        // 去掉 classpath:skills/* 中的通配符，SkillsTool 会扫描子目录
        int wildcardIndex = normalized.indexOf('*');
        if (wildcardIndex >= 0) {
            normalized = normalized.substring(0, wildcardIndex);
        }

        // 去掉末尾的 /
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized.isBlank() ? "classpath:skills" : normalized;
    }
}
