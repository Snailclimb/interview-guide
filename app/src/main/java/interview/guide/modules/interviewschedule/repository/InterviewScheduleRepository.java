package interview.guide.modules.interviewschedule.repository;

import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InterviewScheduleRepository extends JpaRepository<InterviewScheduleEntity, Long> {
    List<InterviewScheduleEntity> findByStatusAndInterviewTimeBefore(InterviewStatus status, LocalDateTime time);

    List<InterviewScheduleEntity> findByOwnerIdAndStatus(String ownerId, InterviewStatus status);

    List<InterviewScheduleEntity> findByOwnerIdAndInterviewTimeBetween(
        String ownerId, LocalDateTime start, LocalDateTime end);

    List<InterviewScheduleEntity> findAllByOwnerIdOrderByInterviewTimeAsc(String ownerId);

    java.util.Optional<InterviewScheduleEntity> findByIdAndOwnerId(Long id, String ownerId);

    long countByOwnerIdIsNotNull();

    @Modifying
    @Query("UPDATE InterviewScheduleEntity e SET e.ownerId = :ownerId WHERE e.ownerId IS NULL")
    int claimUnownedRows(@Param("ownerId") String ownerId);

    List<InterviewScheduleEntity> findByStatus(InterviewStatus status);

    List<InterviewScheduleEntity> findByInterviewTimeBetween(LocalDateTime start, LocalDateTime end);

    @Modifying
    @Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus WHERE e.status = :oldStatus AND e.interviewTime < :cutoff")
    int updateStatusByStatusAndInterviewTimeBefore(
        @Param("newStatus") InterviewStatus newStatus,
        @Param("oldStatus") InterviewStatus oldStatus,
        @Param("cutoff") LocalDateTime cutoff);
}
