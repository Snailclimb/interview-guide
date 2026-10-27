package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.agent.model.AgentActionRequestEntity;
import interview.guide.modules.agent.model.AgentActionStatus;
import interview.guide.modules.agent.repository.AgentActionRequestRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.resume.service.ResumeEditorService;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentActionServiceTest {

    @Mock
    private AgentActionRequestRepository repository;

    @Mock
    private AgentAuditService auditService;

    @Mock
    private AgentMemoryService memoryService;

    @Mock
    private InterviewPersistenceService interviewPersistenceService;

    @Mock
    private AgentTrainingTaskService trainingTaskService;

    @Mock
    private ResumeEditorService resumeEditorService;

    @Test
    @DisplayName("生成训练计划草稿时默认只提出动作，不改原始资料")
    void proposesDraftWithStableContract() {
        AgentActionService service = new AgentActionService(repository, auditService);
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "request-1"))
            .thenReturn(Optional.empty());
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.proposeTrainingPlanDraft(
            "interview-1", "agent-1", "user-1", "  ## 训练计划\n\n先补 Redis  ", "request-1"
        );

        assertThat(result.status()).isEqualTo(AgentActionStatus.PROPOSED);
        assertThat(result.toolId()).isEqualTo("interview.plan.draft");
        assertThat(result.riskLevel()).isEqualTo("DRAFT_WRITE");
        assertThat(result.draftContent()).isEqualTo("## 训练计划\n\n先补 Redis");
        assertThat(result.previewText()).contains("不修改原始面试报告");
        ArgumentCaptor<AgentActionRequestEntity> captor =
            ArgumentCaptor.forClass(AgentActionRequestEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AgentActionStatus.PROPOSED);
        verify(auditService).recordQuietly(
            "user-1", "interview-1", "agent-1", null,
            "ACTION_PROPOSED", "agent-action", result.actionId(), "PROPOSED"
        );
    }

    @Test
    @DisplayName("重复提交相同幂等键时返回原动作，不创建第二份草稿")
    void returnsExistingActionForIdempotencyKey() {
        AgentActionService service = new AgentActionService(repository, auditService);
        AgentActionRequestEntity existing = AgentActionRequestEntity.builder()
            .actionId("action-1")
            .ownerKey("user-1")
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .toolId("interview.plan.draft")
            .riskLevel("DRAFT_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title("训练计划草稿")
            .previewText("预览")
            .draftContent("草稿")
            .idempotencyKey("request-1")
            .build();
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "request-1"))
            .thenReturn(Optional.of(existing));

        var result = service.proposeTrainingPlanDraft(
            "interview-1", "agent-1", "user-1", "另一份内容", "request-1"
        );

        assertThat(result.actionId()).isEqualTo("action-1");
        assertThat(result.draftContent()).isEqualTo("草稿");
        verify(repository, never()).save(any());
        verify(auditService, never()).recordQuietly(
            any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("练习题草稿复用同一动作框架但使用独立工具标识")
    void proposesPracticeQuestionsDraft() {
        AgentActionService service = new AgentActionService(repository, auditService);
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "practice-1"))
            .thenReturn(Optional.empty());
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.proposePracticeQuestionsDraft(
            "interview-1", "agent-1", "user-1", "问题：Redis 缓存击穿\n要点：互斥锁", "practice-1"
        );

        assertThat(result.toolId()).isEqualTo("practice.questions.draft");
        assertThat(result.title()).isEqualTo("练习题草稿");
        assertThat(result.previewText()).contains("练习题");
    }

    @Test
    @DisplayName("确认训练任务动作时创建独立任务，不修改面试资料")
    void confirmsTrainingTaskActionThroughTaskService() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService, trainingTaskService
        );
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "task-1"))
            .thenReturn(Optional.empty());
        when(repository.findByActionIdForUpdate(any())).thenAnswer(invocation ->
            Optional.of(taskAction("task-action-1", "user-1"))
        );
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(trainingTaskService.createFromAction(any(AgentActionRequestEntity.class))).thenReturn(null);

        var proposed = service.proposeTrainingTaskDraft(
            "interview-1", "agent-1", "user-1", "先补 Redis 缓存击穿\n每天练一道题", "task-1"
        );
        var confirmed = service.confirm(proposed.actionId(), "user-1");

        assertThat(proposed.toolId()).isEqualTo("training.task.create");
        assertThat(confirmed.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        verify(trainingTaskService).createFromAction(any(AgentActionRequestEntity.class));
        verify(memoryService, never()).rememberItem(any(), any(), any(), any());
        verify(interviewPersistenceService, never()).renameSessionForOwner(any(), any(), any());
    }

    @Test
    @DisplayName("确认报告导出动作时校验当前用户的面试归属")
    void confirmsInterviewReportExportActionForOwner() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService
        );
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "export-1"))
            .thenReturn(Optional.empty());
        when(repository.findByActionIdForUpdate(any())).thenAnswer(invocation ->
            Optional.of(exportAction("export-action-1", "user-1"))
        );
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(interviewPersistenceService.findBySessionIdForOwner("interview-1", "user-1"))
            .thenReturn(Optional.of(new interview.guide.modules.interview.model.InterviewSessionEntity()));

        var proposed = service.proposeInterviewReportExportDraft(
            "interview-1", "agent-1", "user-1", "export-1"
        );
        var confirmed = service.confirm(proposed.actionId(), "user-1");

        assertThat(proposed.toolId()).isEqualTo("interview.report.export");
        assertThat(confirmed.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        assertThat(confirmed.resultSummary()).contains("下载 PDF");
        verify(interviewPersistenceService).findBySessionIdForOwner("interview-1", "user-1");
        verify(memoryService, never()).rememberItem(any(), any(), any(), any());
    }

    @Test
    @DisplayName("确认简历优化动作时只保存编辑版，不覆盖原始简历")
    void confirmsResumeEditActionThroughEditorService() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService,
            trainingTaskService, resumeEditorService
        );
        var interview = org.mockito.Mockito.mock(
            interview.guide.modules.interview.model.InterviewSessionEntity.class
        );
        when(interview.getResumeId()).thenReturn(42L);
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "resume-1"))
            .thenReturn(Optional.empty());
        when(interviewPersistenceService.findBySessionIdForOwner("interview-1", "user-1"))
            .thenReturn(Optional.of(interview));
        when(repository.findByActionIdForUpdate(any())).thenReturn(
            Optional.of(resumeAction("resume-action-1", "user-1"))
        );
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var proposed = service.proposeResumeEditDraft(
            "interview-1", "agent-1", "user-1",
            "## 项目经历\n- 补充可验证的性能结果", "resume-1"
        );
        var confirmed = service.confirm(proposed.actionId(), "user-1");

        assertThat(proposed.toolId()).isEqualTo("resume.editor.save");
        assertThat(confirmed.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        verify(resumeEditorService).saveAgentDraftForOwner(
            42L, "user-1", "## 项目经历\n- 补充可验证的性能结果"
        );
    }

    @Test
    @DisplayName("没有关联简历的面试不能生成简历优化动作")
    void rejectsResumeEditActionWithoutResume() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService,
            trainingTaskService, resumeEditorService
        );
        var interview = org.mockito.Mockito.mock(
            interview.guide.modules.interview.model.InterviewSessionEntity.class
        );
        when(interview.getResumeId()).thenReturn(null);
        when(interviewPersistenceService.findBySessionIdForOwner("interview-1", "user-1"))
            .thenReturn(Optional.of(interview));

        assertThatThrownBy(() -> service.proposeResumeEditDraft(
            "interview-1", "agent-1", "user-1", "优化内容", "resume-no-id"
        )).isInstanceOf(BusinessException.class)
            .hasMessageContaining("没有可编辑的关联简历");
        verify(resumeEditorService, never()).saveAgentDraftForOwner(any(), any(), any());
    }

    @Test
    @DisplayName("报告导出下载只接受当前用户已成功确认的动作")
    void loadsConfirmedReportSessionIdForOwnerOnly() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService
        );
        AgentActionRequestEntity action = exportAction("export-action-1", "user-1");
        action.setStatus(AgentActionStatus.SUCCEEDED);
        when(repository.findByActionId("export-action-1")).thenReturn(Optional.of(action));
        when(interviewPersistenceService.findBySessionIdForOwner("interview-1", "user-1"))
            .thenReturn(Optional.of(new interview.guide.modules.interview.model.InterviewSessionEntity()));

        assertThat(service.loadConfirmedReportSessionId("export-action-1", "user-1"))
            .isEqualTo("interview-1");

        action.setStatus(AgentActionStatus.PROPOSED);
        assertThatThrownBy(() -> service.loadConfirmedReportSessionId("export-action-1", "user-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已确认");

        action.setStatus(AgentActionStatus.SUCCEEDED);
        assertThatThrownBy(() -> service.loadConfirmedReportSessionId("export-action-1", "owner-b"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权访问");
    }

    @Test
    @DisplayName("确认动作按状态机完成，并且重复确认是幂等的")
    void confirmsActionIdempotently() {
        AgentActionService service = new AgentActionService(repository, auditService);
        AgentActionRequestEntity action = draft("action-1", "user-1");
        when(repository.findByActionIdForUpdate("action-1")).thenReturn(Optional.of(action));
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.confirm("action-1", "user-1");
        var repeated = service.confirm("action-1", "user-1");

        assertThat(result.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        assertThat(result.resultSummary()).contains("原始面试资料未被修改");
        assertThat(repeated.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        verify(repository, times(2)).save(action);
    }

    @Test
    @DisplayName("其他用户不能确认动作")
    void rejectsCrossOwnerConfirmation() {
        AgentActionService service = new AgentActionService(repository, auditService);
        when(repository.findByActionIdForUpdate("action-1"))
            .thenReturn(Optional.of(draft("action-1", "owner-a")));

        assertThatThrownBy(() -> service.confirm("action-1", "owner-b"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权访问");
    }

    @Test
    @DisplayName("查询动作草稿时按 Agent 子会话隔离")
    void listsActionsForSelectedAgentSessionOnly() {
        AgentActionService service = new AgentActionService(repository, auditService);
        when(repository.findTop50ByOwnerKeyAndInterviewSessionIdAndAgentSessionIdOrderByCreatedAtDesc(
            "user-1", "interview-1", "agent-2"
        )).thenReturn(java.util.List.of());

        assertThat(service.listForOwner("interview-1", "agent-2", "user-1")).isEmpty();
        verify(repository).findTop50ByOwnerKeyAndInterviewSessionIdAndAgentSessionIdOrderByCreatedAtDesc(
            "user-1", "interview-1", "agent-2"
        );
        verify(repository, never()).findTop50ByOwnerKeyAndInterviewSessionIdOrderByCreatedAtDesc(
            "user-1", "interview-1"
        );
    }

    @Test
    @DisplayName("待确认草稿允许人工编辑，确认后不允许修改")
    void editsOnlyProposedDraft() {
        AgentActionService service = new AgentActionService(repository, auditService);
        AgentActionRequestEntity action = draft("action-1", "user-1");
        when(repository.findByActionIdForUpdate("action-1")).thenReturn(Optional.of(action));
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var updated = service.updateDraft("action-1", "user-1", "人工补充：每天完成一道题");

        assertThat(updated.draftContent()).isEqualTo("人工补充：每天完成一道题");
        assertThat(updated.status()).isEqualTo(AgentActionStatus.PROPOSED);
        verify(auditService).recordQuietly(
            "user-1", "interview-1", "agent-1", null,
            "ACTION_DRAFT_UPDATED", "agent-action", "action-1", "PROPOSED"
        );

        action.setStatus(AgentActionStatus.SUCCEEDED);
        assertThatThrownBy(() -> service.updateDraft("action-1", "user-1", "再次修改"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只有待确认");
    }

    @Test
    @DisplayName("确认长期记忆动作时复用现有记忆服务")
    void confirmsMemoryActionThroughMemoryService() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService, trainingTaskService
        );
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "memory-1"))
            .thenReturn(Optional.empty());
        when(repository.findByActionIdForUpdate(any())).thenAnswer(invocation ->
            Optional.of(memoryAction("memory-action-1", "user-1"))
        );
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var proposed = service.proposeMemoryDraft(
            "interview-1", "agent-1", "user-1", "Redis 缓存击穿需要互斥锁保护",
            "weakness", "java-backend", "memory-1"
        );
        var confirmed = service.confirm(proposed.actionId(), "user-1");

        assertThat(proposed.status()).isEqualTo(AgentActionStatus.PROPOSED);
        assertThat(confirmed.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        verify(memoryService).rememberItem(
            "user-1", "weakness", "Redis 缓存击穿需要互斥锁保护", "java-backend"
        );
        verify(interviewPersistenceService, never()).renameSessionForOwner(any(), any(), any());
    }

    @Test
    @DisplayName("确认改名动作只调用显式归属改名入口")
    void confirmsInterviewTitleActionThroughOwnerScopedService() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService
        );
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "title-1"))
            .thenReturn(Optional.empty());
        when(repository.findByActionIdForUpdate(any())).thenAnswer(invocation ->
            Optional.of(titleAction("title-action-1", "user-1"))
        );
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var proposed = service.proposeInterviewTitleDraft(
            "interview-1", "agent-1", "user-1", "Java 后端 Redis 专项复盘", "title-1"
        );
        var confirmed = service.confirm(proposed.actionId(), "user-1");

        assertThat(confirmed.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        verify(interviewPersistenceService).renameSessionForOwner(
            "interview-1", "user-1", "Java 后端 Redis 专项复盘"
        );
        verify(memoryService, never()).rememberItem(any(), any(), any(), any());
    }

    @Test
    @DisplayName("执行失败后可以重试，并且重试成功后动作恢复为已保存")
    void retriesFailedActionWithinLimit() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService
        );
        AgentActionRequestEntity action = memoryAction("memory-action-1", "user-1");
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "memory-1"))
            .thenReturn(Optional.empty());
        when(repository.findByActionIdForUpdate(any()))
            .thenReturn(Optional.of(action));
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(memoryService.rememberItem(
            "user-1", "weakness", "Redis 缓存击穿需要互斥锁保护", "java-backend"
        ))
            .thenThrow(new IllegalStateException("temporary failure"))
            .thenReturn(null);

        var proposed = service.proposeMemoryDraft(
            "interview-1", "agent-1", "user-1", "Redis 缓存击穿需要互斥锁保护",
            "weakness", "java-backend", "memory-1"
        );
        var failed = service.confirm(proposed.actionId(), "user-1");
        var retried = service.retry(failed.actionId(), "user-1");

        assertThat(failed.status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(failed.resultSummary()).contains("可以重试");
        assertThat(retried.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        verify(memoryService, times(2)).rememberItem(
            "user-1", "weakness", "Redis 缓存击穿需要互斥锁保护", "java-backend"
        );
        verify(auditService).recordQuietly(
            "user-1", "interview-1", "agent-1", null,
            "ACTION_RETRY", "agent-action", "memory-action-1", "2"
        );
    }

    @Test
    @DisplayName("失败动作达到三次后拒绝继续重试")
    void stopsRetryingAfterThreeAttempts() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService
        );
        AgentActionRequestEntity action = memoryAction("memory-action-1", "user-1");
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "memory-1"))
            .thenReturn(Optional.empty());
        when(repository.findByActionIdForUpdate(any()))
            .thenReturn(Optional.of(action));
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(memoryService.rememberItem(
            "user-1", "weakness", "Redis 缓存击穿需要互斥锁保护", "java-backend"
        )).thenThrow(new IllegalStateException("persistent failure"));

        var proposed = service.proposeMemoryDraft(
            "interview-1", "agent-1", "user-1", "Redis 缓存击穿需要互斥锁保护",
            "weakness", "java-backend", "memory-1"
        );
        var failed = service.confirm(proposed.actionId(), "user-1");
        service.retry(failed.actionId(), "user-1");
        var exhausted = service.retry(failed.actionId(), "user-1");

        assertThat(exhausted.status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(exhausted.resultSummary()).contains("最大重试次数");
        assertThatThrownBy(() -> service.retry(failed.actionId(), "user-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("最大重试次数");
        verify(memoryService, times(3)).rememberItem(
            "user-1", "weakness", "Redis 缓存击穿需要互斥锁保护", "java-backend"
        );
    }

    @Test
    @DisplayName("应用中断后只回收过期执行中的动作，不自动重放副作用")
    void recoversStaleRunningActionWithoutReplayingIt() {
        AgentActionService service = new AgentActionService(
            repository, auditService, memoryService, interviewPersistenceService
        );
        AgentActionRequestEntity action = memoryAction("memory-action-1", "user-1");
        action.setStatus(AgentActionStatus.RUNNING);
        action.setExecutionAttempts(1);
        action.setUpdatedAt(LocalDateTime.now().minusMinutes(6));
        when(repository.findStaleRunningForUpdate(any(), any())).thenReturn(List.of(action));
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        int recovered = service.recoverStaleRunningActions();

        assertThat(recovered).isEqualTo(1);
        assertThat(action.getStatus()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(action.getResultSummary()).contains("未自动重复执行");
        verify(memoryService, never()).rememberItem(any(), any(), any(), any());
        verify(repository).save(action);
        verify(auditService).recordQuietly(
            "user-1", "interview-1", "agent-1", null,
            "ACTION_RECOVERED", "agent-action", "memory-action-1", "FAILED"
        );
    }

    @Test
    @DisplayName("只有当前用户已确认的练习题草稿可以注入下一轮面试")
    void loadsOnlyConfirmedInterviewFocus() {
        AgentActionService service = new AgentActionService(repository, auditService);
        AgentActionRequestEntity action = draft("practice-action-1", "user-1");
        action.setToolId("practice.questions.draft");
        action.setStatus(AgentActionStatus.SUCCEEDED);
        action.setDraftContent("问题一：Redis 缓存击穿\n问题二：消息幂等");
        when(repository.findByActionId("practice-action-1")).thenReturn(Optional.of(action));

        assertThat(service.loadConfirmedInterviewFocus("practice-action-1", "user-1"))
            .contains("Redis 缓存击穿")
            .contains("消息幂等");

        action.setStatus(AgentActionStatus.PROPOSED);
        assertThatThrownBy(() -> service.loadConfirmedInterviewFocus("practice-action-1", "user-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已确认");
    }

    @Test
    @DisplayName("下一轮专项练习拒绝跨用户读取练习草稿")
    void rejectsCrossOwnerPracticeContext() {
        AgentActionService service = new AgentActionService(repository, auditService);
        AgentActionRequestEntity action = draft("practice-action-1", "owner-a");
        action.setToolId("practice.questions.draft");
        action.setStatus(AgentActionStatus.SUCCEEDED);
        when(repository.findByActionId("practice-action-1")).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.loadConfirmedInterviewFocus("practice-action-1", "owner-b"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权访问");
    }

    @Test
    @DisplayName("已确认的训练计划也可以作为下一轮面试重点")
    void loadsConfirmedTrainingPlanFocus() {
        AgentActionService service = new AgentActionService(repository, auditService);
        AgentActionRequestEntity action = draft("plan-action-1", "user-1");
        action.setToolId("interview.plan.draft");
        action.setStatus(AgentActionStatus.SUCCEEDED);
        action.setDraftContent("第一周：补 Redis 缓存击穿；第二周：练消息幂等");
        when(repository.findByActionId("plan-action-1")).thenReturn(Optional.of(action));

        assertThat(service.loadConfirmedInterviewFocus("plan-action-1", "user-1"))
            .contains("Redis 缓存击穿")
            .contains("消息幂等");
    }

    @Test
    @DisplayName("记忆和名称草稿都遵守短内容边界")
    void rejectsOversizedMemoryAndTitleDrafts() {
        AgentActionService service = new AgentActionService(repository, auditService);

        assertThatThrownBy(() -> service.proposeMemoryDraft(
            "interview-1", "agent-1", "user-1", "x".repeat(161),
            "weakness", null, "memory-long"
        )).isInstanceOf(BusinessException.class).hasMessageContaining("参数过长");
        assertThatThrownBy(() -> service.proposeInterviewTitleDraft(
            "interview-1", "agent-1", "user-1", "x".repeat(121), "title-long"
        )).isInstanceOf(BusinessException.class).hasMessageContaining("参数过长");
    }

    private AgentActionRequestEntity draft(String actionId, String owner) {
        return AgentActionRequestEntity.builder()
            .actionId(actionId)
            .ownerKey(owner)
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .toolId("interview.plan.draft")
            .riskLevel("DRAFT_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title("训练计划草稿")
            .previewText("预览")
            .draftContent("草稿")
            .idempotencyKey("request-1")
            .build();
    }

    private AgentActionRequestEntity memoryAction(String actionId, String owner) {
        return AgentActionRequestEntity.builder()
            .actionId(actionId)
            .ownerKey(owner)
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .toolId("agent.memory.save")
            .riskLevel("MEMORY_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title("长期记忆草稿")
            .previewText("预览")
            .draftContent("Redis 缓存击穿需要互斥锁保护")
            .memoryCategory("weakness")
            .memoryJobScope("java-backend")
            .idempotencyKey("memory-1")
            .build();
    }

    private AgentActionRequestEntity taskAction(String actionId, String owner) {
        AgentActionRequestEntity action = draft(actionId, owner);
        action.setToolId("training.task.create");
        action.setTitle("训练任务草稿");
        return action;
    }

    private AgentActionRequestEntity exportAction(String actionId, String owner) {
        AgentActionRequestEntity action = draft(actionId, owner);
        action.setToolId("interview.report.export");
        action.setTitle("导出面试报告");
        action.setDraftContent("导出当前面试报告 PDF");
        return action;
    }

    private AgentActionRequestEntity resumeAction(String actionId, String owner) {
        AgentActionRequestEntity action = draft(actionId, owner);
        action.setToolId("resume.editor.save");
        action.setTitle("简历优化编辑版");
        action.setDraftContent("## 项目经历\n- 补充可验证的性能结果");
        return action;
    }

    private AgentActionRequestEntity titleAction(String actionId, String owner) {
        return AgentActionRequestEntity.builder()
            .actionId(actionId)
            .ownerKey(owner)
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .toolId("interview.title.rename")
            .riskLevel("BUSINESS_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title("面试记录名称修改")
            .previewText("预览")
            .draftContent("Java 后端 Redis 专项复盘")
            .targetTitle("Java 后端 Redis 专项复盘")
            .idempotencyKey("title-1")
            .build();
    }
}
