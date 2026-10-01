package interview.guide.infrastructure.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfOutline;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfString;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("面试 PDF 问题书签")
class PdfInterviewBookmarksTest {

  private final PdfExportService service = new PdfExportService(JsonMapper.builder().build());

  @ParameterizedTest
  @MethodSource("questionTitles")
  @DisplayName("书签应使用完整问题内容，保留 Unicode 并将换行转为空格")
  void shouldCreateBookmarkWithFullQuestion(String question, String title) throws IOException {
    InterviewSessionEntity session = session();
    session.setAnswers(List.of(answer(0, question, "回答内容")));

    try (PdfDocument pdf = read(service.exportInterviewReport(session))) {
      assertThat(bookmarks(pdf)).extracting(PdfOutline::getTitle).containsExactly(title);
      assertThat(pdf.getCatalog().getPageMode()).isEqualTo(PdfName.UseOutlines);
      assertDestinationAtHeading(pdf, bookmarks(pdf).getFirst(), "问题 1 [综合]");
    }
  }

  static Stream<Arguments> questionTitles() {
    String longQuestion = "如何设计高并发服务中的缓存与数据库一致性？".repeat(30);
    return Stream.of(
        Arguments.of("  数据库索引\n\t 如何设计？①②  ", "问题 1：数据库索引 如何设计？①②"),
        Arguments.of("How does \"RAG\" retrieval work? 🧠",
            "问题 1：How does \"RAG\" retrieval work? 🧠"),
        Arguments.of(longQuestion, "问题 1：" + longQuestion));
  }

  @Test
  @DisplayName("跨页后书签应定位到实际问题标题，并保持报告中的题目顺序")
  void shouldResolveDestinationsAfterPageBreaks() throws IOException {
    InterviewSessionEntity session = session();
    session.setOverallFeedback("面试复盘概览内容。\n".repeat(40));
    session.setAnswers(List.of(
        answer(0, "请解释数据库事务。", "第一题的长回答内容。\n".repeat(100)),
        answer(2, "怎样排查慢查询？", "第二个记录的长回答内容。\n".repeat(80)),
        answer(5, "如何设计可靠的重试？", "保留中断状态并限制重试次数。")));

    try (PdfDocument pdf = read(service.exportInterviewReport(session))) {
      List<PdfOutline> children = bookmarks(pdf);
      assertThat(children).extracting(PdfOutline::getTitle).containsExactly(
          "问题 1：请解释数据库事务。", "问题 3：怎样排查慢查询？", "问题 6：如何设计可靠的重试？");
      assertThat(pdf.getNumberOfPages()).isGreaterThan(3);
      assertDestinationAtHeading(pdf, pdf.getOutlines(false).getAllChildren().getFirst(), "问答详情");
      assertDestinationAtHeading(pdf, children.get(0), "问题 1 [综合]");
      assertDestinationAtHeading(pdf, children.get(1), "问题 3 [综合]");
      assertDestinationAtHeading(pdf, children.get(2), "问题 6 [综合]");
      assertThat(destinationPage(pdf, children.get(0))).isGreaterThan(1);
      assertThat(destinationPage(pdf, children.get(2)))
          .isGreaterThan(destinationPage(pdf, children.get(0)));
      assertThat(extractText(pdf)).contains("请解释数据库事务。", "怎样排查慢查询？",
          "如何设计可靠的重试？", "保留中断状态并限制重试次数。");
    }
  }

  @Test
  @DisplayName("书签定位的页面应能看到问题正文，题号不能孤立在上一页")
  void shouldKeepQuestionTextOnBookmarkedPage() throws IOException {
    InterviewSessionEntity session = session();
    session.setOverallScore(85);
    session.setOverallFeedback("本报告演示按面试官问题建立 PDF 书签，可在阅读器的目录栏点击问题复盘。");
    InterviewAnswerEntity first = answer(0, "请介绍你的项目，并说明你负责的核心模块。",
        "我负责 API 接口、缓存与异步任务。下面逐项说明项目中的设计与取舍。\n"
            + "针对每个模块，我会说明触发条件、处理步骤和验证结果。\n".repeat(35));
    first.setFeedback("回答覆盖了核心要点，建议结合实际失败场景说明设计取舍。");
    session.setAnswers(List.of(first, answer(1, "如何保证缓存与数据库的数据一致性？", "第二题回答。")));

    try (PdfDocument pdf = read(service.exportInterviewReport(session))) {
      PdfOutline second = bookmarks(pdf).get(1);
      int page = destinationPage(pdf, second);
      assertThat(PdfTextExtractor.getTextFromPage(pdf.getPage(page)))
          .contains("Q: 如何保证缓存与数据库的数据一致性？");
      assertDestinationAtHeading(pdf, second, "问题 2 [综合]");
    }
  }

