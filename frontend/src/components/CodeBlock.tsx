import { useEffect, useState, lazy, Suspense } from 'react';
import { Check, Copy } from 'lucide-react';

// Lazy load SyntaxHighlighter
const SyntaxHighlighter = lazy(() =>
  import('react-syntax-highlighter/dist/esm/prism').then(module => ({ default: module.default }))
);

interface CodeBlockProps {
  language?: string;
  children: string;
}

export default function CodeBlock({ language, children }: CodeBlockProps) {
  const [copied, setCopied] = useState(false);
  const [syntaxTheme, setSyntaxTheme] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    let active = true;
    void import('react-syntax-highlighter/dist/esm/styles/prism').then(({ oneDark }) => {
      if (active) setSyntaxTheme(oneDark as Record<string, unknown>);
    });
    return () => {
      active = false;
    };
  }, []);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(children);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('复制失败:', err);
    }
  };

  // 清理代码内容
  const code = children?.trim() || '';

  return (
    <div className="relative group my-3">
      {/* 语言标签和复制按钮 */}
      <div className="flex items-center justify-between px-4 py-2 bg-slate-700 rounded-t-xl border-b border-slate-600">
        <span className="text-xs text-slate-400 font-mono">
          {language || 'code'}
        </span>
        <button
          onClick={handleCopy}
          className="flex items-center gap-1.5 px-2 py-1 text-xs text-slate-400 hover:text-white hover:bg-slate-600 rounded transition-colors"
          title="复制代码"
        >
          {copied ? (
            <>
              <Check className="w-3.5 h-3.5 text-green-400" />
              <span className="text-green-400">已复制</span>
            </>
          ) : (
            <>
              <Copy className="w-3.5 h-3.5" />
              <span>复制</span>
            </>
          )}
        </button>
      </div>

      {/* 代码内容 */}
      <div className="bg-[#282c34] rounded-b-xl text-sm leading-6">
        <Suspense fallback={
          <div className="p-4 text-slate-400 font-mono text-xs">Loading code...</div>
        }>
          <SyntaxHighlighter
            language={language || 'text'}
            style={syntaxTheme || {}}
            customStyle={{
              margin: 0,
              borderTopLeftRadius: 0,
              borderTopRightRadius: 0,
              borderBottomLeftRadius: '0.75rem',
              borderBottomRightRadius: '0.75rem',
              fontSize: '0.875rem',
              lineHeight: '1.5',
            }}
            showLineNumbers={code.split('\n').length > 3}
            wrapLines
          >
            {code}
          </SyntaxHighlighter>
        </Suspense>
      </div>
    </div>
  );
}
