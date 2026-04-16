package interview.guide.modules.interviewschedule.service;

import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleStatusUpdater {

    private final InterviewScheduleRepository repository;

    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void updateExpiredInterviews() {
        int updated = repository.updateStatusByStatusAndInterviewTimeBefore(
            InterviewStatus.CANCELLED, InterviewStatus.PENDING, LocalDateTime.now());

        if (updated > 0) {
            log.info("已将 {} 条过期面试标记为已取消", updated);
        }
    }
}
