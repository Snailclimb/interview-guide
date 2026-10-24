package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.modules.resume.model.ResumeEditorContent;
import interview.guide.modules.resume.model.ResumeEditorDTO;
import interview.guide.modules.resume.model.ResumeEditorSource;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ResumeFileExportServiceTest {

    private final ResumeRepository resumeRepository = mock(ResumeRepository.class);
    private final ResumeEditorService editorService = mock(ResumeEditorService.class);
    private final ResumeFileExportService service = new ResumeFileExportService(
        resumeRepository,
        editorService,
        new PdfExportService(JsonMapper.builder().build())
    );

    @Test
    void shouldExportStructuredResumeAsValidDocx() throws IOException {
        ResumeEntity resume = resume();
        ResumeEditorContent content = content();
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));
        when(editorService.getEditor(1L)).thenReturn(editor(content));

        ResumeFileExportService.ExportResult result = service.exportDocx(1L);

        assertThat(result.filename()).isEqualTo("孙扬_编辑版.docx");
        assertThat(result.contentType())
            .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(result.bytes()))) {
            String document = null;
            java.util.Set<String> entries = new java.util.HashSet<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                if (entry.getName().equals("word/document.xml")) {
                    document = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            assertThat(entries).contains("[Content_Types].xml", "_rels/.rels", "word/document.xml",
                "word/styles.xml", "word/settings.xml", "word/_rels/document.xml.rels");
            assertThat(document).contains("孙扬", "专业技能", "Java &amp; Spring", "工作经历", "项目经历")
                .contains("w:tbl", "ResumeName", "ResumeHeading", "EFF6FC", "256398")
                .doesNotContain("ListBullet", "Heading1");
        }
    }

    @Test
    void shouldExportStructuredResumeAsPdf() {
        when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume()));
        when(editorService.getEditor(1L)).thenReturn(editor(content()));

        ResumeFileExportService.ExportResult result = service.exportPdf(1L);

        assertThat(result.filename()).isEqualTo("孙扬_编辑版.pdf");
        assertThat(result.contentType()).isEqualTo("application/pdf");
        assertThat(result.bytes()).startsWith(new byte[]{0x25, 0x50, 0x44, 0x46});
    }

    private ResumeEntity resume() {
        ResumeEntity resume = new ResumeEntity();
        resume.setId(1L);
        resume.setOriginalFilename("孙扬_简历.pdf");
        resume.setUploadedAt(LocalDateTime.of(2026, 10, 23, 12, 0));
        resume.setResumeText("原始简历");
        return resume;
    }

    private ResumeEditorDTO editor(ResumeEditorContent content) {
        return new ResumeEditorDTO(1L, 1, ResumeEditorSource.MANUAL,
            LocalDateTime.of(2026, 10, 23, 12, 1), content, "原始简历", false, false);
    }

    private ResumeEditorContent content() {
        return new ResumeEditorContent("孙扬", "sun@example.com", "15600000000", "广州",
            "Java 后端开发", "Java & Spring", "华为\n· 负责后端开发", "项目 A\n· 提升稳定性",
            "北京化工大学", "重视交付质量", null);
    }
}
