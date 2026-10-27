export type AgentActionIntent = {
  type: 'schedule';
  suggestedTime: string;
};

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
  if (!normalized || /(不要|不用|无需|取消).{0,4}(安排|预约|日程)/.test(normalized)) return null;
  const asksForSchedule = /(安排|预约|加入日程|排个|排一下|提醒我|设置).{0,12}(复习|练习|巩固|日程|时间|准备面试)/.test(normalized)
    || /(复习|练习|巩固|准备面试).{0,12}(下周|明天|后天|周末|今晚|日程|时间)/.test(normalized);
  if (!asksForSchedule) return null;
  return {
    type: 'schedule',
    suggestedTime: suggestScheduleTime(normalized, now),
  };
}
