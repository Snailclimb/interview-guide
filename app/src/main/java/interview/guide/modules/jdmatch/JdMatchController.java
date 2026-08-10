package interview.guide.modules.jdmatch;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.jdmatch.model.JdMatchCreateResponse;
import interview.guide.modules.jdmatch.model.JdMatchDetailDTO;
import interview.guide.modules.jdmatch.model.JdMatchListItemDTO;
import interview.guide.modules.jdmatch.model.JdMatchRequest;
import interview.guide.modules.jdmatch.service.JdMatchCreateService;
import interview.guide.modules.jdmatch.service.JdMatchHistoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * JD 匹配分析控制器
 * 简历与岗位 JD 匹配度检测
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "JD匹配分析", description = "简历与岗位JD匹配度检测")
public class JdMatchController {

    private final JdMatchCreateService createService;
    private final JdMatchHistoryService historyService;

    /**
     * 创建 JD 匹配分析任务（异步执行，返回ID用于轮询状态）
     *
     * @param request 匹配请求（resumeId + jdText）
     * @return 创建结果，包含分析ID和初始状态
     */
    @PostMapping("/api/jd-matches")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<JdMatchCreateResponse> createMatch(@RequestBody JdMatchRequest request) {
        JdMatchCreateResponse response = createService.createMatch(request);
        return Result.success(response);
    }

    /**
     * 获取所有 JD 匹配分析记录
     */
    @GetMapping("/api/jd-matches")
    public Result<List<JdMatchListItemDTO>> getAllMatches() {
        return Result.success(historyService.getAllMatches());
    }

    /**
     * 获取 JD 匹配分析详情（含状态，前端可轮询）
     *
     * @param id 分析ID
     * @return 匹配分析详情
     */
    @GetMapping("/api/jd-matches/{id}")
    public Result<JdMatchDetailDTO> getMatchDetail(@PathVariable Long id) {
        return Result.success(historyService.getMatchDetail(id));
    }

    /**
     * 删除 JD 匹配分析记录
     *
     * @param id 分析ID
     * @return 删除结果
     */
    @DeleteMapping("/api/jd-matches/{id}")
    public Result<Void> deleteMatch(@PathVariable Long id) {
        historyService.deleteMatch(id);
        return Result.success(null);
    }
}
