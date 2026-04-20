package interview.guide.modules.review;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.infrastructure.mapper.ReviewMapper;
import interview.guide.modules.review.model.ArtifactDTO;
import interview.guide.modules.review.model.ArtifactType;
import interview.guide.modules.review.model.AnalyzeRequest;
import interview.guide.modules.review.model.InterviewReviewEntity;
import interview.guide.modules.review.model.ReviewDetailDTO;
import interview.guide.modules.review.model.ReviewListItemDTO;
import interview.guide.modules.review.model.UpdateArtifactRequest;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import interview.guide.modules.review.service.ReviewAnalysisService;
import interview.guide.modules.review.service.ReviewChatService;
import interview.guide.modules.review.service.ReviewListService;
import interview.guide.modules.review.service.ReviewSearchService;
import interview.guide.modules.review.service.ReviewUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试复盘管理控制器
 * 提供复盘记录的上传、AI 分析、对话精修、列表查询和搜索功能
 */
@Slf4j
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewUploadService uploadService;
    private final ReviewAnalysisService analysisService;
    private final ReviewChatService chatService;
    private final ReviewListService listService;
    private final ReviewSearchService searchService;
    private final ReviewMapper reviewMapper;

    /**
     * 上传面试转录稿
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("scheduleId") Long scheduleId) {
        log.info("上传复盘转录稿: scheduleId={}", scheduleId);
        InterviewReviewEntity entity = uploadService.uploadAndCreate(file, scheduleId);
        Map<String, Object> result = new HashMap<>();
        result.put("reviewId", entity.getId());
        return Result.success(result);
    }

    /**
     * 触发 AI 分析
     */
    @PostMapping("/{reviewId}/analyze")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 20)
    public Result<ArtifactDTO> analyze(
            @PathVariable Long reviewId,
            @RequestBody @Valid AnalyzeRequest request) {
        log.info("触发 AI 分析: reviewId={}, type={}", reviewId, request.type());
        ReviewArtifactEntity artifact = analysisService.analyze(reviewId, request.type(), null);
        return Result.success(reviewMapper.toArtifactDTO(artifact));
    }

    /**
     * 手动编辑分析成果
     */
    @PutMapping("/{reviewId}/artifacts/{artifactId}")
    public Result<ArtifactDTO> updateArtifact(
            @PathVariable Long reviewId,
            @PathVariable Long artifactId,
            @RequestBody @Valid UpdateArtifactRequest request) {
        log.info("手动编辑成果: reviewId={}, artifactId={}", reviewId, artifactId);
        ReviewArtifactEntity artifact = chatService.updateArtifactContent(reviewId, artifactId, request.content());
        return Result.success(reviewMapper.toArtifactDTO(artifact));
    }

    /**
     * 查询复盘列表（支持筛选）
     */
    @GetMapping("/list")
    public Result<List<ReviewListItemDTO>> list(
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDateTime startDateTime = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate == null ? null : endDate.atTime(LocalTime.MAX);
        return Result.success(listService.listReviews(companyName, startDateTime, endDateTime));
    }

    /**
     * 获取复盘详情
     */
    @GetMapping("/{reviewId}")
    public Result<ReviewDetailDTO> getDetail(@PathVariable Long reviewId) {
        return Result.success(listService.getDetail(reviewId));
    }

    /**
     * 轻量级状态查询（仅返回 artifact 状态，用于前端轮询）
     */
    @GetMapping("/{reviewId}/status")
    public Result<List<ArtifactDTO>> getStatus(@PathVariable Long reviewId) {
        return Result.success(listService.getArtifactStatuses(reviewId));
    }

    /**
     * 搜索复盘记录
     */
    @GetMapping("/search")
    public Result<List<ReviewListItemDTO>> search(@RequestParam String query) {
        return Result.success(searchService.searchReviews(query));
    }

    /**
     * 删除复盘记录
     */
    @DeleteMapping("/{reviewId}")
    public Result<Void> delete(@PathVariable Long reviewId) {
        log.info("删除复盘记录: reviewId={}", reviewId);
        listService.deleteReview(reviewId);
        return Result.success(null);
    }

    /**
     * 终止分析任务，重置所有 PROCESSING/PENDING 的 artifact 状态
     */
    @PostMapping("/{reviewId}/cancel")
    public Result<Void> cancelAnalysis(@PathVariable Long reviewId) {
        log.info("终止分析任务: reviewId={}", reviewId);
        analysisService.cancelAnalysis(reviewId);
        return Result.success(null);
    }
}
