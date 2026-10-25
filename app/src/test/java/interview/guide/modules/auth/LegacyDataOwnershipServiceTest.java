package interview.guide.modules.auth;

import interview.guide.modules.auth.repository.UserAccountRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyDataOwnershipServiceTest {
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final InterviewScheduleRepository schedules = mock(InterviewScheduleRepository.class);
    private final VoiceInterviewSessionRepository voiceSessions = mock(VoiceInterviewSessionRepository.class);
    private final LegacyDataOwnershipService service =
        new LegacyDataOwnershipService(users, resumes, sessions, schedules, voiceSessions);

    @Test
    void claimsLegacyRowsOnlyForTheSingleAccountMigrationCase() {
        when(users.count()).thenReturn(1L);
        when(resumes.countByOwnerIdIsNotNull()).thenReturn(0L);
        when(sessions.countByOwnerIdIsNotNull()).thenReturn(0L);

        service.claimForSingleUser("user-1");

        verify(resumes).claimUnownedRows("user-1");
        verify(sessions).claimUnownedRows("user-1");
    }

    @Test
    void neverClaimsRowsWhenMultipleAccountsAlreadyExist() {
        when(users.count()).thenReturn(2L);

        service.claimForSingleUser("user-2");

        verify(resumes, never()).countByOwnerIdIsNotNull();
        verify(sessions, never()).countByOwnerIdIsNotNull();
        verify(resumes, never()).claimUnownedRows("user-2");
        verify(sessions, never()).claimUnownedRows("user-2");
    }
}
