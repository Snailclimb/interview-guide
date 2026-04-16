package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequest {
    private String roleType; // 向后兼容，新请求可不传（服务层会用 skillId 填充）
    private String skillId;  // 模板 ID，如 "java-backend", "bytedance-backend" 等
    private String difficulty; // "junior", "mid", "senior"
    private String customJdText;
    private Long resumeId;

    @Builder.Default
    private Boolean introEnabled = false;
    @Builder.Default
    private Boolean techEnabled = true;
    @Builder.Default
    private Boolean projectEnabled = true;
    @Builder.Default
    private Boolean hrEnabled = true;
    @Builder.Default
    private Integer plannedDuration = 30;

    @Builder.Default
    private String llmProvider = "dashscope";
}
