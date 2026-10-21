package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 校验 Agent 是否引用了本次真实检索到的 RAG 来源。 */
public final class AgentAnswerGroundingPolicy {

    private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\[R(\\d+)\\]", Pattern.CASE_INSENSITIVE);

    private AgentAnswerGroundingPolicy() {
    }

    public static String buildNotice(String answer, List<AgentSource> sources) {
        Set<String> allowedReferences = sources == null
            ? Set.of()
            : sources.stream()
                .filter(source -> "rag".equalsIgnoreCase(source.kind()))
                .map(AgentSource::reference)
                .filter(reference -> reference != null && !reference.isBlank())
                .map(reference -> reference.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());

        Set<String> citedReferences = new HashSet<>();
        Set<String> invalidReferences = new HashSet<>();
        Matcher matcher = REFERENCE_PATTERN.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            String reference = "R" + matcher.group(1);
            citedReferences.add(reference);
            if (!allowedReferences.contains(reference)) {
                invalidReferences.add(reference);
            }
        }

        if (allowedReferences.isEmpty()) {
            return citedReferences.isEmpty()
                ? ""
                : "\n\n> 引用校验提示：本次没有检索到可用的 RAG 来源，回答中的来源编号不能作为本次知识库依据。";
        }

        if (!invalidReferences.isEmpty()) {
            return "\n\n> 引用校验提示：回答中出现了未检索到的来源编号（"
                + String.join("、", invalidReferences)
                + "）。请以本次参考来源列表中的编号为准。";
        }
        if (citedReferences.isEmpty()) {
            return "\n\n> 引用提示：本次检索到了知识库资料，但上文没有明确标注来源编号；"
                + "涉及知识库事实的结论请结合 R1 等本次参考来源复核。";
        }
        return "";
    }
}
