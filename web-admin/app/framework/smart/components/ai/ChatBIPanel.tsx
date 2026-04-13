/**
 * ChatBIPanel — natural language to chart conversation panel.
 *
 * Users type plain-English questions; the panel calls POST /api/ai/chat-bi/query
 * and renders the result as a data table or chart using AIChartRenderer.
 */

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { fetchResult } from '~/shared/services/http-client/HttpClient';
import { AIChartRenderer } from './AIChartRenderer';

interface ChatBIResult {
  interpretation: string;
  modelCode: string | null;
  columns: string[];
  records: Record<string, unknown>[];
  chartType: string;
  chartConfig: Record<string, unknown>;
  sql: string;
  total: number;
}

interface Message {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  result?: ChatBIResult;
  error?: string;
  loading?: boolean;
}

const EXAMPLE_QUESTIONS = [
  'Count leads by status',
  'Top 10 opportunities by amount',
  'Count contacts per company',
  'Show all recent orders',
];

let msgCounter = 0;
function nextId() {
  return `msg_${Date.now()}_${++msgCounter}`;
}

export function ChatBIPanel() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [question, setQuestion] = useState('');
  const [modelCode, setModelCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [sqlVisible, setSqlVisible] = useState<Record<string, boolean>>({});
  const [toast, setToast] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const showToast = useCallback((msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3000);
  }, []);

  const handleSubmit = useCallback(async () => {
    const q = question.trim();
    if (!q || loading) return;

    const userMsgId = nextId();
    const assistantMsgId = nextId();

    setMessages((prev) => [
      ...prev,
      { id: userMsgId, role: 'user', text: q },
      { id: assistantMsgId, role: 'assistant', text: '', loading: true },
    ]);
    setQuestion('');
    setLoading(true);

    try {
      const res = await fetchResult<ChatBIResult>('/api/ai/chat-bi/query', {
        method: 'post',
        params: {
          question: q,
          ...(modelCode.trim() ? { modelCode: modelCode.trim() } : {}),
        },
      });

      if (res.success && res.data) {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsgId
              ? { ...m, loading: false, text: res.data!.interpretation, result: res.data! }
              : m,
          ),
        );
      } else {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsgId
              ? { ...m, loading: false, text: '', error: res.message || 'Query failed' }
              : m,
          ),
        );
      }
    } catch (e: unknown) {
      const errMsg = e instanceof Error ? e.message : 'Network error';
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantMsgId ? { ...m, loading: false, text: '', error: errMsg } : m,
        ),
      );
    } finally {
      setLoading(false);
    }
  }, [question, modelCode, loading]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  };

  const toggleSql = (id: string) => {
    setSqlVisible((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  const handleSaveWidget = () => {
    showToast('Save as Dashboard Widget — coming soon!');
  };

  return (
    <div className="flex h-full flex-col bg-gray-950 text-gray-100" data-testid="chat-bi-panel">
      {/* Header */}
      <div className="flex shrink-0 items-center justify-between border-b border-gray-800 px-6 py-4">
        <div>
          <h1 className="text-lg font-semibold text-white">ChatBI</h1>
          <p className="mt-0.5 text-xs text-gray-400">Ask data questions in plain English</p>
        </div>
        <div className="flex items-center gap-2">
          <span className="rounded-full border border-indigo-700/50 bg-indigo-900/40 px-2 py-1 text-xs text-indigo-300">
            Keyword Mode
          </span>
        </div>
      </div>

      {/* Message History */}
      <div className="flex-1 space-y-4 overflow-y-auto px-4 py-4">
        {messages.length === 0 && (
          <div className="flex h-full flex-col items-center justify-center pb-20 text-center">
            <div className="mb-4 text-4xl">🤖</div>
            <h2 className="mb-2 text-lg font-medium text-gray-300">Ask your data a question</h2>
            <p className="mb-6 max-w-md text-sm text-gray-500">
              Type a natural language question below. Specify a model code for best results.
            </p>
            <div className="flex flex-wrap justify-center gap-2">
              {EXAMPLE_QUESTIONS.map((q) => (
                <button
                  key={q}
                  onClick={() => setQuestion(q)}
                  className="rounded-full border border-gray-700 bg-gray-800 px-3 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-700"
                >
                  {q}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((msg) => (
          <div
            key={msg.id}
            className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
          >
            {msg.role === 'user' ? (
              <div className="max-w-[70%] rounded-2xl rounded-tr-sm bg-indigo-600 px-4 py-2.5 text-sm text-white">
                {msg.text}
              </div>
            ) : (
              <div className="max-w-full flex-1 space-y-3">
                {/* Assistant bubble */}
                <div className="rounded-2xl rounded-tl-sm bg-gray-800 px-4 py-3">
                  {msg.loading ? (
                    <div className="flex items-center gap-2 text-sm text-gray-400">
                      <span className="animate-pulse">Analyzing your question</span>
                      <span className="flex gap-1">
                        <span
                          className="h-1.5 w-1.5 animate-bounce rounded-full bg-indigo-400"
                          style={{ animationDelay: '0ms' }}
                        />
                        <span
                          className="h-1.5 w-1.5 animate-bounce rounded-full bg-indigo-400"
                          style={{ animationDelay: '150ms' }}
                        />
                        <span
                          className="h-1.5 w-1.5 animate-bounce rounded-full bg-indigo-400"
                          style={{ animationDelay: '300ms' }}
                        />
                      </span>
                    </div>
                  ) : msg.error ? (
                    <div className="flex items-start gap-2 text-sm text-red-400">
                      <span>⚠️</span>
                      <span>{msg.error}</span>
                    </div>
                  ) : (
                    <div className="text-sm text-gray-200">{msg.text}</div>
                  )}
                </div>

                {/* Result card */}
                {msg.result && !msg.loading && (
                  <div className="overflow-hidden rounded-xl border border-gray-700 bg-gray-900">
                    {/* Result header */}
                    <div className="flex items-center justify-between border-b border-gray-700 bg-gray-800 px-4 py-2">
                      <div className="flex items-center gap-3">
                        <span className="text-xs text-gray-400">
                          {msg.result.total} row{msg.result.total !== 1 ? 's' : ''}
                        </span>
                        {msg.result.modelCode && (
                          <span className="rounded bg-gray-700 px-1.5 py-0.5 font-mono text-xs text-gray-300">
                            {msg.result.modelCode}
                          </span>
                        )}
                        <span className="rounded bg-indigo-900/50 px-1.5 py-0.5 text-xs text-indigo-300">
                          {msg.result.chartType}
                        </span>
                      </div>
                      <div className="flex items-center gap-2">
                        <button
                          onClick={() => toggleSql(msg.id)}
                          className="text-xs text-gray-400 transition-colors hover:text-gray-200"
                        >
                          {sqlVisible[msg.id] ? 'Hide SQL' : 'Show SQL'}
                        </button>
                        <button
                          onClick={handleSaveWidget}
                          className="rounded bg-indigo-700 px-2 py-1 text-xs text-white transition-colors hover:bg-indigo-600"
                        >
                          Save Widget
                        </button>
                      </div>
                    </div>

                    {/* SQL preview */}
                    {sqlVisible[msg.id] && msg.result.sql && (
                      <div className="border-b border-gray-700 bg-gray-950 px-4 py-2">
                        <code className="font-mono text-xs break-all text-green-400">
                          {msg.result.sql}
                        </code>
                      </div>
                    )}

                    {/* Chart / Table */}
                    <div className="p-4">
                      <AIChartRenderer
                        chartType={msg.result.chartType}
                        data={msg.result.records}
                        columns={msg.result.columns}
                        chartConfig={msg.result.chartConfig}
                      />
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        ))}

        <div ref={bottomRef} />
      </div>

      {/* Input Area */}
      <div className="shrink-0 border-t border-gray-800 bg-gray-950 px-4 py-3">
        <div className="flex items-end gap-3">
          {/* Model code hint input */}
          <div className="hidden sm:block">
            <input
              type="text"
              value={modelCode}
              onChange={(e) => setModelCode(e.target.value)}
              placeholder="model code (optional)"
              className="w-40 rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-xs text-gray-300 placeholder-gray-500 focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 focus:outline-none"
            />
          </div>

          {/* Question textarea */}
          <textarea
            ref={inputRef}
            rows={1}
            value={question}
            onChange={(e) => {
              setQuestion(e.target.value);
              e.target.style.height = 'auto';
              e.target.style.height = `${Math.min(e.target.scrollHeight, 120)}px`;
            }}
            onKeyDown={handleKeyDown}
            placeholder="Ask a data question… (Enter to send, Shift+Enter for newline)"
            disabled={loading}
            data-testid="chat-bi-input"
            className="max-h-[120px] min-h-[44px] flex-1 resize-none overflow-y-auto rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-gray-100 placeholder-gray-500 transition-colors focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 focus:outline-none disabled:opacity-50"
          />

          {/* Send button */}
          <button
            onClick={handleSubmit}
            disabled={loading || !question.trim()}
            data-testid="chat-bi-send"
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-indigo-600 text-white transition-colors hover:bg-indigo-500 disabled:cursor-not-allowed disabled:bg-gray-700"
          >
            {loading ? (
              <svg className="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
                <circle
                  className="opacity-25"
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="currentColor"
                  strokeWidth="4"
                />
                <path
                  className="opacity-75"
                  fill="currentColor"
                  d="M4 12a8 8 0 018-8V0C5.37 0 0 5.37 0 12h4z"
                />
              </svg>
            ) : (
              <svg
                className="h-4 w-4"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
              >
                <line x1="22" y1="2" x2="11" y2="13" />
                <polygon points="22 2 15 22 11 13 2 9 22 2" />
              </svg>
            )}
          </button>
        </div>
        <p className="mt-1.5 ml-1 text-xs text-gray-600">
          Tip: provide a model code for more accurate results. Example: "count crm_lead by status"
        </p>
      </div>

      {/* Toast */}
      {toast && (
        <div className="fixed bottom-24 left-1/2 z-50 -translate-x-1/2 rounded-lg bg-gray-700 px-4 py-2 text-sm text-white shadow-lg transition-all">
          {toast}
        </div>
      )}
    </div>
  );
}
