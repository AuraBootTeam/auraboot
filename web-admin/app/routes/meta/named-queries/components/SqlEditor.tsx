/**
 * SQL Editor component built on Monaco Editor.
 * Provides SQL syntax highlighting and table name auto-completion.
 */

import React, { useRef, useCallback, useEffect, useState } from 'react';
import type { Monaco, OnMount } from '@monaco-editor/react';
import { registerSqlCompletion, disposeSqlCompletion } from './sql-completion';

// Lazy import Monaco Editor (SSR-safe)
const LazyEditor = React.lazy(() => import('@monaco-editor/react'));

interface SqlEditorProps {
  value: string;
  onChange: (value: string) => void;
  readOnly?: boolean;
  height?: string;
  tableNames?: string[];
  placeholder?: string;
}

export default function SqlEditor({
  value,
  onChange,
  readOnly = false,
  height = '160px',
  tableNames = [],
  placeholder,
}: SqlEditorProps) {
  const monacoRef = useRef<Monaco | null>(null);
  const [isClient, setIsClient] = useState(false);

  useEffect(() => {
    setIsClient(true);
    return () => {
      disposeSqlCompletion();
    };
  }, []);

  // Re-register completion when tableNames change
  useEffect(() => {
    if (monacoRef.current) {
      registerSqlCompletion(monacoRef.current, tableNames);
    }
  }, [tableNames]);

  const handleMount: OnMount = useCallback(
    (editor, monaco) => {
      monacoRef.current = monaco;
      registerSqlCompletion(monaco, tableNames);

      // Show placeholder text when empty
      if (placeholder && !value) {
        const model = editor.getModel();
        if (model) {
          editor.updateOptions({
            placeholder: undefined, // Monaco doesn't support native placeholder
          });
        }
      }
    },
    [tableNames, placeholder, value],
  );

  const handleChange = useCallback(
    (val: string | undefined) => {
      onChange(val || '');
    },
    [onChange],
  );

  if (!isClient) {
    // SSR fallback
    return (
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        readOnly={readOnly}
        rows={5}
        className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        placeholder={placeholder}
      />
    );
  }

  return (
    <div className="overflow-hidden rounded-md border border-gray-300">
      {/* Testability input — Playwright can fill this to set the SQL value */}
      <input
        data-testid="sql-editor-value"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        style={{
          position: 'absolute',
          opacity: 0,
          height: 0,
          width: 0,
          overflow: 'hidden',
          pointerEvents: 'none',
        }}
        tabIndex={-1}
        aria-hidden="true"
      />
      <React.Suspense
        fallback={
          <div className="flex items-center justify-center bg-gray-50" style={{ height }}>
            <span className="text-sm text-gray-400">Loading editor...</span>
          </div>
        }
      >
        <LazyEditor
          height={height}
          defaultLanguage="sql"
          value={value}
          onChange={handleChange}
          onMount={handleMount}
          theme="vs"
          loading={null}
          options={{
            readOnly,
            minimap: { enabled: false },
            lineNumbers: 'on',
            scrollBeyondLastLine: false,
            fontSize: 13,
            fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', Menlo, monospace",
            tabSize: 2,
            wordWrap: 'on',
            automaticLayout: true,
            suggestOnTriggerCharacters: true,
            quickSuggestions: true,
            folding: false,
            renderLineHighlight: 'line',
            overviewRulerBorder: false,
            scrollbar: {
              verticalScrollbarSize: 8,
              horizontalScrollbarSize: 8,
            },
            padding: { top: 8, bottom: 8 },
          }}
        />
      </React.Suspense>
    </div>
  );
}
