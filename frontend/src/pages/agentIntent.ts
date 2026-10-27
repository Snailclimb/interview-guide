export type AgentActionIntent =
  | { type: 'schedule'; suggestedTime: string }
  | { type: 'trainingTask' }
  | { type: 'memory' }
  | { type: 'title' }
  | { type: 'reportExport' }
  | { type: 'resumeEdit' };

function pad(value: number) {
  return String(value).padStart(2, '0');
}

export function formatLocalDateTime(value: Date) {
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T${pad(value.getHours())}:${pad(value.getMinutes())}`;
}

function atTime(value: Date, hour: number, minute = 0) {
  const result = new Date(value);
  result.setHours(hour, minute, 0, 0);
  return result;
}

function nextMonday(value: Date) {
  const result = new Date(value);
  const daysUntilMonday = ((8 - result.getDay()) % 7) || 7;
  result.setDate(result.getDate() + daysUntilMonday);
  return result;
}

/** 将用户自然语言里的常见复习时间转换为 datetime-local 可编辑值。 */
export function suggestScheduleTime(message: string, now = new Date()) {
  const normalized = message.trim();
  let date = new Date(now);
  if (/下周/.test(normalized)) {
    date = nextMonday(date);
  } else if (/后天/.test(normalized)) {
    date.setDate(date.getDate() + 2);
  } else if (/明天/.test(normalized)) {
    date.setDate(date.getDate() + 1);
  } else if (/周末/.test(normalized)) {
    const daysUntilSaturday = ((6 - date.getDay()) + 7) % 7 || 7;
    date.setDate(date.getDate() + daysUntilSaturday);
  } else {
    const dateMatch = normalized.match(/(\d{1,2})月(\d{1,2})日/);
    if (dateMatch) {
      const year = date.getFullYear();
      date = new Date(year, Number(dateMatch[1]) - 1, Number(dateMatch[2]));
      if (date.getTime() < now.getTime()) date.setFullYear(year + 1);
    } else {
      date.setDate(date.getDate() + 1);
    }
  }

  const timeMatch = normalized.match(/(?:上午|早上|下午|晚上|今晚|晚间)?\s*(\d{1,2})(?:点|时|:)(\d{1,2})?/);
  if (timeMatch) {
    let hour = Number(timeMatch[1]);
    const minute = Number(timeMatch[2] || 0);
    if (/(下午|晚上|今晚|晚间)/.test(timeMatch[0]) && hour < 12) hour += 12;
    date = atTime(date, Math.min(23, hour), Math.min(59, minute));
  } else if (/今晚|晚上|晚间/.test(normalized)) {
    date = atTime(date, 19);
  } else {
    date = atTime(date, 19);
  }
  return formatLocalDateTime(date);
}

/** 只识别明确的“安排复习”意图，避免普通复盘内容误触发写入流程。 */
export function detectAgentActionIntent(message: string, now = new Date()): AgentActionIntent | null {
  const normalized = message.replace(/\s+/g, ' ').trim();
  if (!normalized || /(不要|不用|无需|取消).{0,6}(安排|预约|日程|导出|下载|保存|创建|修改|重命名|任务|简历|记忆)/.test(normalized)) return null;
  if (/(导出|下载).{0,8}(报告|面试报告|pdf)/i.test(normalized)
    || /(报告|面试报告).{0,8}(导出|下载)/i.test(normalized)) {
    return { type: 'reportExport' };
  }
  if (/(优化|修改|编辑|改写).{0,8}简历/.test(normalized)
    || /简历.{0,8}(优化|修改|编辑|改写)/.test(normalized)) {
    return { type: 'resumeEdit' };
  }
  if (/(保存|记住|记录|加入|写入).{0,10}(长期记忆|记忆|薄弱点|偏好)/.test(normalized)
    || /(长期记忆|记忆).{0,8}(保存|记录|记住)/.test(normalized)) {
    return { type: 'memory' };
  }
  if (/(修改|重命名|改).{0,8}(面试记录|面试名称|标题)/.test(normalized)
    || /给这场面试起个名字/.test(normalized)) {
    return { type: 'title' };
  }
  if (/(创建|新建|加入|拆成|安排).{0,12}(训练任务|练习任务|任务清单|待办)/.test(normalized)
    || /把.{0,12}(作为|列为).{0,8}任务/.test(normalized)) {
    return { type: 'trainingTask' };
  }
  const asksForSchedule = /(安排|预约|加入日程|排个|排一下|提醒我|设置).{0,12}(复习|练习|巩固|日程|时间|准备面试)/.test(normalized)
    || /(复习|练习|巩固|准备面试).{0,12}(下周|明天|后天|周末|今晚|日程|时间)/.test(normalized);
  if (!asksForSchedule) return null;
  return {
    type: 'schedule',
    suggestedTime: suggestScheduleTime(normalized, now),
  };
}
