import { useState, useCallback } from 'react';

interface Props {
  data: any;
  maxHeight?: string;
}

export function JsonViewer({ data, maxHeight = '400px' }: Props) {
  const [collapsed, setCollapsed] = useState(false);
  const [copied, setCopied] = useState(false);

  const formatted =
    data == null ? null : typeof data === 'string' ? data : JSON.stringify(data, null, 2);

  const handleCopy = useCallback(() => {
    if (!formatted) return;
    navigator.clipboard.writeText(formatted).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }, [formatted]);

  if (data == null) {
    return <span className="text-sm text-gray-400 italic dark:text-gray-500">null</span>;
  }

  return (
    <div className="group relative">
      <div className="absolute top-1 right-1 z-10 flex gap-1">
        <button
          onClick={handleCopy}
          className="rounded border border-gray-200 bg-white px-2 py-0.5 text-xs text-gray-600 shadow-sm hover:bg-gray-50 hover:text-gray-900 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-gray-700"
          title="Copy"
          data-testid="json-viewer-copy"
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
        <button
          onClick={() => setCollapsed(!collapsed)}
          className="rounded bg-white/80 px-1.5 py-0.5 text-xs text-gray-400 hover:text-gray-600 dark:bg-gray-800/80 dark:hover:text-gray-300"
        >
          {collapsed ? 'Expand' : 'Collapse'}
        </button>
      </div>
      {collapsed ? (
        <div
          className="cursor-pointer rounded bg-gray-50 p-2 font-mono text-xs text-gray-500 dark:bg-gray-900 dark:text-gray-400"
          onClick={() => setCollapsed(false)}
        >
          {typeof data === 'string'
            ? data.slice(0, 120) + (data.length > 120 ? '...' : '')
            : '{...}'}
        </div>
      ) : (
        <pre
          className="overflow-auto rounded bg-gray-50 p-3 font-mono text-xs break-words whitespace-pre-wrap text-gray-800 dark:bg-gray-900 dark:text-gray-200"
          style={{ maxHeight }}
        >
          {formatted}
        </pre>
      )}
    </div>
  );
}
