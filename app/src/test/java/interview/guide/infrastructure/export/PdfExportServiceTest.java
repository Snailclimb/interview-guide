package interview.guide.infrastructure.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData;
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("PDF 报告导出")
class PdfExportServiceTest {

  private final PdfExportService service = new PdfExportService(JsonMapper.builder().build());

  @ParameterizedTest
  @ValueSource(strings = {"①②③", "④⑤⑥⑦⑧⑨", "⑩⑪⑳", "⓪❶❷❸"})
  @DisplayName("面试报告应保留问题、回答和评价中的带圈编号")
  void shouldPreserveNumberedSymbolsInInterviewReport(String symbols) throws IOException {
    InterviewSessionEntity session = session();
    session.setOverallFeedback("总体评价：" + symbols);
    session.setStrengthsJson("[\"优势：" + symbols + "\"]");
    session.setImprovementsJson("[\"改进：" + symbols + "\"]");
    InterviewAnswerEntity answer = new InterviewAnswerEntity();
    answer.setQuestionIndex(0);
    answer.setQuestion("问题：" + symbols);
    answer.setUserAnswer("回答：" + symbols);
    answer.setFeedback("评价：" + symbols);
    answer.setReferenceAnswer("先归因，再优化：" + symbols);
    answer.setScore(85);
    session.setAnswers(List.of(answer));

    byte[] report = service.exportInterviewReport(session);

    assertThat(extractText(report)).contains(
        "总体评价：" + symbols, "优势：" + symbols, "改进：" + symbols,
        "问题：" + symbols, "回答：" + symbols, "评价：" + symbols,
        "先归因，再优化：" + symbols);
    assertFontsEmbedded(report);
  }

  @Test
  @DisplayName("带圈编号后的中英文和数字应继续使用原来的中文字体")
  void shouldKeepPrimaryFontAfterNumberedSymbols() throws IOException {
    InterviewSessionEntity session = session();
    session.setOverallFeedback("①Java②123③中文");

    byte[] report = service.exportInterviewReport(session);
    assertThat(extractText(report)).contains("①Java②123③中文");

    try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(report)))) {
      var listener = new IEventListener() {
        @Override
        public void eventOccurred(IEventData data, EventType type) {
          if (type != EventType.RENDER_TEXT) {
            return;
          }
          for (TextRenderInfo character : ((TextRenderInfo) data).getCharacterRenderInfos()) {
            if (character.getText().matches("[A-Za-z0-9中文]")) {
              assertThat(character.getFont().getFontProgram().getFontNames().getFontName())
                  .endsWith("ZhuqueFangsong-Regular");
            }
          }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
          return Set.of(EventType.RENDER_TEXT);
        }
      };
      for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
        new PdfCanvasProcessor(listener).processPageContent(pdf.getPage(page));
      }
    }
  }

  @Test
  @DisplayName("简历分析报告也应保留带圈编号及普通中英文内容")
  void shouldPreserveNumberedSymbolsInResumeAnalysis() throws IOException {
    ResumeEntity resume = new ResumeEntity();
    resume.setOriginalFilename("Java简历.pdf");
    ResumeAnalysisResponse analysis = new ResumeAnalysisResponse(85,
        new ResumeAnalysisResponse.ScoreDetail(10, 12, 18, 8, 37),
        "摘要：①Java，②Spring，③API", List.of("优势：①②③"),
        List.of(new ResumeAnalysisResponse.Suggestion("项目经验", "高",
            "问题：①②③", "建议：①先归因，②再优化，③验证")), null);

    byte[] report = service.exportResumeAnalysis(resume, analysis);

    assertThat(extractText(report)).contains("简历分析报告", "Java简历.pdf", "85 / 100",
        "摘要：①Java，②Spring，③API", "优势：①②③", "问题：①②③",
        "建议：①先归因，②再优化，③验证");
    assertFontsEmbedded(report);
  }

  @Test
  @DisplayName("普通报告应继续保留中文、英文、箭头和原有 emoji 清理行为")
  void shouldPreserveExistingTextAndAllowRepeatedExports() throws IOException {
    InterviewSessionEntity session = session();
    session.setOverallFeedback("中文 Java API → 验证😀");

    for (int i = 0; i < 2; i++) {
      byte[] report = service.exportInterviewReport(session);
      assertThat(extractText(report)).contains("模拟面试报告", "中文 Java API → 验证")
          .doesNotContain("😀");
      assertFontsEmbedded(report);
    }
  }

  private InterviewSessionEntity session() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("pdf-symbol-regression");
    session.setTotalQuestions(1);
    session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    return session;
  }

  private String extractText(byte[] bytes) throws IOException {
    try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
      StringBuilder text = new StringBuilder();
      for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
        text.append(PdfTextExtractor.getTextFromPage(pdf.getPage(page)));
      }
      return text.toString();
    }
  }

  private void assertFontsEmbedded(byte[] bytes) throws IOException {
    try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
      for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
        var fonts = pdf.getPage(page).getResources().getResource(PdfName.Font);
        assertThat(fonts).isNotNull();
        for (PdfName name : fonts.keySet()) {
          var font = fonts.getAsDictionary(name);
          var descendants = font.getAsArray(PdfName.DescendantFonts);
          var descriptor = descendants.getAsDictionary(0).getAsDictionary(PdfName.FontDescriptor);
          assertThat(descriptor.getAsStream(PdfName.FontFile2)).isNotNull();
        }
      }
    }
  }
}
