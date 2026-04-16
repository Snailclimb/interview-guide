const STORAGE_KEY = 'interview.preferences.v1';

interface InterviewPreferences {
  defaultLlmProvider: string;
}

const DEFAULT_INTERVIEW_PREFERENCES: InterviewPreferences = {
  defaultLlmProvider: 'dashscope',
};

function normalizeProvider(value: unknown): string {
  if (typeof value !== 'string') {
    return DEFAULT_INTERVIEW_PREFERENCES.defaultLlmProvider;
  }
  const trimmed = value.trim();
  return trimmed || DEFAULT_INTERVIEW_PREFERENCES.defaultLlmProvider;
}

export function loadInterviewPreferences(): InterviewPreferences {
  if (typeof window === 'undefined') {
    return DEFAULT_INTERVIEW_PREFERENCES;
  }
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return DEFAULT_INTERVIEW_PREFERENCES;
  }
  try {
    const parsed = JSON.parse(raw) as Partial<InterviewPreferences> & Record<string, unknown>;
    // 兼容旧结构，只读取 provider。
    return {
      defaultLlmProvider: normalizeProvider(parsed.defaultLlmProvider),
    };
  } catch {
    return DEFAULT_INTERVIEW_PREFERENCES;
  }
}

export function saveInterviewPreferences(preferences: InterviewPreferences) {
  if (typeof window === 'undefined') {
    return;
  }
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences));
}
