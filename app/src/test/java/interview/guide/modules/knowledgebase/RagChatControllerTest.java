package interview.guide.modules.knowledgebase;

import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RAG 聊天流式接口")
class RagChatControllerTest {

  @Mock
  private RagChatSessionService sessionService;

  @InjectMocks
  private RagChatController controller;

  @Test
  @DisplayName("客户端中断时应保存已经生成的部分回答")
  void shouldPersistPartialAnswerWhenClientCancels() {
    when(sessionService.prepareStreamMessage(1L, "什么是 RAG"))
        .thenReturn(10L);
    when(sessionService.getStreamAnswer(1L, "什么是 RAG"))
        .thenReturn(Flux.concat(Flux.just("部分回答"), Flux.never()));

    Flux<ServerSentEvent<String>> response = controller.sendMessageStream(
        1L,
        new SendMessageRequest("什么是 RAG")
    );

    AtomicReference<ServerSentEvent<String>> event = new AtomicReference<>();
    Disposable subscription = response.subscribe(event::set);
    assertThat(event.get().data()).isEqualTo("部分回答");
    subscription.dispose();

    verify(sessionService).completeStreamMessage(10L, "部分回答");
  }

  @Test
  @DisplayName("首个分片前中断时也应结束消息占位")
  void shouldCompletePlaceholderWhenClientCancelsBeforeFirstChunk() {
    when(sessionService.prepareStreamMessage(2L, "解释向量检索"))
        .thenReturn(20L);
    when(sessionService.getStreamAnswer(2L, "解释向量检索"))
        .thenReturn(Flux.never());

    Flux<ServerSentEvent<String>> response = controller.sendMessageStream(
        2L,
        new SendMessageRequest("解释向量检索")
    );

    Disposable subscription = response.subscribe();
    subscription.dispose();

    verify(sessionService).completeStreamMessage(20L, "【已中断】回答生成已取消");
  }
}
