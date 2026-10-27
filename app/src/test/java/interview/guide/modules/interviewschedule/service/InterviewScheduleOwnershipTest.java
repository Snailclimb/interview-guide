package interview.guide.modules.interviewschedule.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewScheduleOwnershipTest {

    @Mock
    private InterviewScheduleRepository repository;

    private InterviewScheduleService service;

    @BeforeEach
    void setUp() {
        service = new InterviewScheduleService(repository);
        AuthUserContext.set("user-a");
    }

    @AfterEach
    void tearDown() {
        AuthUserContext.clear();
    }

    @Test
    void createBindsScheduleToAuthenticatedUser() {
        when(repository.save(any(InterviewScheduleEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(request());

        ArgumentCaptor<InterviewScheduleEntity> captor = ArgumentCaptor.forClass(InterviewScheduleEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isEqualTo("user-a");
    }

    @Test
    void agentScheduleRetryReusesExistingActionSchedule() {
        InterviewScheduleEntity existing = new InterviewScheduleEntity();
        existing.setId(12L);
        existing.setOwnerId("user-a");
        when(repository.findByAgentActionIdAndOwnerId("action-1", "user-a"))
            .thenReturn(Optional.of(existing));

        InterviewScheduleDTO result = service.createForOwner(request(), "user-a", "action-1");

        assertThat(result.getId()).isEqualTo(12L);
        verify(repository, org.mockito.Mockito.never()).save(any(InterviewScheduleEntity.class));
    }

    @Test
    void agentScheduleCreateBindsOwnerAndActionId() {
        when(repository.save(any(InterviewScheduleEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.createForOwner(request(), "user-a", "action-2");

        ArgumentCaptor<InterviewScheduleEntity> captor = ArgumentCaptor.forClass(InterviewScheduleEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isEqualTo("user-a");
        assertThat(captor.getValue().getAgentActionId()).isEqualTo("action-2");
    }

    @Test
    void listIgnoresClientUserIdAndUsesAuthenticatedOwner() {
        when(repository.findAllByOwnerIdOrderByInterviewTimeAsc("user-a"))
            .thenReturn(List.of());

        service.getAll(null, null, null);

        verify(repository).findAllByOwnerIdOrderByInterviewTimeAsc("user-a");
    }

    @Test
    void detailCannotReadAnotherUsersSchedule() {
        when(repository.findByIdAndOwnerId(8L, "user-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(8L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("面试日程不存在");
    }

    @Test
    void deleteUsesOwnerScopedLookup() {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        entity.setId(8L);
        entity.setOwnerId("user-a");
        when(repository.findByIdAndOwnerId(8L, "user-a")).thenReturn(Optional.of(entity));

        service.delete(8L);

        verify(repository).delete(entity);
    }

    private CreateInterviewRequest request() {
        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setCompanyName("示例公司");
        request.setPosition("Java 工程师");
        request.setInterviewTime(LocalDateTime.now().plusDays(1));
        return request;
    }
}