  @Test
  @DisplayName("重复问题应有独立书签和目标位置，不能互相覆盖")
  void shouldKeepSeparateDestinationsForRepeatedQuestions() throws IOException {
    InterviewSessionEntity session = session();
    session.setAnswers(List.of(
        answer(0, "请介绍你的项目。", "第一轮回答。\n".repeat(80)),
        answer(1, "请介绍你的项目。", "第二轮回答。")));

    try (PdfDocument pdf = read(service.exportInterviewReport(session))) {
      List<PdfOutline> children = bookmarks(pdf);
      assertThat(children).extracting(PdfOutline::getTitle).containsExactly(
          "问题 1：请介绍你的项目。", "问题 2：请介绍你的项目。");
      assertThat(children).extracting(child -> child.getDestination().getPdfObject())
          .doesNotHaveDuplicates();
      assertDestinationAtHeading(pdf, children.get(0), "问题 1 [综合]");
      assertDestinationAtHeading(pdf, children.get(1), "问题 2 [综合]");
      assertThat(destinationPage(pdf, children.get(1)))
          .isGreaterThan(destinationPage(pdf, children.get(0)));
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" \n\t "})
  @DisplayName("缺少问题文字时仍应提供可跳转的题号书签")
  void shouldUseQuestionNumberWhenQuestionIsBlank(String question) throws IOException {
    InterviewSessionEntity session = session();
    session.setAnswers(List.of(answer(0, question, "用户回答")));

    try (PdfDocument pdf = read(service.exportInterviewReport(session))) {
      assertThat(bookmarks(pdf)).extracting(PdfOutline::getTitle).containsExactly("问题 1");
      assertDestinationAtHeading(pdf, bookmarks(pdf).getFirst(), "问题 1 [综合]");
    }
  }

  @ParameterizedTest
  @MethodSource("noAnswers")
  @DisplayName("没有问答记录时应正常导出且不创建空目录")
  void shouldNotCreateEmptyBookmarks(List<InterviewAnswerEntity> answers) throws IOException {
    InterviewSessionEntity session = session();
    session.setAnswers(answers);

    try (PdfDocument pdf = read(service.exportInterviewReport(session))) {
      assertThat(pdf.getCatalog().getPdfObject().containsKey(PdfName.Outlines)).isFalse();
      assertThat(pdf.getCatalog().getPageMode()).isNotEqualTo(PdfName.UseOutlines);
      assertThat(extractText(pdf)).contains("模拟面试报告", "面试信息");
    }
  }

  static Stream<List<InterviewAnswerEntity>> noAnswers() {
    return Stream.of(null, List.of());
  }

  @Test
  @DisplayName("简历分析导出应保持原有行为，不创建面试问题书签")
  void shouldPreserveResumeAnalysisExport() throws IOException {
    ResumeEntity resume = new ResumeEntity();
    resume.setOriginalFilename("resume.pdf");
    ResumeAnalysisResponse analysis = new ResumeAnalysisResponse(85, null,
        "正常简历摘要。", List.of(), List.of(), null);

    try (PdfDocument pdf = read(service.exportResumeAnalysis(resume, analysis))) {
      assertThat(pdf.getCatalog().getPdfObject().containsKey(PdfName.Outlines)).isFalse();
      assertThat(extractText(pdf)).contains("简历分析报告", "resume.pdf", "正常简历摘要。");
    }
  }

  private InterviewSessionEntity session() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("bookmark-regression");
    session.setTotalQuestions(3);
    session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    return session;
  }

  private InterviewAnswerEntity answer(int index, String question, String userAnswer) {
    InterviewAnswerEntity answer = new InterviewAnswerEntity();
    answer.setQuestionIndex(index);
    answer.setQuestion(question);
    answer.setUserAnswer(userAnswer);
    answer.setScore(85);
    return answer;
  }

  private PdfDocument read(byte[] bytes) throws IOException {
    return new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)));
  }

  private List<PdfOutline> bookmarks(PdfDocument pdf) {
    PdfOutline root = pdf.getOutlines(false);
    assertThat(root).as("PDF 书签目录").isNotNull();
    assertThat(root.getAllChildren()).hasSize(1);
    PdfOutline questions = root.getAllChildren().getFirst();
    assertThat(questions.getTitle()).isEqualTo("问答详情");
    return questions.getAllChildren();
  }

  private PdfArray destination(PdfDocument pdf, PdfOutline outline) {
    assertThat(outline.getDestination()).isNotNull();
    PdfString name = (PdfString) outline.getDestination().getPdfObject();
    PdfArray destination = (PdfArray) pdf.getCatalog().getNameTree(PdfName.Dests).getNames().get(name);
    assertThat(destination).as("书签的命名目标").isNotNull();
    assertThat(destination.getAsName(1)).isEqualTo(PdfName.XYZ);
    return destination;
  }

  private int destinationPage(PdfDocument pdf, PdfOutline outline) {
    return pdf.getPageNumber(destination(pdf, outline).getAsDictionary(0));
  }

  private void assertDestinationAtHeading(PdfDocument pdf, PdfOutline outline, String heading) {
    Map<Integer, Float> headingPositions = new HashMap<>();
    for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
      int pageNumber = page;
      var listener = new IEventListener() {
        @Override
        public void eventOccurred(IEventData data, EventType type) {
          if (type == EventType.RENDER_TEXT) {
            TextRenderInfo text = (TextRenderInfo) data;
            if (heading.equals(text.getText())) {
              headingPositions.put(pageNumber, text.getAscentLine().getStartPoint().get(1));
            }
          }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
          return Set.of(EventType.RENDER_TEXT);
        }
      };
      new PdfCanvasProcessor(listener).processPageContent(pdf.getPage(page));
    }
    assertThat(headingPositions).as("实际绘制的问题标题：%s", heading).hasSize(1);
    PdfArray target = destination(pdf, outline);
    int page = pdf.getPageNumber(target.getAsDictionary(0));
    assertThat(headingPositions).containsKey(page);
    assertThat(target.getAsNumber(3).floatValue())
        .isCloseTo(headingPositions.get(page), within(24f));
  }

  private String extractText(PdfDocument pdf) {
    StringBuilder text = new StringBuilder();
    for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
      text.append(PdfTextExtractor.getTextFromPage(pdf.getPage(page)));
    }
    return text.toString();
  }
}
