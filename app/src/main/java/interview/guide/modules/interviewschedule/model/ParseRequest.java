package interview.guide.modules.interviewschedule.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ParseRequest {
    @NotBlank(message = "文本不能为空")
    private String rawText;

    private String source; // feishu, tencent, zoom, other
}
