package interview.guide.modules.jdmatch.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.jdmatch.model.JdMatchDetailDTO;
import interview.guide.modules.jdmatch.model.JdMatchEntity;
import interview.guide.modules.jdmatch.model.JdMatchListItemDTO;
import interview.guide.modules.jdmatch.repository.JdMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * JD 匹配分析历史服务
 * 查询、删除匹配分析记录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdMatchHistoryService {

    private final JdMatchRepository jdMatchRepository;
    private final JdMatchPersistenceService persistenceService;

    /**
     * 获取所有匹配分析记录（按创建时间倒序）
     */
    public List<JdMatchListItemDTO> getAllMatches() {
        return jdMatchRepository.findAllByOrderByCreatedAtDesc().stream()
            .map(persistenceService::toListItemDTO)
            .toList();
    }

    /**
     * 获取匹配分析详情（含状态，用于轮询）
     */
    public JdMatchDetailDTO getMatchDetail(Long id) {
        JdMatchEntity entity = jdMatchRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.JD_MATCH_NOT_FOUND, "JD匹配分析不存在"));
        return persistenceService.toDetailDTO(entity);
    }

    /**
     * 删除匹配分析记录
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMatch(Long id) {
        JdMatchEntity entity = jdMatchRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.JD_MATCH_NOT_FOUND, "JD匹配分析不存在"));
        jdMatchRepository.delete(entity);
        log.info("JD匹配分析记录已删除: id={}", id);
    }
}
