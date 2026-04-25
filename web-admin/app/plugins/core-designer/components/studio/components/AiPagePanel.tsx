/**
 * AiPagePanel — Multi-turn conversational AI panel for page generation
 *
 * Replaces the single-shot AiPageGenerateDialog with a persistent
 * side panel that supports streaming, message history, quick commands,
 * and context-aware prompts.
 *
 * @since 4.2.0
 */

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { auraBotApi } from '~/plugins/core-aurabot/services/auraBotApi';
import { get } from '~/shared/services/http-client';
import {
  buildContextPrompt,
  parsePageDslResponse,
  type MergeMode,
} from './ai-page-prompt';
import { AiQuickCommands } from './AiQuickCommands';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';

// ============================================================================
// Types
// ============================================================================

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: number;
  /** Whether the message is still being streamed */
  streaming?: boolean;
  /** Error flag for assistant messages */
  error?: boolean;
}

export interface AiPagePanelProps {
  /** Whether the panel is open */
  open: boolean;
  /** Toggle panel visibility */
  onToggle: () => void;
  /** Called when AI generates page DSL */
  onGenerated: (dsl: {
    kind: PageSchema['kind'];
    blocks: PageSchema['blocks'];
    layout: PageSchema['layout'];
    schemaVersion: 2;
    mergeMode: MergeMode;
  }) => void;
  /** Current page ID — used for conversation persistence */
  pageId: string;
  /** Model code for field context */
  modelCode?: string;
  /** Model fields for context injection */
  modelFields?: Array<{ code: string; name: string; type: string }>;
  /** Current blocks on the canvas (injected into prompt each turn) */
  currentBlocks?: any[];
  /** Current schema version */
  schemaVersion?: number;
}

// ============================================================================
// Component
// ============================================================================

