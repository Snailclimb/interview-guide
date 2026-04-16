import { request } from './request';

// ========== 类型定义 ==========

export interface CreateSessionRequest {
  roleType?: string;
  skillId: string;
  difficulty?: string;
  customJdText?: string;
  resumeId?: number;
  introEnabled?: boolean;
  techEnabled?: boolean;
  projectEnabled?: boolean;
  hrEnabled?: boolean;
  plannedDuration?: number;
  llmProvider?: string;
}

export interface SessionResponse {
  sessionId: number;
  roleType: string;
  currentPhase: string;
  status: string;
  startTime: string;
  plannedDuration: number;
  webSocketUrl: string;
}

export interface InterviewMessage {
  id: number;
  sessionId: number;
  messageType: string;
  phase: string;
  userRecognizedText: string;
  aiGeneratedText: string;
  timestamp: string;
  sequenceNum: number;
}

export interface VoiceAnswerDetail {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
  referenceAnswer?: string | null;
  keyPoints?: string[] | null;
}

export interface VoiceEvaluationDetail {
  sessionId: number;
  totalQuestions: number;
  overallScore: number;
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  answers: VoiceAnswerDetail[];
}

/**
 * Evaluation status response from GET/POST evaluation endpoints
 */
export interface EvaluationStatusResponse {
  evaluateStatus: string | null;  // PENDING | PROCESSING | COMPLETED | FAILED
  evaluateError?: string | null;
  evaluation?: VoiceEvaluationDetail | null;
}

/**
 * Session metadata for history list
 */
export interface SessionMeta {
  sessionId: number;
  roleType: string;
  status: string;
  currentPhase: string;
  createdAt: string;
  updatedAt: string;
  actualDuration?: number;
  messageCount: number;
  evaluateStatus?: string;
  evaluateError?: string;
}

// WebSocket 消息类型
export interface WebSocketAudioMessage {
  type: 'audio';
  data: string; // Base64 编码的音频
  timestamp?: number;
}

export interface WebSocketSubtitleMessage {
  type: 'subtitle';
  text: string;
  isFinal: boolean;
}

export interface WebSocketAudioResponseMessage {
  type: 'audio';
  data: string; // Base64 编码的音频
  text: string;
}

export interface WebSocketTextMessage {
  type: 'text';
  content: string;
}

export interface WebSocketAudioChunkMessage {
  type: 'audio_chunk';
  data: string; // Base64 WAV
  index: number;
  isLast: boolean;
}

export type WebSocketMessage =
  | WebSocketAudioMessage
  | WebSocketSubtitleMessage
  | WebSocketAudioResponseMessage
  | WebSocketTextMessage
  | WebSocketAudioChunkMessage;

// WebSocket 事件处理器
export interface WebSocketEventHandlers {
  onMessage?: (message: WebSocketMessage) => void;
  onSubtitle?: (text: string, isFinal: boolean) => void;
  onAudioResponse?: (audioData: string, text: string) => void;
  onAudioChunk?: (data: string, index: number, isLast: boolean) => void;
  onOpen?: () => void;
  onClose?: (event: CloseEvent) => void;
  onError?: (error: Event) => void;
}

// ========== API 函数 ==========

export const voiceInterviewApi = {
  /**
   * 创建新的语音面试会话
   */
  async createSession(data: CreateSessionRequest): Promise<SessionResponse> {
    return request.post<SessionResponse>('/api/voice-interview/sessions', data);
  },

  /**
   * 获取会话详情
   */
  async getSession(sessionId: number): Promise<SessionResponse> {
    return request.get<SessionResponse>(`/api/voice-interview/sessions/${sessionId}`);
  },

  /**
   * 结束会话
   */
  async endSession(sessionId: number): Promise<void> {
    return request.post<void>(`/api/voice-interview/sessions/${sessionId}/end`);
  },

  /**
   * 获取会话消息列表
   */
  async getMessages(sessionId: number): Promise<InterviewMessage[]> {
    return request.get<InterviewMessage[]>(
      `/api/voice-interview/sessions/${sessionId}/messages`
    );
  },

  /**
   * 获取面试评估状态和结果（轮询）
   */
  async getEvaluation(sessionId: number): Promise<EvaluationStatusResponse> {
    return request.get<EvaluationStatusResponse>(
      `/api/voice-interview/sessions/${sessionId}/evaluation`
    );
  },

  /**
   * 触发异步评估生成
   */
  async generateEvaluation(sessionId: number): Promise<EvaluationStatusResponse> {
    return request.post<EvaluationStatusResponse>(
      `/api/voice-interview/sessions/${sessionId}/evaluation`
    );
  },

  /**
   * Pause interview session
   */
  async pauseSession(sessionId: number, reason: string = 'user_initiated'): Promise<void> {
    return request.put(
      `/api/voice-interview/sessions/${sessionId}/pause`,
      { reason }
    );
  },

  /**
   * Resume interview session
   */
  async resumeSession(sessionId: number): Promise<SessionResponse> {
    return request.put<SessionResponse>(
      `/api/voice-interview/sessions/${sessionId}/resume`
    );
  },

  /**
   * Get all sessions
   */
  async getAllSessions(userId?: string, status?: string): Promise<SessionMeta[]> {
    const params = new URLSearchParams();
    if (userId) params.append('userId', userId);
    if (status) params.append('status', status);

    return request.get<SessionMeta[]>(
      `/api/voice-interview/sessions?${params.toString()}`
    );
  },

  /**
   * 删除语音面试会话
   */
  async deleteSession(sessionId: number): Promise<void> {
    return request.delete(`/api/voice-interview/sessions/${sessionId}`);
  },

};

