package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentRunUsageEntity;
import interview.guide.modules.agent.model.AgentRunStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentRunUsageRepository extends JpaRepository<AgentRunUsageEntity, Long> {

    Optional<AgentRunUsageEntity> findByRunId(String runId);

    List<AgentRunUsageEntity> findTop100ByOwnerKeyOrderByCreatedAtDesc(String ownerKey);

    long countByOwnerKey(String ownerKey);

    long countByOwnerKeyAndRunStatus(String ownerKey, AgentRunStatus runStatus);

    /** 在数据库侧聚合数值，避免随历史运行记录增长而加载整表。 */
    @Query("""
        SELECT
          COALESCE(SUM(u.promptTokens), 0) AS promptTokens,
          COALESCE(SUM(u.completionTokens), 0) AS completionTokens,
          COALESCE(SUM(u.totalTokens), 0) AS totalTokens,
          COALESCE(SUM(u.modelCalls), 0) AS modelCalls,
          COALESCE(SUM(u.durationMillis), 0) AS durationMillis
        FROM AgentRunUsageEntity u
        WHERE u.ownerKey = :ownerKey
        """)
    AgentUsageTotalsProjection summarizeTotals(@Param("ownerKey") String ownerKey);

    @Query("""
        SELECT u.provider AS provider, u.currency AS currency, SUM(u.cost) AS amount
        FROM AgentRunUsageEntity u
        WHERE u.ownerKey = :ownerKey AND u.cost IS NOT NULL
        GROUP BY u.provider, u.currency
        """)
    List<AgentUsageCostProjection> summarizeCosts(@Param("ownerKey") String ownerKey);

    interface AgentUsageTotalsProjection {
        Long getPromptTokens();
        Long getCompletionTokens();
        Long getTotalTokens();
        Long getModelCalls();
        Long getDurationMillis();
    }

    interface AgentUsageCostProjection {
        String getProvider();
        String getCurrency();
        java.math.BigDecimal getAmount();
    }
}
