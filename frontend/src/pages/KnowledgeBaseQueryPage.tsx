import {useEffect, useState, useRef} from 'react';
import {motion, AnimatePresence} from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {knowledgeBaseApi, type KnowledgeBaseItem} from '../api/knowledgebase';
import {formatDateOnly} from '../utils/date';
import ConfirmDialog from '../components/ConfirmDialog';

interface KnowledgeBaseQueryPageProps {
  onBack: () => void;
  onUpload: () => void;
}

interface Message {
  type: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

export default function KnowledgeBaseQueryPage({ onBack, onUpload }: KnowledgeBaseQueryPageProps) {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [selectedKbIds, setSelectedKbIds] = useState<Set<number>>(new Set());
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingList, setLoadingList] = useState(true);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ id: number; name: string } | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    loadKnowledgeBases();
  }, []);

  // 自动滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const loadKnowledgeBases = async () => {
    setLoadingList(true);
    try {
      const list = await knowledgeBaseApi.getAllKnowledgeBases();
      setKnowledgeBases(list);
    } catch (err) {
      console.error('加载知识库列表失败', err);
    } finally {
      setLoadingList(false);
    }
  };

  const handleToggleKb = (kbId: number) => {
    setSelectedKbIds(prev => {
      const newSet = new Set(prev);
      if (newSet.has(kbId)) {
        newSet.delete(kbId);
      } else {
        newSet.add(kbId);
      }
      if (newSet.size !== prev.size) {
        setMessages([]); // 切换知识库时清空消息
      }
      return newSet;
    });
  };

  const handleSubmitQuestion = async () => {
    if (!question.trim() || selectedKbIds.size === 0 || loading) return;

    const userMessage: Message = {
      type: 'user',
      content: question.trim(),
      timestamp: new Date(),
    };
    setMessages(prev => [...prev, userMessage]);
    setQuestion('');
    setLoading(true);

    // 创建一个临时的助手消息用于流式更新
    const assistantMessage: Message = {
      type: 'assistant',
      content: '',
      timestamp: new Date(),
    };
    setMessages(prev => [...prev, assistantMessage]);

    let fullContent = '';

    try {
      await knowledgeBaseApi.queryKnowledgeBaseStream(
        {
          knowledgeBaseIds: Array.from(selectedKbIds),
          question: userMessage.content,
        },
        // onMessage: 收到流式数据块
        (chunk: string) => {
          fullContent += chunk;
          setMessages(prev => {
            const newMessages = [...prev];
            newMessages[newMessages.length - 1] = {
              ...assistantMessage,
              content: fullContent,
            };
            return newMessages;
          });
        },
        // onComplete: 流式传输完成
        () => {
          // 处理完成后，规范化内容的换行
          setMessages(prev => {
            const newMessages = [...prev];
            const lastMessage = newMessages[newMessages.length - 1];
            
            // 激进的换行处理：在中文句号、问号、感叹号后强制换行
            let normalizedContent = lastMessage.content
              // 在句号后（如果后面不是换行）添加两个换行符
              .replace(/([。！？])(?!\n)/g, '$1\n\n')
              // 在英文句号后（如果后面是空格或数字）添加两个换行符
              .replace(/(\.)(\s+)(?=[0-9A-Z一二三四五])/g, '$1\n\n')
              // 数字序号前确保有空行
              .replace(/([^-\n])(\s*)([0-9]+[\.、])/g, '$1\n\n$3')
              // 确保列表项（- 开头）前有空行
              .replace(/([^\n])\n(-\s)/g, '$1\n\n$2')
              // 确保 Markdown 标题前有空行
              .replace(/([^\n])\n(#{1,6}\s)/g, '$1\n\n$2')
              // 冒号后如果换行，确保是两个换行
              .replace(/([：:])\n(?!\n)/g, '$1\n\n')
              // 清理多余的连续空行（超过2个）
              .replace(/\n{3,}/g, '\n\n');
            
            newMessages[newMessages.length - 1] = {
              ...lastMessage,
              content: normalizedContent,
            };
            return newMessages;
          });
          setLoading(false);
        },
        // onError: 错误处理
        (error: Error) => {
          console.error('流式查询失败:', error);
          setMessages(prev => {
            const newMessages = [...prev];
            newMessages[newMessages.length - 1] = {
              ...assistantMessage,
              content: fullContent || error.message || '回答失败，请重试',
            };
            return newMessages;
          });
          setLoading(false);
        }
      );
    } catch (err) {
      console.error('发起流式查询失败:', err);
      setMessages(prev => {
        const newMessages = [...prev];
        newMessages[newMessages.length - 1] = {
          ...assistantMessage,
          content: err instanceof Error ? err.message : '回答失败，请重试',
        };
        return newMessages;
      });
      setLoading(false);
    }
  };

  const handleDeleteClick = (id: number, name: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteConfirm({ id, name });
  };

  const handleDeleteConfirm = async () => {
    if (!deleteConfirm) return;

    const { id } = deleteConfirm;
    setDeletingId(id);
    try {
      await knowledgeBaseApi.deleteKnowledgeBase(id);
      await loadKnowledgeBases();
      if (selectedKbIds.has(id)) {
        setSelectedKbIds(prev => {
          const newSet = new Set(prev);
          newSet.delete(id);
          return newSet;
        });
        setMessages([]);
      }
      setDeleteConfirm(null);
    } catch (err) {
      alert(err instanceof Error ? err.message : '删除失败，请稍后重试');
    } finally {
      setDeletingId(null);
    }
  };

  const formatFileSize = (bytes: number): string => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  return (
    <div className="max-w-7xl mx-auto pt-8 pb-10">
      {/* 头部 */}
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold text-slate-900 mb-2">知识库问答</h1>
          <p className="text-slate-500">选择知识库，向 AI 提问</p>
        </div>
        <div className="flex gap-3">
          <motion.button
            onClick={onUpload}
            className="px-5 py-2.5 border border-slate-200 rounded-xl text-slate-600 font-medium hover:bg-slate-50 transition-all"
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
          >
            上传知识库
          </motion.button>
          <motion.button
            onClick={onBack}
            className="px-5 py-2.5 border border-slate-200 rounded-xl text-slate-600 font-medium hover:bg-slate-50 transition-all"
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
          >
            返回
          </motion.button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* 左侧：知识库列表 */}
        <div className="lg:col-span-1">
          <div className="bg-white rounded-2xl p-6 shadow-sm">
            <h2 className="text-lg font-semibold text-slate-800 mb-4">知识库列表</h2>
            
            {loadingList ? (
              <div className="text-center py-8">
                <motion.div
                  className="w-8 h-8 border-2 border-primary-500 border-t-transparent rounded-full mx-auto"
                  animate={{ rotate: 360 }}
                  transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                />
              </div>
            ) : knowledgeBases.length === 0 ? (
              <div className="text-center py-8 text-slate-500">
                <p className="mb-4">暂无知识库</p>
                <button
                  onClick={onUpload}
                  className="text-primary-500 hover:text-primary-600 font-medium"
                >
                  立即上传
                </button>
              </div>
            ) : (
              <div className="space-y-2">
                {knowledgeBases.map((kb) => (
                  <motion.div
                    key={kb.id}
                    onClick={() => handleToggleKb(kb.id)}
                    className={`p-4 rounded-xl cursor-pointer transition-all ${
                      selectedKbIds.has(kb.id)
                        ? 'bg-primary-50 border-2 border-primary-500'
                        : 'bg-slate-50 hover:bg-slate-100 border-2 border-transparent'
                    }`}
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <input
                            type="checkbox"
                            checked={selectedKbIds.has(kb.id)}
                            onChange={() => handleToggleKb(kb.id)}
                            onClick={(e) => e.stopPropagation()}
                            className="w-4 h-4 text-primary-500 rounded focus:ring-primary-500"
                          />
                          <h3 className="font-medium text-slate-800 truncate">{kb.name}</h3>
                        </div>
                        <div className="flex items-center gap-3 text-xs text-slate-500 mt-1">
                          <span>{formatFileSize(kb.fileSize)}</span>
                          <span>•</span>
                          <span>{kb.questionCount} 次提问</span>
                        </div>
                        <p className="text-xs text-slate-400 mt-1">
                          {formatDateOnly(kb.uploadedAt)}
                        </p>
                      </div>
                      <button
                        onClick={(e) => handleDeleteClick(kb.id, kb.name, e)}
                        disabled={deletingId === kb.id}
                        className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        title="删除知识库"
                      >
                        {deletingId === kb.id ? (
                          <motion.div
                            className="w-4 h-4 border-2 border-red-500 border-t-transparent rounded-full"
                            animate={{ rotate: 360 }}
                            transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                          />
                        ) : (
                          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none">
                            <path d="M3 6H5H21M8 6V4C8 3.46957 8.21071 2.96086 8.58579 2.58579C8.96086 2.21071 9.46957 2 10 2H14C14.5304 2 15.0391 2.21071 15.4142 2.58579C15.7893 2.96086 16 3.46957 16 4V6M19 6V20C19 20.5304 18.7893 21.0391 18.4142 21.4142C18.0391 21.7893 17.5304 22 17 22H7C6.46957 22 5.96086 21.7893 5.58579 21.4142C5.21071 21.0391 5 20.5304 5 20V6H19Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                            <path d="M10 11V17M14 11V17" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                          </svg>
                        )}
                      </button>
                    </div>
                  </motion.div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* 右侧：问答区域 */}
        <div className="lg:col-span-2">
          <div className="bg-white rounded-2xl shadow-sm flex flex-col h-[calc(100vh-12rem)]">
            {selectedKbIds.size > 0 ? (
              <>
                {/* 知识库信息 */}
                <div className="p-6 border-b border-slate-200">
                  <h2 className="text-xl font-semibold text-slate-800">
                    {selectedKbIds.size === 1 
                      ? knowledgeBases.find(kb => kb.id === Array.from(selectedKbIds)[0])?.name || '知识库'
                      : `已选择 ${selectedKbIds.size} 个知识库`}
                  </h2>
                  <p className="text-sm text-slate-500 mt-1">
                    {selectedKbIds.size === 1 
                      ? (() => {
                          const kb = knowledgeBases.find(kb => kb.id === Array.from(selectedKbIds)[0]);
                          return kb ? `${formatFileSize(kb.fileSize)} • ${kb.questionCount} 次提问` : '';
                        })()
                      : '将综合多个知识库的内容回答您的问题'}
                  </p>
                </div>

                {/* 消息列表 */}
                <div className="flex-1 overflow-y-auto p-6 space-y-4">
                  {messages.length === 0 ? (
                    <div className="text-center py-12 text-slate-400">
                      <svg className="w-16 h-16 mx-auto mb-4 opacity-50" viewBox="0 0 24 24" fill="none">
                        <path d="M21 15C21 15.5304 20.7893 16.0391 20.4142 16.4142C20.0391 16.7893 19.5304 17 19 17H7L3 21V5C3 4.46957 3.21071 3.96086 3.58579 3.58579C3.96086 3.21071 4.46957 3 5 3H19C19.5304 3 20.0391 3.21071 20.4142 3.58579C20.7893 3.96086 21 4.46957 21 5V15Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                      </svg>
                      <p>开始提问吧！</p>
                    </div>
                  ) : (
                    <AnimatePresence>
                      {messages.map((msg, index) => (
                        <motion.div
                          key={index}
                          initial={{ opacity: 0, y: 10 }}
                          animate={{ opacity: 1, y: 0 }}
                          className={`flex ${msg.type === 'user' ? 'justify-end' : 'justify-start'}`}
                        >
                          <div
                            className={`max-w-[80%] rounded-2xl p-4 ${
                              msg.type === 'user'
                                ? 'bg-primary-500 text-white'
                                : 'bg-slate-100 text-slate-800'
                            }`}
                          >
                            {msg.type === 'user' ? (
                              <p className="whitespace-pre-wrap">{msg.content}</p>
                            ) : (
                              <div className="markdown-content text-slate-800">
                                <ReactMarkdown
                                  remarkPlugins={[remarkGfm]}
                                  components={{
                                    // 段落 - 添加更多间距
                                    p: ({children}) => <p className="mb-4 leading-relaxed last:mb-0">{children}</p>,
                                    // 标题
                                    h1: ({children}) => <h1 className="text-xl font-bold mb-3 mt-4">{children}</h1>,
                                    h2: ({children}) => <h2 className="text-lg font-bold mb-2 mt-3">{children}</h2>,
                                    h3: ({children}) => <h3 className="text-base font-bold mb-2 mt-3">{children}</h3>,
                                    // 加粗和斜体
                                    strong: ({children}) => <strong className="font-bold text-slate-900">{children}</strong>,
                                    em: ({children}) => <em className="italic">{children}</em>,
                                    // 列表 - 增加间距
                                    ul: ({children}) => <ul className="list-disc list-outside ml-5 mb-4 space-y-2">{children}</ul>,
                                    ol: ({children}) => <ol className="list-decimal list-outside ml-5 mb-4 space-y-2">{children}</ol>,
                                    li: ({children}) => <li className="leading-relaxed pl-1">{children}</li>,
                                    // 代码 - 优化样式
                                    code: ({inline, children}) => 
                                      inline ? (
                                        <code className="bg-slate-200 text-slate-800 px-2 py-1 rounded text-sm font-mono whitespace-nowrap">{children}</code>
                                      ) : (
                                        <code className="block bg-slate-200 text-slate-800 p-3 rounded-lg overflow-x-auto my-3 font-mono text-sm leading-relaxed">{children}</code>
                                      ),
                                    pre: ({children}) => <pre className="my-2">{children}</pre>,
                                    // 引用
                                    blockquote: ({children}) => (
                                      <blockquote className="border-l-4 border-slate-300 pl-4 italic my-3 text-slate-600">
                                        {children}
                                      </blockquote>
                                    ),
                                    // 链接
                                    a: ({href, children}) => (
                                      <a href={href} className="text-primary-500 hover:text-primary-600 underline" target="_blank" rel="noopener noreferrer">
                                        {children}
                                      </a>
                                    ),
                                    // 水平线
                                    hr: () => <hr className="my-4 border-slate-300" />,
                                    // 表格
                                    table: ({children}) => (
                                      <div className="overflow-x-auto my-3">
                                        <table className="min-w-full border-collapse border border-slate-300">
                                          {children}
                                        </table>
                                      </div>
                                    ),
                                    thead: ({children}) => <thead className="bg-slate-100">{children}</thead>,
                                    tbody: ({children}) => <tbody>{children}</tbody>,
                                    tr: ({children}) => <tr className="border-b border-slate-300">{children}</tr>,
                                    th: ({children}) => <th className="border border-slate-300 px-3 py-2 text-left font-semibold">{children}</th>,
                                    td: ({children}) => <td className="border border-slate-300 px-3 py-2">{children}</td>,
                                  }}
                                >
                                  {msg.content}
                                </ReactMarkdown>
                              </div>
                            )}
                          </div>
                        </motion.div>
                      ))}
                    </AnimatePresence>
                  )}
                  {loading && (
                    <div className="flex justify-start">
                      <div className="bg-slate-100 rounded-2xl p-4">
                        <div className="flex items-center gap-2">
                          <motion.div
                            className="w-2 h-2 bg-slate-400 rounded-full"
                            animate={{ scale: [1, 1.2, 1] }}
                            transition={{ duration: 0.6, repeat: Infinity, delay: 0 }}
                          />
                          <motion.div
                            className="w-2 h-2 bg-slate-400 rounded-full"
                            animate={{ scale: [1, 1.2, 1] }}
                            transition={{ duration: 0.6, repeat: Infinity, delay: 0.2 }}
                          />
                          <motion.div
                            className="w-2 h-2 bg-slate-400 rounded-full"
                            animate={{ scale: [1, 1.2, 1] }}
                            transition={{ duration: 0.6, repeat: Infinity, delay: 0.4 }}
                          />
                        </div>
                      </div>
                    </div>
                  )}
                  <div ref={messagesEndRef} />
                </div>

                {/* 输入区域 */}
                <div className="p-6 border-t border-slate-200">
                  <div className="flex gap-3">
                    <input
                      type="text"
                      value={question}
                      onChange={(e) => setQuestion(e.target.value)}
                      onKeyPress={(e) => e.key === 'Enter' && !e.shiftKey && handleSubmitQuestion()}
                      placeholder="输入您的问题..."
                      className="flex-1 px-4 py-3 border border-slate-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                      disabled={loading}
                    />
                    <motion.button
                      onClick={handleSubmitQuestion}
                      disabled={!question.trim() || selectedKbIds.size === 0 || loading}
                      className="px-6 py-3 bg-primary-500 text-white rounded-xl font-medium hover:bg-primary-600 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                      whileHover={{ scale: loading ? 1 : 1.02 }}
                      whileTap={{ scale: loading ? 1 : 0.98 }}
                    >
                      发送
                    </motion.button>
                  </div>
                </div>
              </>
            ) : (
              <div className="flex-1 flex items-center justify-center text-slate-400">
                <div className="text-center">
                  <svg className="w-16 h-16 mx-auto mb-4 opacity-50" viewBox="0 0 24 24" fill="none">
                    <path d="M21 15C21 15.5304 20.7893 16.0391 20.4142 16.4142C20.0391 16.7893 19.5304 17 19 17H7L3 21V5C3 4.46957 3.21071 3.96086 3.58579 3.58579C3.96086 3.21071 4.46957 3 5 3H19C19.5304 3 20.0391 3.21071 20.4142 3.58579C20.7893 3.96086 21 4.46957 21 5V15Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                  </svg>
                  <p>请先选择一个知识库</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* 删除确认对话框 */}
      <ConfirmDialog
        open={deleteConfirm !== null}
        title="删除知识库"
        message={deleteConfirm ? `确定要删除知识库"${deleteConfirm.name}"吗？删除后无法恢复。` : ''}
        confirmText="确定删除"
        cancelText="取消"
        confirmVariant="danger"
        loading={deletingId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteConfirm(null)}
      />
    </div>
  );
}

