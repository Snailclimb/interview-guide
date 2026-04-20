package interview.guide.modules.review.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.review.listener.ReviewAnalyzeStreamProducer;
import interview.guide.modules.review.model.ArtifactType;
import interview.guide.modules.review.model.InterviewReviewEntity;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import interview.guide.modules.review.model.ReviewStatus;
import interview.guide.modules.review.repository.InterviewReviewRepository;
import interview.guide.modules.review.repository.ReviewArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewUploadService {

    private static final long MAX_TRANSCRIPT_SIZE = 5 * 1024 * 1024;

    private final InterviewReviewRepository reviewRepository;
    private final InterviewScheduleRepository scheduleRepository;
    private final ReviewArtifactRepository artifactRepository;
    private final FileValidationService fileValidationService;
    private final DocumentParseService documentParseService;
    private final FileStorageService fileStorageService;
    private final ReviewAnalyzeStreamProducer reviewAnalyzeProducer;
    private final ReviewDomainService reviewDomainService;
    private final TransactionTemplate transactionTemplate;

    public InterviewReviewEntity uploadAndCreate(MultipartFile file, Long scheduleId) {
        InterviewScheduleEntity schedule = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.BAD_REQUEST, "面试日程不存在: " + scheduleId));

        fileValidationService.validateFile(file, MAX_TRANSCRIPT_SIZE, "转录稿");
        String transcriptText = documentParseService.parseContent(file);
        String storageKey = fileStorageService.uploadReview(file);
        String storageUrl = fileStorageService.getFileUrl(storageKey);

        try {
            InterviewReviewEntity review = transactionTemplate.execute(status ->
                createReviewRecord(scheduleId, schedule, transcriptText, storageKey, storageUrl)
            );
            if (review == null) {
                throw new BusinessException(ErrorCode.REVIEW_UPLOAD_FAILED, "创建复盘记录失败");
            }

            for (ArtifactType artifactType : ArtifactType.values()) {
                reviewAnalyzeProducer.sendAnalyzeTask(review.getId(), artifactType);
            }
            log.info("Created review: reviewId={}, scheduleId={}, companyName={}",
                review.getId(), scheduleId, review.getCompanyName());
            return review;
        } catch (RuntimeException e) {
            cleanupUploadedFile(storageKey);
            throw e;
        }
    }

    private InterviewReviewEntity createReviewRecord(
        Long scheduleId,
        InterviewScheduleEntity schedule,
        String transcriptText,
        String storageKey,
        String storageUrl
    ) {
        InterviewReviewEntity review = new InterviewReviewEntity();
        review.setScheduleId(scheduleId);
        review.setTranscriptStorageKey(storageKey);
        review.setTranscriptStorageUrl(storageUrl);
        review.setTranscriptText(transcriptText);
        review.setCompanyName(schedule.getCompanyName());
        review.setPosition(schedule.getPosition());
        review.setRoundNumber(schedule.getRoundNumber());
        review.setInterviewDate(schedule.getInterviewTime());
        review.setStatus(ReviewStatus.DRAFT);
        review.setAnalysisCancelled(false);

        review = reviewRepository.save(review);
        createInitialArtifacts(review);
        return review;
    }

    private void createInitialArtifacts(InterviewReviewEntity review) {
        for (ArtifactType artifactType : ArtifactType.values()) {
            ReviewArtifactEntity artifact = reviewDomainService.newArtifact(review, artifactType);
            artifact.setStatus(AsyncTaskStatus.PENDING);
            artifact.setError(null);
            artifactRepository.save(artifact);
        }
    }

    private void cleanupUploadedFile(String storageKey) {
        try {
            fileStorageService.deleteReview(storageKey);
            log.info("Cleaned up uploaded review file after failure: storageKey={}", storageKey);
        } catch (Exception cleanupError) {
            log.error("Failed to clean up uploaded review file: storageKey={}", storageKey, cleanupError);
        }
    }
}
