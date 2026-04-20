package interview.guide.infrastructure.mapper;

import interview.guide.modules.review.model.ArtifactDTO;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import interview.guide.modules.review.model.ReviewDetailDTO;
import interview.guide.modules.review.model.InterviewReviewEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 面试复盘相关的对象映射器
 * 使用 MapStruct 自动生成转换代码
 * <p>
 * 注意：ReviewDetailDTO.artifacts 需要在 Service 层手动设置
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ReviewMapper {

    /**
     * InterviewReviewEntity 转换为 ReviewDetailDTO
     * artifacts 字段需在 Service 层手动填充
     */
    @Mapping(target = "artifacts", ignore = true)
    ReviewDetailDTO toDetailDTO(InterviewReviewEntity entity);

    /**
     * ReviewArtifactEntity 转换为 ArtifactDTO
     */
    ArtifactDTO toArtifactDTO(ReviewArtifactEntity entity);

    /**
     * 批量转换 ReviewArtifactEntity 为 ArtifactDTO
     */
    List<ArtifactDTO> toArtifactDTOs(List<ReviewArtifactEntity> entities);
}
