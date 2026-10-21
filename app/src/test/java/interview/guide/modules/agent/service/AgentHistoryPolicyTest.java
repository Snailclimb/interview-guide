package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentHistoryMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentHistoryPolicyTest {

    @Test
    void shouldKeepRecentMessagesWithinTheContextBudget() {
        List<AgentHistoryMessage> messages = java.util.stream.IntStream.range(0, 40)
            .mapToObj(index -> new AgentHistoryMessage("user", "消息-" + index))
            .toList();

        List<AgentHistoryMessage> normalized = AgentHistoryPolicy.normalize(messages);

        assertThat(normalized).hasSize(AgentHistoryPolicy.MAX_MESSAGES);
        assertThat(normalized.get(0).content()).isEqualTo("消息-8");
        assertThat(normalized.get(31).content()).isEqualTo("消息-39");
        assertThat(normalized.stream().mapToInt(message -> message.content().length()).sum())
            .isLessThanOrEqualTo(AgentHistoryPolicy.MAX_TOTAL_CHARS);
    }

    @Test
    void shouldCompactOneLongMessageInsteadOfRejectingTheConversation() {
        String content = "开头信息" + "x".repeat(20_000) + "结尾信息";

        List<AgentHistoryMessage> normalized = AgentHistoryPolicy.normalize(List.of(
            new AgentHistoryMessage("assistant", content)
        ));

        assertThat(normalized).hasSize(1);
        assertThat(normalized.getFirst().content()).hasSize(AgentHistoryPolicy.MAX_MESSAGE_CHARS);
        assertThat(normalized.getFirst().content()).contains("[较早内容已压缩]");
        assertThat(normalized.getFirst().content()).startsWith("开头信息");
        assertThat(normalized.getFirst().content()).endsWith("结尾信息");
    }
}
