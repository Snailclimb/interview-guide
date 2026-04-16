package interview.guide.modules.interview.skill;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/interview/skills")
public class InterviewSkillController {

    private final InterviewSkillService skillService;

    public InterviewSkillController(InterviewSkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public Result<List<InterviewSkillService.SkillDTO>> listSkills() {
        return Result.success(skillService.getAllSkills());
    }

    @GetMapping("/{id}")
    public Result<InterviewSkillService.SkillDTO> getSkill(@PathVariable String id) {
        return Result.success(skillService.getSkill(id));
    }

    @PostMapping("/parse-jd")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<List<InterviewSkillService.CategoryDTO>> parseJd(@Valid @RequestBody ParseJdRequest request) {
        return Result.success(skillService.parseJd(request.jdText()));
    }

    public record ParseJdRequest(@NotBlank String jdText) {}
}
