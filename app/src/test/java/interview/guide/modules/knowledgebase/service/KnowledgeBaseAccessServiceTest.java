package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseAccessServiceTest {

    private final KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
    private final KnowledgeBaseAccessService service = new KnowledgeBaseAccessService(repository);

    @AfterEach
    void clearContext() {
        AuthUserContext.clear();
    }

    @Test
    void legacySharedKnowledgeBaseRemainsVisibleToAuthenticatedUsers() {
        KnowledgeBaseEntity shared = knowledgeBase(null);
        AuthUserContext.set("user-a");

        assertThat(service.canRead(shared)).isTrue();
        assertThat(service.canModify(shared)).isTrue();
    }

    @Test
    void privateKnowledgeBaseIsVisibleOnlyToOwner() {
        KnowledgeBaseEntity privateKb = knowledgeBase("user-a");

        AuthUserContext.set("user-a");
        assertThat(service.canRead(privateKb)).isTrue();

        AuthUserContext.set("user-b");
        assertThat(service.canRead(privateKb)).isFalse();
        assertThatThrownBy(() -> service.requireReadable(privateKb))
            .isInstanceOf(BusinessException.class)
            .hasMessage("无权访问该知识库");
    }

    @Test
    void idBoundaryRejectsAnotherUsersKnowledgeBaseBeforeRagRetrieval() {
        KnowledgeBaseEntity privateKb = knowledgeBase("user-a");
        when(repository.findAllById(List.of(7L))).thenReturn(List.of(privateKb));
        AuthUserContext.set("user-b");

        assertThatThrownBy(() -> service.requireReadableIds(List.of(7L)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("无权访问该知识库");
    }

    private KnowledgeBaseEntity knowledgeBase(String ownerId) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(7L);
        entity.setOwnerId(ownerId);
        return entity;
    }
}
