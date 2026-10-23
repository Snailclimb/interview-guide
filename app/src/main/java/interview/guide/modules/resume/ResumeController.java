package interview.guide.modules.resume;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeEditorDTO;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import interview.guide.modules.resume.model.SaveResumeEditorRequest;
import interview.guide.modules.resume.service.ResumeDeleteService;
import interview.guide.modules.resume.service.ResumeEditorService;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumeFileExportService;
import interview.guide.modules.resume.service.ResumeUploadService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 简历控制器
 * Resume Controller for upload and analysis
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "简历管理", description = "简历上传、分析、导出与删除")
public class ResumeController {

    private final ResumeUploadService uploadService;
    private final ResumeDeleteService deleteService;
    private final ResumeHistoryService historyService;
    private final ResumeEditorService editorService;
    private final ResumeFileExportService fileExportService;

    /**
     * 上传简历并获取分析结果
     *
     * @param file 简历文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 简历分析结果，包含评分和建议
     */
    @PostMapping(value = "/api/resumes/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<Map<String, Object>> uploadAndAnalyze(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = uploadService.uploadAndAnalyze(file);
        boolean isDuplicate = (Boolean) result.get("duplicate");
        if (isDuplicate) {
            return Result.success("检测到相同简历，已返回历史分析结果", result);
        }
        return Result.success(result);
    }

    /**
     * 获取所有简历列表
     */
    @GetMapping("/api/resumes")
    public Result<List<ResumeListItemDTO>> getAllResumes() {
        List<ResumeListItemDTO> resumes = historyService.getAllResumes();
        return Result.success(resumes);
    }

    /**
     * 获取简历详情（包含分析历史）
     */
    @GetMapping("/api/resumes/{id}/detail")
    public Result<ResumeDetailDTO> getResumeDetail(@PathVariable Long id) {
        ResumeDetailDTO detail = historyService.getResumeDetail(id);
        return Result.success(detail);
    }

    /** 获取简历编辑版；首次打开时返回原始文本作为可编辑起点。 */
    @GetMapping("/api/resumes/{id}/editor")
    public Result<ResumeEditorDTO> getEditor(@PathVariable Long id) {
        return Result.success(editorService.getEditor(id));
    }

    /** 保存手动编辑的简历草稿，不会覆盖原始上传文件。 */
    @org.springframework.web.bind.annotation.PutMapping("/api/resumes/{id}/editor")
    public Result<ResumeEditorDTO> saveEditor(
        @PathVariable Long id,
        @Valid @RequestBody SaveResumeEditorRequest request
    ) {
        return Result.success(editorService.save(id, request));
    }

    /** 根据原文、当前草稿和最新分析建议生成可继续编辑的 AI 草稿。 */
    @PostMapping("/api/resumes/{id}/editor/ai-optimize")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<ResumeEditorDTO> optimizeEditor(
        @PathVariable Long id,
        @RequestBody(required = false) @Valid SaveResumeEditorRequest request
    ) {
        return Result.success(editorService.optimize(id, request));
    }

    /** 显式将编辑版应用为后续模拟面试使用的简历文本。 */
    @PostMapping("/api/resumes/{id}/editor/apply")
    public Result<ResumeEditorDTO> applyEditor(@PathVariable Long id) {
        return Result.success(editorService.applyToInterview(id));
    }

    /** 导出当前简历编辑版为 PDF。 */
    @GetMapping("/api/resumes/{id}/editor/export/pdf")
    public ResponseEntity<byte[]> exportResumePdf(@PathVariable Long id) {
        return attachment(fileExportService.exportPdf(id));
    }

    /** 导出当前简历编辑版为 Word。 */
    @GetMapping("/api/resumes/{id}/editor/export/docx")
    public ResponseEntity<byte[]> exportResumeDocx(@PathVariable Long id) {
        return attachment(fileExportService.exportDocx(id));
    }

    private ResponseEntity<byte[]> attachment(ResumeFileExportService.ExportResult result) {
        String filename = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
            .contentType(MediaType.parseMediaType(result.contentType()))
            .body(result.bytes());
    }

    /**
     * 导出简历分析报告为PDF
     */
    @GetMapping("/api/resumes/{id}/export")
    public ResponseEntity<byte[]> exportAnalysisPdf(@PathVariable Long id) {
        try {
            var result = historyService.exportAnalysisPdf(id);
            String filename = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(result.pdfBytes());
        } catch (Exception e) {
            log.error("导出PDF失败: resumeId={}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除简历
     *
     * @param id 简历ID
     * @return 删除结果
     */
    @DeleteMapping("/api/resumes/{id}")
    public Result<Void> deleteResume(@PathVariable Long id) {
        deleteService.deleteResume(id);
        return Result.success(null);
    }

    /**
     * 重新分析简历（手动重试）
     * 用于分析失败后的重试
     *
     * @param id 简历ID
     * @return 结果
     */
    @PostMapping("/api/resumes/{id}/reanalyze")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> reanalyze(@PathVariable Long id) {
        uploadService.reanalyze(id);
        return Result.success(null);
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/api/resumes/health")
    public Result<Map<String, String>> health() {
        return Result.success(Map.of(
            "status", "UP",
            "service", "AI Interview Platform - Resume Service"
        ));
    }

}
