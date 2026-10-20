package interview.guide.modules.agent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamEventTest {

    @Test
    void shouldCreateStepEvent() {
        AgentStreamEvent event = AgentStreamEvent.step("context", "completed");

        assertThat(event.type()).isEqualTo("step");
        assertThat(event.step()).isEqualTo("context");
        assertThat(event.status()).isEqualTo("completed");
        assertThat(event.content()).isNull();
    }

    @Test
    void shouldCreateDeltaAndTerminalEvents() {
        AgentStreamEvent delta = AgentStreamEvent.delta("建议先复盘回答结构");
        AgentStreamEvent done = AgentStreamEvent.done();
        AgentStreamEvent error = AgentStreamEvent.error("模型暂时不可用");

        assertThat(delta.type()).isEqualTo("delta");
        assertThat(delta.content()).isEqualTo("建议先复盘回答结构");
        assertThat(done.type()).isEqualTo("done");
        assertThat(error.type()).isEqualTo("error");
        assertThat(error.message()).isEqualTo("模型暂时不可用");
    }
}
