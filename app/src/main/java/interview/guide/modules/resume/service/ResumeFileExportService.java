package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.model.ResumeEditorContent;
import interview.guide.modules.resume.model.ResumeEditorDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.infrastructure.export.PdfExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 将当前保存的简历编辑版导出为可下载的 PDF 或 DOCX 文件。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeFileExportService {

    private static final String DOCX_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ResumeRepository resumeRepository;
    private final ResumeEditorService editorService;
    private final PdfExportService pdfExportService;

    public ExportResult exportPdf(Long resumeId) {
        ExportContent content = loadContent(resumeId);
        byte[] bytes = pdfExportService.exportResume(content.editor().content(), content.title());
        return new ExportResult(bytes, content.filename("pdf"), "application/pdf");
    }

    public ExportResult exportDocx(Long resumeId) {
        ExportContent content = loadContent(resumeId);
        try {
            byte[] bytes = createDocx(content.editor().content(), content.title());
            return new ExportResult(bytes, content.filename("docx"), DOCX_CONTENT_TYPE);
        } catch (IOException e) {
            log.error("导出简历 Word 失败: resumeId={}", resumeId, e);
            throw new BusinessException(ErrorCode.EXPORT_DOCUMENT_FAILED, "导出 Word 简历失败");
        }
    }

    private ExportContent loadContent(Long resumeId) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        ResumeEditorDTO editor = editorService.getEditor(resumeId);
        if (editor.content() == null || editor.content().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "没有可导出的简历内容");
        }
        return new ExportContent(resume, editor, title(editor, resume));
    }

    private String title(ResumeEditorDTO editor, ResumeEntity resume) {
        String name = editor.content().name();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String filename = resume.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return "个人简历";
        }
        int extension = filename.lastIndexOf('.');
        return (extension > 0 ? filename.substring(0, extension) : filename).trim();
    }

    private byte[] createDocx(ResumeEditorContent content, String title) throws IOException {
        List<Block> blocks = blocks(content, title);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            addEntry(zip, "[Content_Types].xml", contentTypes());
            addEntry(zip, "_rels/.rels", rootRelationships());
            addEntry(zip, "word/document.xml", documentXml(blocks));
            addEntry(zip, "word/styles.xml", stylesXml());
            addEntry(zip, "word/settings.xml", settingsXml());
            addEntry(zip, "word/_rels/document.xml.rels", documentRelationships());
        }
        return output.toByteArray();
    }

    private void addEntry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private List<Block> blocks(ResumeEditorContent content, String title) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(BlockType.TITLE, title));
        String contact = joinNonBlank(content.email(), content.phone(), content.location());
        if (!contact.isBlank()) {
            blocks.add(new Block(BlockType.SUBTITLE, contact));
        }
        if (content.targetPosition() != null && !content.targetPosition().isBlank()) {
            blocks.add(new Block(BlockType.SUBTITLE, content.targetPosition().trim()));
        }

        addSection(blocks, "专业技能", content.skills());
        addSection(blocks, "工作经历", content.workExperience());
        addSection(blocks, "项目经历", content.projects());
        addSection(blocks, "教育经历", content.education());
        addSection(blocks, "个人评价", content.selfEvaluation());
        if (blocks.size() == 1 && content.rawText() != null && !content.rawText().isBlank()) {
            addRawText(blocks, content.rawText());
        }
        return blocks;
    }

    private void addSection(List<Block> blocks, String heading, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        blocks.add(new Block(BlockType.HEADING, heading));
        addLines(blocks, value);
    }

    private void addRawText(List<Block> blocks, String value) {
        for (String line : value.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                blocks.add(new Block(BlockType.SPACER, ""));
            } else if (trimmed.startsWith("## ")) {
                blocks.add(new Block(BlockType.HEADING, trimmed.substring(3).trim()));
            } else {
                blocks.add(new Block(isBullet(trimmed) ? BlockType.BULLET : BlockType.BODY,
                    normalizeBullet(trimmed)));
            }
        }
    }

    private void addLines(List<Block> blocks, String value) {
        for (String line : value.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                blocks.add(new Block(isBullet(trimmed) ? BlockType.BULLET : BlockType.BODY,
                    normalizeBullet(trimmed)));
            }
        }
    }

    private boolean isBullet(String value) {
        return value.startsWith("·") || value.startsWith("•") || value.startsWith("-")
            || value.startsWith("*") || value.matches("^\\d+[.)].*");
    }

    private String normalizeBullet(String value) {
        if (value.startsWith("·") || value.startsWith("•")) {
            return value.substring(1).trim();
        }
        if (value.startsWith("- ") || value.startsWith("* ")) {
            return value.substring(2).trim();
        }
        return value;
    }

    private String joinNonBlank(String... values) {
        return java.util.Arrays.stream(values)
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .reduce((left, right) -> left + "  |  " + right)
            .orElse("");
    }

    private String documentXml(List<Block> blocks) {
        StringBuilder body = new StringBuilder();
        for (Block block : blocks) {
            if (block.type() == BlockType.SPACER) {
                body.append(paragraph("", "Normal"));
            } else if (block.type() == BlockType.BULLET) {
                body.append(paragraph("• " + block.text(), "ListBullet"));
            } else {
                String style = switch (block.type()) {
                    case TITLE -> "Title";
                    case SUBTITLE -> "Subtitle";
                    case HEADING -> "Heading1";
                    default -> "Normal";
                };
                body.append(paragraph(block.text(), style));
            }
        }
        body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1200\" w:right=\"1200\" w:bottom=\"1200\" w:left=\"1200\"/></w:sectPr>");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + body + "</w:body></w:document>";
    }

    private String paragraph(String text, String style) {
        String escaped = xml(text);
        return "<w:p><w:pPr><w:pStyle w:val=\"" + style + "\"/></w:pPr><w:r><w:rPr>"
            + "<w:rFonts w:ascii=\"Aptos\" w:hAnsi=\"Aptos\" w:eastAsia=\"Microsoft YaHei\"/>"
            + "</w:rPr><w:t xml:space=\"preserve\">" + escaped + "</w:t></w:r></w:p>";
    }

    private String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Aptos\" w:hAnsi=\"Aptos\" w:eastAsia=\"Microsoft YaHei\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults>"
            + style("Normal", "22", "0", "0")
            + style("Title", "36", "0", "240")
            + style("Subtitle", "22", "0", "80")
            + style("Heading1", "26", "240", "100")
            + style("ListBullet", "22", "0", "40")
            + "</w:styles>";
    }

    private String style(String id, String size, String before, String after) {
        return "<w:style w:type=\"paragraph\" w:styleId=\"" + id + "\"><w:name w:val=\"" + id
            + "\"/><w:pPr><w:spacing w:before=\"" + before + "\" w:after=\"" + after + "\" w:line=\"300\"/></w:pPr><w:rPr><w:sz w:val=\""
            + size + "\"/></w:rPr></w:style>";
    }

    private String settingsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:zoom w:percent=\"100\"/></w:settings>";
    }

    private String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/><Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/></Types>";
    }

    private String rootRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";
    }

    private String documentRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>";
    }

    private String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    public record ExportResult(byte[] bytes, String filename, String contentType) {}

    private record ExportContent(ResumeEntity resume, ResumeEditorDTO editor, String title) {
        String filename(String extension) {
            return title.replaceAll("[\\\\/:*?\"<>|]", "_") + "_编辑版." + extension;
        }
    }

    private enum BlockType { TITLE, SUBTITLE, HEADING, BODY, BULLET, SPACER }

    private record Block(BlockType type, String text) {}
}