export const AiPagePanel: React.FC<AiPagePanelProps> = ({
  open,
  onToggle,
  onGenerated,
  pageId,
  modelCode,
  modelFields,
  currentBlocks,
  schemaVersion,
}) => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const sessionIdRef = useRef(`page-ai-${pageId}`);
  const messagesRef = useRef<ChatMessage[]>([]);
  messagesRef.current = messages;

  // Auto-load model fields when modelCode is available but modelFields prop is not provided
  const [autoFields, setAutoFields] = useState<Array<{ code: string; name: string; type: string }>>([]);
  useEffect(() => {
    if (modelFields?.length || !modelCode) return;
    let cancelled = false;
    get<Array<{ code: string; name: string; fieldType: string }>>(`/api/meta/models/code/${modelCode}/fields`)
      .then((res) => {
        if (cancelled || !res?.data) return;
        setAutoFields(
          res.data.map((f) => ({
            code: f.code,
            name: f.name || f.code,
            type: f.fieldType || 'text',
          })),
        );
      })
      .catch(() => { /* non-fatal — AI will work without field context */ });
    return () => { cancelled = true; };
  }, [modelCode, modelFields]);

  const effectiveFields = modelFields?.length ? modelFields : autoFields;

  // Auto-scroll to bottom when messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Focus input when panel opens
  useEffect(() => {
    if (open) {
      setTimeout(() => inputRef.current?.focus(), 100);
    }
  }, [open]);

  // Update sessionId when pageId changes
  useEffect(() => {
    sessionIdRef.current = `page-ai-${pageId}`;
  }, [pageId]);

  const sendMessage = useCallback(
    async (text: string) => {
      if (!text.trim() || isStreaming) return;

      const userMsg: ChatMessage = {
        id: `user-${Date.now()}`,
        role: 'user',
        content: text.trim(),
        timestamp: Date.now(),
      };

      const assistantMsgId = `assistant-${Date.now()}`;
      const assistantMsg: ChatMessage = {
        id: assistantMsgId,
        role: 'assistant',
        content: '',
        timestamp: Date.now(),
        streaming: true,
      };

      // Build multi-turn history: system prompt + previous conversation turns
      const systemPrompt = buildContextPrompt({
        modelFields: effectiveFields,
        currentBlocks,
        schemaVersion,
        modelCode,
      });

      const history: Array<{ role: string; content: string }> = [
        { role: 'system', content: systemPrompt },
      ];
      // Include previous messages for multi-turn context (exclude the new user msg)
      for (const msg of messagesRef.current) {
        if (msg.role === 'user' || msg.role === 'assistant') {
          if (msg.content && !msg.streaming && !msg.error) {
            history.push({ role: msg.role, content: msg.content });
          }
        }
      }

      setMessages((prev) => [...prev, userMsg, assistantMsg]);
      setInput('');
      setIsStreaming(true);

      try {
        await auraBotApi.chatStream(
          {
            sessionId: sessionIdRef.current,
            message: text.trim(),
            history,
          },
          {
            onChunk: (chunk) => {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsgId
                    ? { ...m, content: m.content + chunk }
                    : m,
                ),
              );
            },
            onDone: (fullContent) => {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsgId
                    ? { ...m, content: fullContent || m.content, streaming: false }
                    : m,
                ),
              );
              setIsStreaming(false);

              // Try to parse as DSL
              try {
                const dsl = parsePageDslResponse(fullContent);
                onGenerated(dsl);
              } catch {
                // Not valid DSL — that's fine, it may be a conversational reply
              }
            },
            onError: (errMsg) => {
              const errorText = typeof errMsg === 'string' ? errMsg : 'AI 生成失败';
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsgId
                    ? { ...m, content: errorText, streaming: false, error: true }
                    : m,
                ),
              );
              setIsStreaming(false);
            },
          },
        );
      } catch (err) {
        const errorText = err instanceof Error ? err.message : 'AI 生成失败';
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantMsgId
              ? { ...m, content: errorText, streaming: false, error: true }
              : m,
          ),
        );
        setIsStreaming(false);
      }
    },
    [isStreaming, effectiveFields, currentBlocks, schemaVersion, modelCode, onGenerated],
  );

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      sendMessage(input);
    },
    [input, sendMessage],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage(input);
      }
    },
    [input, sendMessage],
  );

  const handleQuickCommand = useCallback(
    (prompt: string) => {
      sendMessage(prompt);
    },
    [sendMessage],
  );

  if (!open) return null;

  return (
    <div
      className="flex h-full w-[380px] flex-col border-l border-gray-200 bg-white"
      data-testid="ai-page-panel"
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 px-4 py-3">
        <div className="flex items-center gap-2">
          <span className="text-base">&#x2728;</span>
          <h3 className="text-sm font-semibold text-gray-900">AI 助手</h3>
        </div>
        <button
          onClick={onToggle}
          className="rounded-md p-1 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
          data-testid="ai-panel-close"
          title="关闭 AI 面板"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* Quick Commands */}
      <AiQuickCommands onCommand={handleQuickCommand} disabled={isStreaming} />

      {/* Messages */}
      <div className="flex-1 overflow-y-auto px-4 py-3" data-testid="ai-panel-messages">
        {messages.length === 0 && (
          <div className="flex h-full flex-col items-center justify-center text-center">
            <div className="mb-3 text-3xl">&#x2728;</div>
            <p className="mb-1 text-sm font-medium text-gray-700">AI Page Assistant</p>
            <p className="text-xs text-gray-400">
              Describe the page you want or use a quick command above.
            </p>
          </div>
        )}

        {messages.map((msg) => (
          <div
            key={msg.id}
            className={`mb-3 ${msg.role === 'user' ? 'flex justify-end' : ''}`}
          >
            <div
              className={`max-w-[90%] rounded-lg px-3 py-2 text-sm ${
                msg.role === 'user'
                  ? 'bg-purple-600 text-white'
                  : msg.error
                    ? 'bg-red-50 text-red-600'
                    : 'bg-gray-100 text-gray-800'
              }`}
              data-testid={`ai-msg-${msg.role}`}
            >
              <div className="whitespace-pre-wrap break-words">
                {msg.content || (msg.streaming ? '...' : '')}
              </div>
              {msg.streaming && (
                <span className="mt-1 inline-block h-2 w-2 animate-pulse rounded-full bg-purple-500" />
              )}
            </div>
          </div>
        ))}
        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <form onSubmit={handleSubmit} className="border-t border-gray-200 p-3">
        <div className="flex items-end gap-2">
          <textarea
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Describe what you want..."
            rows={2}
            disabled={isStreaming}
            className="flex-1 resize-none rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-purple-500 focus:outline-none focus:ring-1 focus:ring-purple-500 disabled:opacity-50"
            data-testid="ai-panel-input"
          />
          <button
            type="submit"
            disabled={isStreaming || !input.trim()}
            className="flex h-9 w-9 items-center justify-center rounded-lg bg-purple-600 text-white transition-colors hover:bg-purple-700 disabled:opacity-40"
            data-testid="ai-panel-send"
            title="Send message"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 19V5m0 0l-7 7m7-7l7 7"
              />
            </svg>
          </button>
        </div>
      </form>
    </div>
  );
};

export default AiPagePanel;
