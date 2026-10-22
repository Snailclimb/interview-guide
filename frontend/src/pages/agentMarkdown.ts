const INLINE_HEADING_TITLES = [
  '本场面试复盘结论',
  '本场面试考察重点',
  '目前无法确认的关键问题',
  '表现概览',
  '关键优势',
  '最重要的改进点',
  '下一步练习',
  '薄弱点判断',
  '答题要点',
  '自测标准',
  '短期目标',
  '分步任务',
  '完成标准',
  '复盘周期',
  '行动建议',
  '改进建议',
  '训练计划',
];

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const inlineHeadingPattern = new RegExp(
  `^(#{1,6}\\s+(?:${INLINE_HEADING_TITLES.map(escapeRegExp).join('|')}))[ \\t]+(?=\\S)`,
  'gm',
);

/**
 * 修复模型流式输出偶尔把标题、列表项和正文压缩到同一行的问题。
 * 代码围栏内部保持原样，避免修改示例代码或代码里的 Markdown。
 */
export function normalizeAgentMarkdown(content: string) {
  return content
    .replace(/\r\n?/g, '\n')
    .split(/(```[\s\S]*?```|~~~[\s\S]*?~~~)/g)
    .map((part, index) => {
      if (index % 2 === 1) return part;

      return part
        // 已知的中文标题：把“标题 正文”拆成独立标题和正文。
        .replace(inlineHeadingPattern, '$1\n\n')
        // 未预置的二级及以上标题：先确保标题不会和前一段正文粘在一起。
        .replace(/([^#\n])[ \t]*(#{1,6}\s+(?=\S))/g, '$1\n\n$2')
        // “- **优势**：...” 统一拆成标题项和子项，避免整段挤在一行。
        .replace(/^(\s*(?:[-*+]|\d+\.)\s+\*\*[^*\n]+\*\*)\s*[-：:]\s*/gm, '$1\n   - ')
        // 模型有时连续输出多个列表项，但没有换行。
        .replace(/[ \t]+(?=(?:[-*+] )(?:(?:\*\*)|(?:`)|[A-Za-z\u4e00-\u9fff]))/g, '\n')
        .replace(/[ \t]+(?=\d{1,2}\. (?:(?:\*\*)|(?:`)|[A-Za-z\u4e00-\u9fff]))/g, '\n');
    })
    .join('');
}
