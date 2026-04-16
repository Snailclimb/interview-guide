import { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import InterviewMessageBubble from './InterviewMessageBubble';

interface Message {
  role: 'user' | 'ai';
  text: string;
  id: string;
}

interface RealtimeSubtitleProps {
  messages: Message[];
  userText: string;
  aiText: string;
  isAiSpeaking: boolean;
}

export default function RealtimeSubtitle({
  messages,
  userText,
  aiText,
  isAiSpeaking
}: RealtimeSubtitleProps) {
  const [displayedAiText, setDisplayedAiText] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const activeAiText = displayedAiText || aiText;
  const latestAiMessage = [...messages].reverse().find(msg => msg.role === 'ai');
  const shouldShowActiveAi =
    isAiSpeaking &&
    !!activeAiText &&
    latestAiMessage?.text.trim() !== activeAiText.trim();

  // Typewriter effect for current AI text
  useEffect(() => {
    if (aiText && isAiSpeaking) {
      setIsTyping(true);
      setDisplayedAiText('');

      let index = 0;
      const speed = 25; // ms per character

      const typeWriter = () => {
        if (index < aiText.length) {
          setDisplayedAiText(aiText.substring(0, index + 1));
          index++;
          setTimeout(typeWriter, speed);
        } else {
          setIsTyping(false);
        }
      };

      typeWriter();
    } else if (!aiText) {
      setDisplayedAiText('');
      setIsTyping(false);
    }
  }, [aiText, isAiSpeaking]);

  // Auto-scroll to bottom on new messages or text updates
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, userText, displayedAiText]);

  return (
    <div className="flex flex-col h-full bg-white dark:bg-slate-800 overflow-hidden">
      {/* Header */}
      <div className="px-5 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between bg-slate-50 dark:bg-slate-800/80">
        <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-200 tracking-wide">对话实录</h4>
        <div className="flex items-center gap-3">
          {isAiSpeaking && (
            <div className="flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 bg-primary-500 rounded-full animate-pulse" />
              <span className="text-[10px] uppercase tracking-wider text-primary-400 font-semibold">AI 说</span>
            </div>
          )}
          {userText && (
            <div className="flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse" />
              <span className="text-[10px] uppercase tracking-wider text-green-400 font-semibold">你在说</span>
            </div>
          )}
        </div>
      </div>

      {/* Chat History */}
      <div
        ref={scrollRef}
        className="flex-1 min-h-0 overflow-y-auto p-5 space-y-5 scroll-smooth"
      >
        <AnimatePresence initial={false}>
          {/* History Messages */}
          {messages.map((msg) => (
            <div key={msg.id}>
              <InterviewMessageBubble
                role={msg.role === 'user' ? 'user' : 'interviewer'}
                text={msg.text}
              />
            </div>
          ))}

          {/* Current AI Response (Active) */}
          {shouldShowActiveAi && (
            <InterviewMessageBubble
              role="interviewer"
              text={activeAiText}
              highlight
              suffix={isTyping ? (
                <motion.span
                  className="inline-block w-1 h-3.5 bg-primary-500 ml-1.5 translate-y-0.5 rounded-full"
                  animate={{ opacity: [1, 0, 1] }}
                  transition={{ duration: 0.8, repeat: Infinity }}
                />
              ) : null}
            />
          )}

          {/* Current User Input (Real-time) */}
          {userText && (
            <InterviewMessageBubble
              role="user"
              text={userText}
              highlight
              italic
              suffix={<span className="ml-1 animate-pulse">...</span>}
            />
          )}

          {/* Empty State */}
          {messages.length === 0 && !userText && !aiText && (
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              className="h-full flex flex-col items-center justify-center text-slate-500 dark:text-slate-400 py-12"
            >
              <div className="w-12 h-12 rounded-full bg-slate-100 dark:bg-slate-700 flex items-center justify-center mb-4 border border-slate-200 dark:border-slate-600">
                <svg className="w-5 h-5 text-slate-400 dark:text-slate-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                </svg>
              </div>
              <p className="text-sm">面试即将开始，请准备</p>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}
