package interview.guide.modules.interviewschedule.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleRepository repository;

    private static final String[] COPYABLE_FIELDS = {
        "companyName", "position", "interviewTime", "interviewType",
        "meetingLink", "roundNumber", "interviewer", "notes"
    };

    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request) {
        return createForOwner(request, AuthUserContext.currentUserId().orElse(null));
    }

    /**
     * 由受控业务动作创建日程时使用显式 owner，避免依赖当前线程中的认证上下文。
     * Agent 只能通过已确认的动作进入该入口，普通日程接口行为保持不变。
     */
    @Transactional
    public InterviewScheduleDTO createForOwner(CreateInterviewRequest request, String ownerId) {
        return createForOwner(request, ownerId, null);
    }

    /** Agent 动作专用创建入口：同一动作重试时复用已经创建的日程。 */
    @Transactional
    public InterviewScheduleDTO createForOwner(
        CreateInterviewRequest request, String ownerId, String agentActionId
    ) {
        if (agentActionId != null && !agentActionId.isBlank() && ownerId != null && !ownerId.isBlank()) {
            var existing = repository.findByAgentActionIdAndOwnerId(agentActionId.trim(), ownerId.trim());
            if (existing.isPresent()) {
                return toDTO(existing.get());
            }
        }
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setStatus(InterviewStatus.PENDING);
        if (ownerId != null && !ownerId.isBlank()) {
            entity.setOwnerId(ownerId.trim());
        }
        if (agentActionId != null && !agentActionId.isBlank()) {
            entity.setAgentActionId(agentActionId.trim());
        }

        return toDTO(repository.save(entity));
    }

    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        BeanUtils.copyProperties(request, entity, "id", "status");
        return toDTO(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(getByIdOrThrow(id));
    }

    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        entity.setStatus(status);
        return toDTO(repository.save(entity));
    }

    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        List<InterviewScheduleEntity> entities;
        String ownerId = AuthUserContext.currentUserId().orElse(null);

        if (ownerId != null && start != null && end != null) {
            entities = repository.findByOwnerIdAndInterviewTimeBetween(ownerId, start, end);
        } else if (ownerId != null && status != null) {
            entities = repository.findByOwnerIdAndStatus(ownerId, InterviewStatus.valueOf(status));
        } else if (ownerId != null) {
            entities = repository.findAllByOwnerIdOrderByInterviewTimeAsc(ownerId);
        } else if (start != null && end != null) {
            entities = repository.findByInterviewTimeBetween(start, end);
        } else if (status != null) {
            entities = repository.findByStatus(InterviewStatus.valueOf(status));
        } else {
            entities = repository.findAll();
        }

        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    public InterviewScheduleDTO getById(Long id) {
        return toDTO(getByIdOrThrow(id));
    }

    private InterviewScheduleEntity getByIdOrThrow(Long id) {
        return AuthUserContext.currentUserId()
            .map(ownerId -> repository.findByIdAndOwnerId(id, ownerId))
            .orElseGet(() -> repository.findById(id))
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id));
    }

    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
