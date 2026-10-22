package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentHistoryMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 短期上下文策略。
 *
 * <p>请求可以携带较长的多轮对话，但真正送入 Prompt 的上下文必须有稳定预算。
 * 策略从最新消息向前保留，超长单条消息保留开头和结尾，避免把上下文预算暴露为
 * 用户可见的硬错误。</p>
 */
public final class AgentHistoryPolicy {
    /** 传输层允许更多轮对话；真正送入模型前再按上下文预算处理。 */
    public static final int MAX_MESSAGES = 32;
    /** 单条消息的内部压缩阈值，不是对用户暴露的输入校验。 */
    public static final int MAX_MESSAGE_CHARS = 12_000;
    /** 预留面试报告、Skill、RAG 和当前问题后的历史上下文预算。 */
    public static final int MAX_TOTAL_CHARS = 48_000;

    private static final String COMPACT_MARKER = "\n[较早内容已压缩]\n";

    private AgentHistoryPolicy() {
    }

    public static List<AgentHistoryMessage> normalize(List<AgentHistoryMessage> messages) {
        return normalize(messages, MAX_TOTAL_CHARS, MAX_MESSAGE_CHARS);
    }

    /** 在模型明确返回上下文超限时使用更紧的历史预算。 */
    public static List<AgentHistoryMessage> normalize(
        List<AgentHistoryMessage> messages,
        int maxTotalChars,
        int maxMessageChars
    ) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<AgentHistoryMessage> normalized = new ArrayList<>();
        int remaining = Math.max(0, maxTotalChars);
        int start = Math.max(0, messages.size() - MAX_MESSAGES);
        for (int index = messages.size() - 1; index >= start && remaining > 0; index--) {
            AgentHistoryMessage message = messages.get(index);
            if (message == null || message.role() == null || message.content() == null) {
                continue;
            }

            int roleOverhead = message.role().length() + 2;
            int available = Math.min(Math.max(0, maxMessageChars), remaining - roleOverhead);
            if (available <= 0) {
                break;
            }

            String content = compact(message.content(), available);
            normalized.add(new AgentHistoryMessage(message.role(), content));
            remaining -= roleOverhead + content.length();
        }

        Collections.reverse(normalized);
        return List.copyOf(normalized);
    }

    private static String compact(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= COMPACT_MARKER.length() + 2) {
            return value.substring(0, maxChars);
        }

        int remaining = maxChars - COMPACT_MARKER.length();
        int headChars = (remaining + 1) / 2;
        int tailChars = remaining - headChars;
        return value.substring(0, headChars)
            + COMPACT_MARKER
            + value.substring(value.length() - tailChars);
    }
}
