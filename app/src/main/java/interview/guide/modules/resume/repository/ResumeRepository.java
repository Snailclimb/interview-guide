package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 简历Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {
    
    /**
     * 根据文件哈希查找简历（用于去重）
     */
    Optional<ResumeEntity> findByFileHash(String fileHash);
    
    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);

    /**
     * 按版本号升序查询版本族内所有简历（首版本 + 共享 groupId 的后续版本）
     *
     * @param versionGroupId 版本族ID（首版本 id）
     */
    List<ResumeEntity> findByVersionGroupIdOrderByVersionNoAsc(Long versionGroupId);

    /**
     * 查询版本族内最大版本号
     */
    Optional<ResumeEntity> findFirstByVersionGroupIdOrderByVersionNoDesc(Long versionGroupId);

    /**
     * 查询某个版本族内是否已存在相同内容的简历（用于上传新版本时的族内去重）
     */
    List<ResumeEntity> findByVersionGroupIdAndFileHash(Long versionGroupId, String fileHash);
}