// ========== WebSocket 连接管理类 ==========

export class VoiceInterviewWebSocket {
  private ws: WebSocket | null = null;
  private url: string;
  private handlers: WebSocketEventHandlers;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 3;
  private reconnectDelay = 2000;

  constructor(_sessionId: number, url: string, handlers: WebSocketEventHandlers) {
    this.url = url;
    this.handlers = handlers;
  }

  /**
   * 建立 WebSocket 连接
   */
  connect(): void {
    try {
      this.ws = new WebSocket(this.url);

      this.ws.onopen = () => {
        this.reconnectAttempts = 0;
        this.handlers.onOpen?.();
      };

      this.ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data) as WebSocketMessage;

          // 调用通用消息处理器
          this.handlers.onMessage?.(message);

          // 根据消息类型调用特定处理器
          switch (message.type) {
            case 'subtitle':
              this.handlers.onSubtitle?.(
                message.text,
                (message as WebSocketSubtitleMessage).isFinal
              );
              break;
            case 'audio':
              // 检查是否是 AI 响应（包含 text 字段）
              if ('text' in message) {
                const audioMsg = message as WebSocketAudioResponseMessage;
                this.handlers.onAudioResponse?.(audioMsg.data, audioMsg.text);
              }
              break;
            case 'audio_chunk':
              if ('index' in message) {
                const chunkMsg = message as WebSocketAudioChunkMessage;
                this.handlers.onAudioChunk?.(chunkMsg.data, chunkMsg.index, chunkMsg.isLast);
              }
              break;
            case 'text':
              // Text-only message (when TTS fails)
              if ('content' in message) {
                this.handlers.onAudioResponse?.('', (message as any).content);
              }
              break;
          }
        } catch (error) {
          console.error('Error parsing WebSocket message:', error);
        }
      };

      this.ws.onclose = (event) => {
        this.handlers.onClose?.(event);

        if (!event.wasClean && this.reconnectAttempts < this.maxReconnectAttempts) {
          this.reconnectAttempts++;
          setTimeout(() => this.connect(), this.reconnectDelay);
        }
      };

      this.ws.onerror = (error) => {
        this.handlers.onError?.(error);
      };
    } catch (error) {
      console.error('Error creating WebSocket connection:', error);
      this.handlers.onError?.(error as Event);
    }
  }

  /**
   * 发送音频数据
   */
  sendAudio(audioData: string): boolean {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      const message: WebSocketAudioMessage = {
        type: 'audio',
        data: audioData,
        timestamp: Date.now(),
      };
      this.ws.send(JSON.stringify(message));
      return true;
    }
    console.warn('WebSocket is not connected');
    return false;
  }

  /**
   * 发送控制消息
   */
  sendControl(action: string, data?: Record<string, unknown>): boolean {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      const message = {
        type: 'control',
        action,
        data,
        timestamp: Date.now(),
      };
      this.ws.send(JSON.stringify(message));
      return true;
    }
    console.warn('WebSocket is not connected');
    return false;
  }

  /**
   * 关闭连接
   */
  disconnect(): void {
    if (this.ws) {
      // 不重连
      this.reconnectAttempts = this.maxReconnectAttempts;
      this.ws.close(1000, 'User disconnected');
      this.ws = null;
    }
  }

  /**
   * 获取连接状态
   */
  getReadyState(): number {
    return this.ws?.readyState ?? WebSocket.CLOSED;
  }

  /**
   * 是否已连接
   */
  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }
}

// ========== 便捷函数 ==========

/**
 * 创建并连接 WebSocket
 */
export function connectWebSocket(
  sessionId: number,
  webSocketUrl: string,
  handlers: WebSocketEventHandlers
): VoiceInterviewWebSocket {
  const ws = new VoiceInterviewWebSocket(sessionId, webSocketUrl, handlers);
  ws.connect();
  return ws;
}

export default voiceInterviewApi;
