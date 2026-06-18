package interview.guide.common.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 结构化输出调用与重试的统一封装。
 * <p>
 * LLM 生成 JSON 格式输出时，常见问题：
 * - 输出 Markdown 代码块包裹的 JSON（```json ... ```）
 * - JSON 字符串值内引号未转义
 * - 附带解释文字
 * 此类通过重试 + 本地修复 + 严格指令组合处理上述问题。
 */
@Component
public class StructuredOutputInvoker {

    // === 常量区 ===

    // 重试时追加的严格 JSON 指令——提醒模型不要输出 Markdown 包裹、额外文字或未转义引号
    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
    """;

    // invocations = 一次 invoke() 整体成功/失败，attempts = 每次 LLM 请求（含重试），latency = 总耗时
    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";
    // 以下用于 context tag 归一化，适配 Prometheus/Micrometer tag 命名规范
    private static final int MAX_CONTEXT_TAG_LENGTH = 48;
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

    // === 配置字段（从 StructuredOutputProperties 注入） ===

    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;
    private final boolean retryUseRepairPrompt;
    private final boolean retryAppendStrictJsonInstruction;
    private final int errorMessageMaxLength;
    private final boolean metricsEnabled;
    // MeterRegistry 可选——无 Micrometer 依赖时不报错
    private final boolean schemaValidationEnabled;
    private final MeterRegistry meterRegistry;

    public StructuredOutputInvoker(
        StructuredOutputProperties properties,
        @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        // 最少重试 1 次（至少一次调用），避免配置意外设为 0 或负数
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        // 错误消息截断最少保留 20 字符，避免太短看不出问题
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.metricsEnabled = properties.isStructuredMetricsEnabled();
        this.schemaValidationEnabled = properties.isStructuredSchemaValidationEnabled();
        this.meterRegistry = meterRegistry;
    }

    /**
     * 调用 LLM 并解析结构化输出，失败时按配置重试。
     * <p>
     * 重试策略三层递进：
     * 1. 本地 repair 修复常见 JSON 格式问题（未转义引号）
     * 2. 重试时追加严格 JSON 指令 + 上次失败原因
     * 3. 达到 maxAttempts 后抛出 BusinessException
     */
    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        long startNanos = System.nanoTime();
        String contextTag = normalizeContextTag(logContext);
        // 追加防注入指令：防止用户输入通过 system prompt 劫持 LLM 行为
        String securedSystemPrompt = systemPromptWithFormat
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 首次用原始 prompt，重试用增强 prompt（含上次错误信息和严格 JSON 指令）
            String attemptSystemPrompt = attempt == 1
                ? securedSystemPrompt
                : buildRetrySystemPrompt(securedSystemPrompt, lastError);
            try {
                T result = callStructuredOutput(
                    chatClient, attemptSystemPrompt, userPrompt, outputConverter, logContext, log);
                recordAttempt(contextTag, STATUS_SUCCESS);
                recordInvocation(contextTag, STATUS_SUCCESS, startNanos);
                return result;
            } catch (Exception e) {
                lastError = e;
                recordAttempt(contextTag, STATUS_FAILURE);
                if (attempt < maxAttempts) {
                    log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
                        logContext, attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                        logContext, maxAttempts, e.getMessage());
                }
            }
        }

        // 所有重试耗尽，记录失败指标并抛出业务异常
        recordInvocation(contextTag, STATUS_FAILURE, startNanos);
        throw new BusinessException(
            errorCode,
            errorPrefix + (lastError != null ? lastError.getMessage() : "unknown")
        );
    }

    private <T> T callStructuredOutput(
        ChatClient chatClient,
        String systemPrompt,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        var call = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call();
        if (schemaValidationEnabled) {
            return call.entity(outputConverter, spec -> spec.validateSchema());
        }
        String content = call.content();
        return convertWithRepair(content, outputConverter, logContext, log);
    }

    private <T> T convertWithRepair(
        String content,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        try {
            return outputConverter.convert(content);
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(content);
            if (!repaired.equals(content)) {
                try {
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return result;
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    /**
     * 修复 JSON 字符串值内未转义的引号（双引号前面缺少反斜杠）。
     * 这是 LLM 输出 JSON 最频繁的错误模式：如 {"name":他说"你好"} -> {"name":他说\"你好\"}
     * 使用状态机逐字符扫描，仅在确信是字符串终结符时才保留原始 "。
     */
    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;  // 当前是否在 JSON 字符串值内部
        boolean escaping = false;  // 前一个字符是否为反斜杠（转义模式）
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                // 根据后续字符判断这是字符串终结符还是字符串内部的双引号
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    /**
     * 启发式判断当前位置的双引号是否可能是字符串的终结符。
     * 跳过空白后，如果后面的字符是 ,}]: 之一，则很可能是终结符；
     * 如果后面紧跟其他字符（如中文），则很可能是字符串内部的引号。
     */
    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    /**
     * 构建重试 prompt：保留原 system prompt，追加严格 JSON 指令和（可选的）上次失败原因。
     * 由配置控制是否启用：retryUseRepairPrompt、retryAppendStrictJsonInstruction、includeLastErrorInRetryPrompt。
     */
    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n");

        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    /**
     * 清洗错误消息：去换行、截断，避免重试 prompt 中的错误消息太长稀释有效指令。
     */
    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    // === 指标记录 ===

    private void recordAttempt(String contextTag, String status) {
        if (!isMetricsAvailable()) {
            return;
        }
        meterRegistry.counter(
            METRIC_ATTEMPTS,
            Tags.of("context", contextTag, "status", status)
        ).increment();
    }

    /**
     * 记录一次调用。
     */
    private void recordInvocation(String contextTag, String status, long startNanos) {
        if (!isMetricsAvailable()) {
            return;
        }
        Tags tags = Tags.of("context", contextTag, "status", status);
        // 调用次数
        meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
        // 调用耗时
        meterRegistry.timer(METRIC_LATENCY, tags)
            .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private boolean isMetricsAvailable() {
        return metricsEnabled && meterRegistry != null;
    }

    /**
     * 将上下文标签归一化为 Prometheus tag 可接受的格式：
     * 小写 + 下划线 + 最多 48 字符。
     */
    private String normalizeContextTag(String raw) {
        String source = (raw == null || raw.isBlank()) ? "unknown" : raw;
        String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        if (normalized.length() > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH);
        }
        return normalized;
    }
}
