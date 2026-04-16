package interview.guide.modules.interviewschedule.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CreateInterviewRequest {
    @NotBlank(message = "公司名称不能为空")
    private String companyName;

    @NotBlank(message = "岗位不能为空")
    private String position;

    @NotNull(message = "面试时间不能为空")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private java.time.LocalDateTime interviewTime;

    private String interviewType; // ONSITE, VIDEO, PHONE

    private String meetingLink;

    private Integer roundNumber = 1;

    private String interviewer;

    private String notes;
}
