package interview.guide.infrastructure.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("面试 PDF 问题排序")
class PdfInterviewQuestionOrderTest {

  private static final Pattern HEADING = Pattern.compile("问题\\s+(\\d+)\\s+\\[[^\\]]*\\]");
  private final PdfExportService service = new PdfExportService(JsonMapper.builder().build());

  @ParameterizedTest
  @MethodSource("questionOrders")
  @DisplayName("导出应按数值题号升序排列，保留两位数和非连续题号")
  void shouldSortQuestionNumbers(int[] input, List<Integer> expected) throws IOException {
    InterviewSessionEntity session = session();
    session.setAnswers(IntStream.of(input)
        .mapToObj(number -> answer(number, "question-" + number, "answer-" + number,
            60 + number, "feedback-" + number, "reference-" + number))
        .toList());

    String report = text(service.exportInterviewReport(session));

    assertThat(numbers(report)).containsExactlyElementsOf(expected);
  }

  static Stream<Arguments> questionOrders() {
    return Stream.of(
        Arguments.of(new int[] {9, 10, 11, 12, 6, 7, 8, 1, 2, 3, 4, 5},
            List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)),
        Arguments.of(new int[] {4, 3, 2, 1}, List.of(1, 2, 3, 4)),
        Arguments.of(new int[] {12, 3, 6, 1}, List.of(1, 3, 6, 12)),
        Arguments.of(new int[] {1, 3, 6, 12}, List.of(1, 3, 6, 12)),
        Arguments.of(new int[] {3}, List.of(3)));
  }

  @Test
  @DisplayName("排序应移动整条问答，保留该题的回答、评分、反馈和参考答案")
  void shouldKeepAnswerDetailsWithTheirQuestion() throws IOException {
    InterviewSessionEntity session = session();
    session.setAnswers(List.of(
        answer(3, "third-question", "third-answer", 73, "third-feedback", "third-reference"),
        answer(1, "first-question", "first-answer", 91, "first-feedback", "first-reference"),
        answer(2, "second-question", "second-answer", 52, "second-feedback", "second-reference")));

    String report = text(service.exportInterviewReport(session));

    assertThat(numbers(report)).containsExactly(1, 2, 3);
    List<String> blocks = blocks(report);
    assertThat(blocks).hasSize(3);
    assertThat(blocks.get(0)).contains("Q: first-question", "A: first-answer",
        "得分: 91/100", "评价: first-feedback", "参考答案: first-reference");
    assertThat(blocks.get(1)).contains("Q: second-question", "A: second-answer",
        "得分: 52/100", "评价: second-feedback", "参考答案: second-reference");
    assertThat(blocks.get(2)).contains("Q: third-question", "A: third-answer",
        "得分: 73/100", "评价: third-feedback", "参考答案: third-reference");
  }

  @Test
  @DisplayName("导出排序不能修改或替换会话的原始答案列表")
  void shouldNotMutateSessionAnswers() throws IOException {
    InterviewAnswerEntity third = answer(3, "third", "third-answer", 83, null, null);
    InterviewAnswerEntity first = answer(1, "first", "first-answer", 81, null, null);
    InterviewAnswerEntity second = answer(2, "second", "second-answer", 82, null, null);
    List<InterviewAnswerEntity> original = new ArrayList<>(List.of(third, first, second));
    InterviewSessionEntity session = session();
    session.setAnswers(original);

    String report = text(service.exportInterviewReport(session));

    assertThat(numbers(report)).containsExactly(1, 2, 3);
    assertThat(session.getAnswers()).isSameAs(original);
    assertThat(original).containsExactly(third, first, second);
  }

  @ParameterizedTest
  @MethodSource("noAnswers")
  @DisplayName("没有答案记录时应继续正常导出")
  void shouldHandleNoAnswers(List<InterviewAnswerEntity> answers) throws IOException {
    InterviewSessionEntity session = session();
    session.setAnswers(answers);

    String report = text(service.exportInterviewReport(session));

    assertThat(report).contains("模拟面试报告", "面试信息");
    assertThat(numbers(report)).isEmpty();
  }

  static Stream<List<InterviewAnswerEntity>> noAnswers() {
    return Stream.of(null, List.of());
  }

  @Test
  @DisplayName("简历分析导出应保持原有摘要和评分")
  void shouldPreserveResumeAnalysis() throws IOException {
    ResumeEntity resume = new ResumeEntity();
    resume.setOriginalFilename("resume.pdf");
    ResumeAnalysisResponse analysis = new ResumeAnalysisResponse(85, null,
        "普通简历摘要", List.of(), List.of(), null);

    String report = text(service.exportResumeAnalysis(resume, analysis));

    assertThat(report).contains("简历分析报告", "resume.pdf", "85 / 100", "普通简历摘要");
  }

  private InterviewSessionEntity session() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("question-order-regression");
    session.setTotalQuestions(12);
    session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    return session;
  }

  private InterviewAnswerEntity answer(int number, String question, String response, int score,
      String feedback, String reference) {
    InterviewAnswerEntity answer = new InterviewAnswerEntity();
    answer.setQuestionIndex(number - 1);
    answer.setQuestion(question);
    answer.setUserAnswer(response);
    answer.setScore(score);
    answer.setFeedback(feedback);
    answer.setReferenceAnswer(reference);
    return answer;
  }

  private String text(byte[] bytes) throws IOException {
    try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
      StringBuilder text = new StringBuilder();
      for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
        text.append(PdfTextExtractor.getTextFromPage(pdf.getPage(page))).append('\n');
      }
      return text.toString();
    }
  }

  private List<Integer> numbers(String report) {
    var matcher = HEADING.matcher(report);
    List<Integer> numbers = new ArrayList<>();
    while (matcher.find()) {
      numbers.add(Integer.parseInt(matcher.group(1)));
    }
    return numbers;
  }

  private List<String> blocks(String report) {
    var matcher = HEADING.matcher(report);
    List<Integer> starts = new ArrayList<>();
    while (matcher.find()) {
      starts.add(matcher.start());
    }
    List<String> blocks = new ArrayList<>();
    for (int i = 0; i < starts.size(); i++) {
      int end = i + 1 < starts.size() ? starts.get(i + 1) : report.length();
      blocks.add(report.substring(starts.get(i), end));
    }
    return blocks;
  }
}
