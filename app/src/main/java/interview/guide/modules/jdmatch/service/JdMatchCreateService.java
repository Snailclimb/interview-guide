package interview.guide.modules.jdmatch.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.jdmatch.listener.JdMatchStreamProducer;
import interview.guide.modules.jdmatch.model.JdMatchCreateResponse;
import interview.guide.modules.jdmatch.model.JdMatchEntity;
import interview.guide.modules.jdmatch.model.JdMatchRequest;
import interview.guide.modules.jdmatch.repository.JdMatchRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * JD 匹配分析创建服务
 * 创建匹配任务并发送到 Redis Stream 异步处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdMatchCreateService {

    private final ResumeRepository resumeRepository;
    private final JdMatchRepository jdMatchRepository;
    private final JdMatchStreamProducer jdMatchStreamProducer;

    /**
     * 创建 JD 匹配分析任务（异步执行）
     *
     * @param request 匹配请求
     * @return 创建结果（含ID和初始状态）
     */
    public JdMatchCreateResponse createMatch(JdMatchRequest request) {
        // 1. 校验参数
        if (request.jdText() == null || request.jdText().isBlank()) {
            throw new BusinessException(ErrorCode.JD_TEXT_EMPTY, "JD文本不能为空");
        }
        if (request.resumeId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要匹配的简历");
        }

        // 2. 校验简历存在且有可分析的文本
        ResumeEntity resume = resumeRepository.findById(request.resumeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        String resumeText = resume.getResumeText();
        if (resumeText == null || resumeText.isBlank()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "该简历没有可用的文本内容，请重新上传简历");
        }

        // 3. 创建待处理实体
        JdMatchEntity entity = new JdMatchEntity();
        entity.setResumeId(resume.getId());
        entity.setResumeFilename(resume.getOriginalFilename());
        entity.setJdTitle(trimToNull(request.jdTitle()));
        entity.setJdText(request.jdText());
        entity.setStatus(AsyncTaskStatus.PENDING);
        JdMatchEntity saved = jdMatchRepository.save(entity);
        log.info("JD匹配分析任务已创建: id={}, resumeId={}, jdTitle={}",
            saved.getId(), resume.getId(), entity.getJdTitle());

        // 4. 发送异步任务到 Redis Stream
        jdMatchStreamProducer.sendMatchTask(
            saved.getId(), resumeText, entity.getJdTitle(), request.jdText());

        return new JdMatchCreateResponse(saved.getId(), AsyncTaskStatus.PENDING.name());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
