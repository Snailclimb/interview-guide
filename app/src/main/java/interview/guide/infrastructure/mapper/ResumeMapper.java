package interview.guide.infrastructure.mapper;

import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import org.mapstruct.*;

import java.util.List;

/**
 * 简历相关的对象映射器
 * 使用MapStruct自动生成转换代码
 * <p>
 * 注意：JSON字段(strengthsJson, suggestionsJson)需要在Service层手动处理
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ResumeMapper {

    // ========== ScoreDetail 映射 ==========

    /**
     * 将实体基础字段映射到DTO的ScoreDetail
     */
    @Mapping(target = "contentScore", source = "contentScore", qualifiedByName = "nullToZero")
    @Mapping(target = "structureScore", source = "structureScore", qualifiedByName = "nullToZero")
    @Mapping(target = "skillMatchScore", source = "skillMatchScore", qualifiedByName = "nullToZero")
    @Mapping(target = "expressionScore", source = "expressionScore", qualifiedByName = "nullToZero")
    @Mapping(target = "projectScore", source = "projectScore", qualifiedByName = "nullToZero")
    ResumeAnalysisResponse.ScoreDetail toScoreDetail(ResumeAnalysisEntity entity);

    // ========== ResumeListItemDTO 映射 ==========

    /**
     * ResumeEntity 转换为 ResumeListItemDTO
     * 需要额外传入 latestScore, lastAnalyzedAt, interviewCount
     */
    default ResumeListItemDTO toListItemDTO(
        ResumeEntity resume,
        Integer latestScore,
        java.time.LocalDateTime lastAnalyzedAt,
        Integer interviewCount
    ) {
        return new ResumeListItemDTO(
            resume.getId(),
            resume.getOriginalFilename(),
            resume.getFileSize(),
            resume.getUploadedAt(),
            resume.getAccessCount(),
            latestScore,
            lastAnalyzedAt,
            interviewCount,
            null,
            null
        );
    }

    /**
     * 简化版：从 ResumeEntity 直接映射（其他字段为 null）
     */
    @Mapping(target = "filename", source = "originalFilename")
    @Mapping(target = "latestScore", ignore = true)
    @Mapping(target = "lastAnalyzedAt", ignore = true)
    @Mapping(target = "interviewCount", ignore = true)
    ResumeListItemDTO toListItemDTOBasic(ResumeEntity entity);

    // ========== ResumeDetailDTO 映射 ==========

    /**
     * ResumeEntity 转换为 ResumeDetailDTO（不含 analyses 和 interviews）
     */
    @Mapping(target = "filename", source = "originalFilename")
    @Mapping(target = "analyses", ignore = true)
    @Mapping(target = "interviews", ignore = true)
    ResumeDetailDTO toDetailDTOBasic(ResumeEntity entity);

    // ========== AnalysisHistoryDTO 映射 ==========

    /**
     * ResumeAnalysisEntity 转换为 AnalysisHistoryDTO
     * 注意：strengths 和 suggestions 需要在 Service 层从 JSON 解析后传入
     */
    @Mapping(target = "strengths", source = "strengths")
    @Mapping(target = "suggestions", source = "suggestions")
    ResumeDetailDTO.AnalysisHistoryDTO toAnalysisHistoryDTO(
        ResumeAnalysisEntity entity,
        List<String> strengths,
        List<Object> suggestions
    );

    /**
     * 批量转换（需要在 Service 层处理 JSON）
     */
    default List<ResumeDetailDTO.AnalysisHistoryDTO> toAnalysisHistoryDTOList(
        List<ResumeAnalysisEntity> entities,
        java.util.function.Function<ResumeAnalysisEntity, List<String>> strengthsExtractor,
        java.util.function.Function<ResumeAnalysisEntity, List<Object>> suggestionsExtractor
    ) {
        return entities.stream()
            .map(e -> toAnalysisHistoryDTO(e, strengthsExtractor.apply(e), suggestionsExtractor.apply(e)))
            .toList();
    }

    // ========== ResumeAnalysisEntity 创建映射 ==========

    /**
     * 从 ResumeAnalysisResponse 创建 ResumeAnalysisEntity
     * 注意：JSON 字段和 Resume 关联需要在 Service 层设置
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "resume", ignore = true)
    @Mapping(target = "strengthsJson", ignore = true)
    @Mapping(target = "suggestionsJson", ignore = true)
    @Mapping(target = "analyzedAt", ignore = true)
    @Mapping(target = "contentScore", source = "scoreDetail.contentScore")
    @Mapping(target = "structureScore", source = "scoreDetail.structureScore")
    @Mapping(target = "skillMatchScore", source = "scoreDetail.skillMatchScore")
    @Mapping(target = "expressionScore", source = "scoreDetail.expressionScore")
    @Mapping(target = "projectScore", source = "scoreDetail.projectScore")
    ResumeAnalysisEntity toAnalysisEntity(ResumeAnalysisResponse response);

    /**
     * 更新已有的 ResumeAnalysisEntity
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "resume", ignore = true)
    @Mapping(target = "strengthsJson", ignore = true)
    @Mapping(target = "suggestionsJson", ignore = true)
    @Mapping(target = "analyzedAt", ignore = true)
    @Mapping(target = "contentScore", source = "scoreDetail.contentScore")
    @Mapping(target = "structureScore", source = "scoreDetail.structureScore")
    @Mapping(target = "skillMatchScore", source = "scoreDetail.skillMatchScore")
    @Mapping(target = "expressionScore", source = "scoreDetail.expressionScore")
    @Mapping(target = "projectScore", source = "scoreDetail.projectScore")
    void updateAnalysisEntity(ResumeAnalysisResponse response, @MappingTarget ResumeAnalysisEntity entity);

    // ========== 工具方法 ==========

    @Named("nullToZero")
    default int nullToZero(Integer value) {
        return value != null ? value : 0;
    }
}
