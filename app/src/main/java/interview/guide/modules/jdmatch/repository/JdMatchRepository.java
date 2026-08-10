package interview.guide.modules.jdmatch.repository;

import interview.guide.modules.jdmatch.model.JdMatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * JD 匹配分析仓储
 */
public interface JdMatchRepository extends JpaRepository<JdMatchEntity, Long> {

    /**
     * 按创建时间倒序查询所有匹配分析
     */
    List<JdMatchEntity> findAllByOrderByCreatedAtDesc();
}
