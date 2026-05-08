/**
 * AuraBotChat
 *
 * Main chat interface component for AuraBot.
 * Includes message list, input area, and quick actions.
 *
 * @since 1.0.0
 */

import React, { useState, useCallback, useRef, useEffect, type KeyboardEvent } from 'react';
import {
  useAuraBot,
  type SimpleMessage,
  type ChatImageAttachment,
} from '../components-shell/AuraBotProvider';
import { useI18n } from '~/contexts/I18nContext';
import { ToolResultCard } from './ToolResultCard';
import { ChatBiResultCard } from './ChatBiResultCard';
import { ConfirmCard } from './ConfirmCard';
import { SkillPreviewCard } from '../components-shell/SkillPreviewCard';
import { ModelSuggestionCard } from './ModelSuggestionCard';
import { ResultContractView } from './ResultContractView';
import { ThinkingBlock } from './ThinkingBlock';

// ============================================================================
// Icons
// ============================================================================

function SendIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <line x1="22" y1="2" x2="11" y2="13" />
      <polygon points="22 2 15 22 11 13 2 9 22 2" />
    </svg>
  );
}

function PaperclipIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48" />
    </svg>
  );
}

function BookIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <path d="M4 19.5v-15A2.5 2.5 0 016.5 2H20v20H6.5a2.5 2.5 0 010-5H20" />
    </svg>
  );
}

function LoadingDots() {
  return (
    <span className="inline-flex items-center gap-1">
      <span
        className="h-1.5 w-1.5 animate-bounce rounded-full bg-blue-500"
        style={{ animationDelay: '0ms' }}
      />
      <span
        className="h-1.5 w-1.5 animate-bounce rounded-full bg-blue-500"
        style={{ animationDelay: '150ms' }}
      />
      <span
        className="h-1.5 w-1.5 animate-bounce rounded-full bg-blue-500"
        style={{ animationDelay: '300ms' }}
      />
    </span>
  );
}

function TraceLink({ traceId }: { traceId: string }) {
  return (
    <a
      href={`/aurabot/traces/${traceId}`}
      target="_blank"
      rel="noopener noreferrer"
      className="mt-1.5 flex items-center gap-1 text-[11px] text-gray-400 transition-colors hover:text-blue-500"
      onClick={(e) => e.stopPropagation()}
    >
      <svg
        className="h-3 w-3"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
      >
        <path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z" />
        <circle cx="12" cy="12" r="3" />
      </svg>
      View Trace
    </a>
  );
}

// ============================================================================
// Message Components
// ============================================================================

interface MessageBubbleProps {
  message: SimpleMessage;
  onConfirm?: (toolId: string) => void;
  onCancel?: (toolId: string) => void;
  isLoading?: boolean;
}

function MessageBubble({ message, onConfirm, onCancel, isLoading }: MessageBubbleProps) {
  const isUser = message.sender === 'user';

  // Error message
  if (message.type === 'error') {
    return (
      <div className="mb-3 flex justify-start">
        <div className="max-w-[85%] rounded-2xl rounded-bl-md border border-red-200 bg-red-50 px-4 py-3 text-red-800">
          <div className="flex items-start gap-2">
            <span className="mt-0.5 text-red-500">!</span>
            <p className="text-sm text-red-600">{message.content}</p>
          </div>
          {message.traceId && <TraceLink traceId={message.traceId} />}
        </div>
      </div>
    );
  }

  // Anthropic Extended Thinking trace — collapsed by default. P0-2.
  if (message.type === 'thinking') {
    return <ThinkingBlock content={message.content} tokens={message.thinkingTokens} />;
  }

  // Tool loading — spinner + text
  if (message.type === 'tool_loading') {
    return (
      <div className="mb-3 flex justify-start">
        <div className="flex items-center gap-2 rounded-2xl rounded-bl-md bg-gray-50 px-4 py-2.5 dark:bg-gray-700/50">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          <span className="text-sm text-gray-600 dark:text-gray-300">{message.content}</span>
        </div>
      </div>
    );
  }

  // ResultContract — structured Skill/tool output (PR-11). Renders via the
  // renderHint-dispatching ResultContractView; see spec §3.4.
  if (message.type === 'result_contract' && message.resultContract) {
    return (
      <div className="mb-3 flex justify-start">
        <div className="max-w-[88%] rounded-2xl rounded-bl-md bg-white px-3 py-2 shadow-sm dark:bg-gray-700">
          <ResultContractView contract={message.resultContract} />
        </div>
      </div>
    );
  }

  // Tool result — any result with records array gets a chart card; others get collapsible table
  if (message.type === 'tool_result') {
    // toolResult shape varies: { records, ... } or { success, data: { records, ... }, durationMs }
    const resultData = message.toolResult?.data || message.toolResult;
    if (resultData?.records && Array.isArray(resultData.records) && resultData.records.length > 0) {
      return <ChatBiResultCard result={resultData as any} />;
    }
    const isModelSuggest =
      message.toolName === 'builtin__model_suggest' && message.toolResult?.modelCode;
    if (isModelSuggest) {
      return <ModelSuggestionCard suggestion={message.toolResult as any} />;
    }
    return (
      <ToolResultCard
        toolName={message.toolName || 'unknown'}
        result={message.toolResult || {}}
        success={true}
      />
    );
  }

  // C-5 T7: skill confirmation — risk-tier card with preview JSON.
  if (message.type === 'skill_preview_card' && message.toolId) {
    return (
      <SkillPreviewCard
        turnId={message.pendingTurnId || ''}
        toolId={message.toolId}
        skillName={message.skillName || message.toolName || 'unknown'}
        preview={message.skillPreview || {}}
        previewToken={message.previewToken || ''}
        riskLevel={message.riskLevel || 'MEDIUM'}
        onConfirm={onConfirm || (() => {})}
        onCancel={onCancel || (() => {})}
        disabled={isLoading}
      />
    );
  }

  // Confirm card — amber confirmation UI
  if (message.type === 'confirm_card' && message.toolId) {
    return (
      <ConfirmCard
        toolId={message.toolId}
        toolName={message.toolName || 'unknown'}
        description={message.content}
        input={message.toolInput || {}}
        onConfirm={onConfirm || (() => {})}
        onCancel={onCancel || (() => {})}
        disabled={isLoading}
      />
    );
  }

  // Tool executed — green check + text
  if (message.type === 'tool_executed') {
    return (
      <div className="mb-3 flex justify-start">
        <div className="flex items-center gap-2 rounded-2xl rounded-bl-md border border-green-200 bg-green-50 px-4 py-2.5 dark:border-green-800 dark:bg-green-900/20">
          <svg
            className="h-4 w-4 flex-shrink-0 text-green-500"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <path d="M20 6L9 17l-5-5" />
          </svg>
          <span className="text-sm text-green-700 dark:text-green-300">{message.content}</span>
        </div>
      </div>
    );
  }

  // Tool cancelled — grey italic text
  if (message.type === 'tool_cancelled') {
    return (
      <div className="mb-3 flex justify-start">
        <div className="rounded-2xl rounded-bl-md bg-gray-50 px-4 py-2.5 dark:bg-gray-700/30">
          <span className="text-sm text-gray-400 italic dark:text-gray-500">{message.content}</span>
        </div>
      </div>
    );
  }

  // Default: text message
  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'} mb-3`}>
      <div
        className={`max-w-[85%] rounded-2xl px-4 py-2.5 ${
          isUser
            ? 'rounded-br-md bg-blue-600 text-white'
            : 'rounded-bl-md bg-gray-100 text-gray-900'
        } `}
      >
        {!isUser ? (
          <BotMessageContent content={message.content} />
        ) : (
          <p className="text-sm break-words whitespace-pre-wrap">{message.content}</p>
        )}
        {!isUser && message.traceId && <TraceLink traceId={message.traceId} />}
      </div>
    </div>
  );
}

// ============================================================================
// Bot Message Content — parses RAG citation markers [Source: docName, Chunk N]
// ============================================================================

const CITATION_REGEX = /\[Source:\s*([^,\]]+),\s*Chunk\s*(\d+)\]/g;

function BotMessageContent({ content }: { content: string }) {
  const parts: React.ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let key = 0;

  // Reset regex state
  CITATION_REGEX.lastIndex = 0;

  while ((match = CITATION_REGEX.exec(content)) !== null) {
    // Text before the citation
    if (match.index > lastIndex) {
      parts.push(
        <span key={key++} className="break-words whitespace-pre-wrap">
          {content.slice(lastIndex, match.index)}
        </span>,
      );
    }
    // Citation badge
    const docName = match[1].trim();
    const chunkIndex = match[2];
    parts.push(<CitationBadge key={key++} docName={docName} chunkIndex={chunkIndex} />);
    lastIndex = match.index + match[0].length;
  }

  // Remaining text
  if (lastIndex < content.length) {
    parts.push(
      <span key={key++} className="break-words whitespace-pre-wrap">
        {content.slice(lastIndex)}
      </span>,
    );
  }

  // No citations found — render plain text
  if (parts.length === 0) {
    return <p className="text-sm break-words whitespace-pre-wrap">{content}</p>;
  }

  return <div className="text-sm">{parts}</div>;
}

function CitationBadge({ docName, chunkIndex }: { docName: string; chunkIndex: string }) {
  const [expanded, setExpanded] = React.useState(false);

  return (
    <button
      onClick={() => setExpanded(!expanded)}
      className="mx-0.5 inline-flex cursor-pointer items-center gap-1 rounded-md border border-blue-200 bg-blue-50 px-1.5 py-0.5 text-xs text-blue-700 transition-colors hover:bg-blue-100 dark:border-blue-800 dark:bg-blue-900/30 dark:text-blue-300"
      title={`${docName} — Chunk ${chunkIndex}`}
    >
      <svg className="h-3 w-3" viewBox="0 0 16 16" fill="currentColor">
        <path d="M1 2.828c.885-.37 2.154-.769 3.388-.893 1.33-.134 2.458.063 3.112.752v9.746c-.935-.53-2.12-.603-3.213-.493-1.18.12-2.37.461-3.287.811V2.828zm7.5-.141c.654-.689 1.782-.886 3.112-.752 1.234.124 2.503.523 3.388.893v9.923c-.918-.35-2.107-.692-3.287-.81-1.094-.111-2.278-.039-3.213.492V2.687zM8 1.783C7.015.936 5.587.81 4.287.94c-1.514.153-3.042.672-3.994 1.105A.5.5 0 000 2.5v11a.5.5 0 00.707.455c.882-.4 2.303-.881 3.68-1.02 1.409-.142 2.59.087 3.223.877a.5.5 0 00.78 0c.633-.79 1.814-1.019 3.222-.877 1.378.139 2.8.62 3.681 1.02A.5.5 0 0016 13.5v-11a.5.5 0 00-.293-.455c-.952-.433-2.48-.952-3.994-1.105C10.413.809 8.985.936 8 1.783z" />
      </svg>
      {docName.length > 20 ? docName.slice(0, 20) + '...' : docName}
      <span className="opacity-60">#{chunkIndex}</span>
    </button>
  );
}

// ============================================================================
// Welcome Message
// ============================================================================

function WelcomeMessage({ onQuickAction }: { onQuickAction: (msg: string) => void }) {
  const { state } = useAuraBot();
  const pc = state.pageContext;

  return (
    <div className="flex flex-1 flex-col items-center justify-center p-6 text-center">
      <div className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500 to-indigo-500">
        <span className="text-2xl text-white">&#10022;</span>
      </div>
      <h3 className="mb-2 text-lg font-semibold text-gray-800 dark:text-gray-200">AuraBot</h3>
      {pc?.modelCode ? (
        <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
          {'当前查看: '}
          <span className="font-medium text-blue-600 dark:text-blue-400">
            {pc.modelCode.replace(/_/g, ' ')}
          </span>
          {pc.pageType === 'detail' && ' 详情'}
          {pc.pageType === 'list' && ' 列表'}
          {pc.pageType === 'form' && ' 表单'}
          {pc.pageType === 'dashboard' && ' 仪表盘'}
          <br />
          {'可以问我关于这条数据的任何问题'}
        </p>
      ) : (
        <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
          {'我是你的 AI 助手，可以帮你分析数据、生成内容、执行操作。'}
        </p>
      )}
      <div className="flex flex-wrap justify-center gap-2">
        <button
          onClick={() => onQuickAction('你能帮我做什么？')}
          className="rounded-full bg-gray-100 px-3 py-1.5 text-xs text-gray-600 transition-colors hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
        >
          {'💡 你能帮我做什么？'}
        </button>
        {pc?.modelCode && (
          <button
            onClick={() => onQuickAction(`总结当前 ${pc.modelCode} 的关键信息`)}
            className="rounded-full bg-blue-50 px-3 py-1.5 text-xs text-blue-600 transition-colors hover:bg-blue-100 dark:bg-blue-900/20 dark:text-blue-300 dark:hover:bg-blue-900/40"
          >
            {'📊 总结当前数据'}
          </button>
        )}
      </div>
    </div>
  );
}

// ============================================================================
// Knowledge Base Selector
// ============================================================================

function KnowledgeBaseSelector() {
  const { state, toggleKnowledgeBase } = useAuraBot();
  const [open, setOpen] = useState(false);
  const popoverRef = useRef<HTMLDivElement>(null);

  const { knowledgeBases, selectedKnowledgeBaseIds } = state;

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  if (knowledgeBases.length === 0) return null;

  const selectedCount = selectedKnowledgeBaseIds.length;

  return (
    <div ref={popoverRef} className="relative">
      <button
        onClick={() => setOpen(!open)}
        className={`flex items-center gap-1 rounded-lg p-2 text-sm transition-colors ${
          selectedCount > 0
            ? 'bg-blue-50 text-blue-600 hover:bg-blue-100 dark:bg-blue-900/20 dark:text-blue-400'
            : 'text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700'
        }`}
        title="Knowledge Bases"
        data-testid="kb-selector-trigger"
      >
        <BookIcon className="h-4.5 w-4.5" />
        {selectedCount > 0 && (
          <span className="min-w-[16px] rounded-full bg-blue-500 px-1 text-center text-[10px] leading-4 font-bold text-white">
            {selectedCount}
          </span>
        )}
      </button>

      {open && (
        <div
          className="absolute bottom-full left-0 z-50 mb-2 w-56 rounded-lg border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-600 dark:bg-gray-800"
          data-testid="kb-selector-dropdown"
        >
          <div className="border-b border-gray-100 px-3 py-2 dark:border-gray-700">
            <p className="text-xs font-semibold text-gray-500 uppercase dark:text-gray-400">
              Knowledge Bases
            </p>
          </div>
          <div className="max-h-48 overflow-y-auto py-1">
            {knowledgeBases.map((kb) => {
              const isSelected = selectedKnowledgeBaseIds.includes(kb.pid);
              return (
                <button
                  key={kb.pid}
                  onClick={() => toggleKnowledgeBase(kb.pid)}
                  className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-gray-50 dark:hover:bg-gray-700 ${
                    isSelected
                      ? 'bg-blue-50 text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                      : 'text-gray-700 dark:text-gray-300'
                  }`}
                  data-testid={`kb-option-${kb.pid}`}
                >
                  <span
                    className={`flex h-4 w-4 flex-shrink-0 items-center justify-center rounded border ${
                      isSelected
                        ? 'border-blue-500 bg-blue-500'
                        : 'border-gray-300 dark:border-gray-500'
                    }`}
                  >
                    {isSelected && (
                      <svg className="h-3 w-3 text-white" viewBox="0 0 20 20" fill="currentColor">
                        <path
                          fillRule="evenodd"
                          d="M16.704 4.153a.75.75 0 01.143 1.052l-8 10.5a.75.75 0 01-1.127.075l-4.5-4.5a.75.75 0 011.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 011.05-.143z"
                          clipRule="evenodd"
                        />
                      </svg>
                    )}
                  </span>
                  <span className="flex-1 truncate">{kb.name}</span>
                  <span className="text-[10px] text-gray-400">{kb.docCount} docs</span>
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

function SelectedKbChips() {
  const { state, toggleKnowledgeBase } = useAuraBot();
  const { knowledgeBases, selectedKnowledgeBaseIds } = state;

  if (selectedKnowledgeBaseIds.length === 0) return null;

  const selected = knowledgeBases.filter((kb) => selectedKnowledgeBaseIds.includes(kb.pid));
  if (selected.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-1 px-4 pt-2 pb-1" data-testid="kb-selected-chips">
      {selected.map((kb) => (
        <span
          key={kb.pid}
          className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-[11px] text-blue-700 dark:bg-blue-900/20 dark:text-blue-300"
        >
          <BookIcon className="h-3 w-3" />
          <span className="max-w-[100px] truncate">{kb.name}</span>
          <button
            onClick={() => toggleKnowledgeBase(kb.pid)}
            className="ml-0.5 rounded-full p-0.5 transition-colors hover:bg-blue-200 dark:hover:bg-blue-800"
            title="Remove"
          >
            <svg className="h-2.5 w-2.5" viewBox="0 0 20 20" fill="currentColor">
              <path d="M6.28 5.22a.75.75 0 00-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 101.06 1.06L10 11.06l3.72 3.72a.75.75 0 101.06-1.06L11.06 10l3.72-3.72a.75.75 0 00-1.06-1.06L10 8.94 6.28 5.22z" />
            </svg>
          </button>
        </span>
      ))}
    </div>
  );
}

// ============================================================================
// Main Component
// ============================================================================

// ============================================================================
// Image Attachment helpers (P1 — Vision)
// ============================================================================

/**
 * MIME types accepted by the AuraBotChat paperclip — Anthropic supports
 * these four formats on Messages API image blocks.
 */
const ACCEPTED_IMAGE_MIME_TYPES = 'image/jpeg,image/png,image/gif,image/webp';

/** Hard cap (bytes) on a single image attachment. Anthropic rejects > 5MB
 *  per image; we apply a slightly tighter limit so base64-bloated payloads
 *  still fit within the request body cap. */
const MAX_IMAGE_BYTES = 4 * 1024 * 1024; // 4 MiB

/**
 * Strip the {@code data:image/png;base64,} prefix that {@link FileReader}
 * adds, leaving raw base64 bytes ready to ship to Anthropic.
 */
function stripDataUriPrefix(dataUri: string): string {
  const idx = dataUri.indexOf(',');
  return idx >= 0 ? dataUri.slice(idx + 1) : dataUri;
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === 'string' ? reader.result : '';
      resolve(stripDataUriPrefix(result));
    };
    reader.onerror = () => reject(reader.error || new Error('FileReader failed'));
    reader.readAsDataURL(file);
  });
}

export function AuraBotChat() {
  const { state, sendMessage, setInputValue, confirmTool, cancelTool } = useAuraBot();
  const { t } = useI18n();
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // P1 — vision: pending image attachments staged for the next send. Cleared
  // on send (success path) or via the per-chip remove button.
  const [attachments, setAttachments] = useState<ChatImageAttachment[]>([]);
  const [attachmentError, setAttachmentError] = useState<string | null>(null);

  const { messages, inputValue, isLoading } = state;

  // Auto-scroll to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Focus input on mount
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Handle input change
  const handleInputChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      setInputValue(e.target.value);
    },
    [setInputValue],
  );

  // P1 — vision: pop the OS file picker and stage selected images locally.
  const handleAttachClick = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleFilesSelected = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const files = Array.from(e.target.files ?? []);
      // Reset the input so picking the same file twice in a row still fires.
      if (e.target) e.target.value = '';
      if (files.length === 0) return;

      setAttachmentError(null);
      const next: ChatImageAttachment[] = [];
      for (const f of files) {
        if (f.size > MAX_IMAGE_BYTES) {
          setAttachmentError(`图片 ${f.name} 超过 4MB 限制`);
          continue;
        }
        if (!ACCEPTED_IMAGE_MIME_TYPES.split(',').includes(f.type)) {
          setAttachmentError(`图片 ${f.name} 格式不支持，仅支持 JPEG/PNG/GIF/WEBP`);
          continue;
        }
        try {
          const data = await readFileAsBase64(f);
          next.push({ mediaType: f.type, data, name: f.name });
        } catch (err) {
          setAttachmentError(`读取图片 ${f.name} 失败`);
        }
      }
      if (next.length > 0) {
        setAttachments((prev) => [...prev, ...next]);
      }
    },
    [],
  );

  const handleRemoveAttachment = useCallback((idx: number) => {
    setAttachments((prev) => prev.filter((_, i) => i !== idx));
  }, []);

  /**
   * Dispatch the message + (optional) attachments. Centralises the send
   * codepath so Cmd+Enter / Enter / send-button all clear the staged image
   * preview after a successful send.
   */
  const dispatchSend = useCallback(
    (text: string) => {
      const hasAttachments = attachments.length > 0;
      if (!hasAttachments && !text.trim()) return;
      sendMessage(text, hasAttachments ? attachments : undefined);
      // Clear staged attachments — successful send takes ownership.
      if (hasAttachments) setAttachments([]);
    },
    [sendMessage, attachments],
  );

  // Handle key press
  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      // Ignore Enter while IME composition is active (Chinese/Japanese/Korean input)
      if (e.nativeEvent.isComposing || e.keyCode === 229) {
        return;
      }

      // Cmd/Ctrl + Enter to send
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        e.preventDefault();
        dispatchSend(e.currentTarget.value);
      }

      // Enter without shift to send (if not empty OR attachments staged)
      if (e.key === 'Enter' && !e.shiftKey && (e.currentTarget.value.trim() || attachments.length > 0)) {
        e.preventDefault();
        dispatchSend(e.currentTarget.value);
      }
    },
    [dispatchSend, attachments.length],
  );

  // Handle send button click
  const handleSend = useCallback(() => {
    dispatchSend(inputValue);
  }, [dispatchSend, inputValue]);

  const hasMessages = messages.length > 0;

  return (
    <div className="flex h-full flex-col">
      {/* Messages Area */}
      <div className="flex-1 overflow-y-auto">
        {hasMessages ? (
          <div className="px-4 py-4">
            {messages.map((message) => (
              <MessageBubble
                key={message.id}
                message={message}
                onConfirm={confirmTool}
                onCancel={cancelTool}
                isLoading={isLoading}
              />
            ))}

            {/* Loading indicator */}
            {isLoading && (
              <div className="mb-3 flex justify-start">
                <div className="rounded-2xl rounded-bl-md bg-gray-100 px-4 py-2.5">
                  <LoadingDots />
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>
        ) : (
          <WelcomeMessage onQuickAction={sendMessage} />
        )}
      </div>

      {/* Input Area */}
      <div className="border-t border-gray-200 bg-gray-50">
        {/* Selected KB chips */}
        <SelectedKbChips />

        {/* P1: staged image attachments preview */}
        {attachments.length > 0 && (
          <div
            className="flex flex-wrap gap-2 px-4 pt-2 pb-1"
            data-testid="aurabot-attachments-preview"
          >
            {attachments.map((att, idx) => (
              <div
                key={idx}
                className="group relative inline-flex items-center gap-1 rounded-lg border border-blue-200 bg-blue-50 px-2 py-1 text-[11px] text-blue-700 dark:border-blue-800 dark:bg-blue-900/20 dark:text-blue-300"
                data-testid={`aurabot-attachment-${idx}`}
              >
                <img
                  src={`data:${att.mediaType};base64,${att.data}`}
                  alt={att.name || `attachment-${idx}`}
                  className="h-8 w-8 rounded object-cover"
                />
                <span className="max-w-[120px] truncate">{att.name || `image-${idx + 1}`}</span>
                <button
                  type="button"
                  onClick={() => handleRemoveAttachment(idx)}
                  className="ml-1 rounded-full p-0.5 transition-colors hover:bg-blue-200 dark:hover:bg-blue-800"
                  title="Remove"
                  data-testid={`aurabot-attachment-remove-${idx}`}
                >
                  <svg className="h-3 w-3" viewBox="0 0 20 20" fill="currentColor">
                    <path d="M6.28 5.22a.75.75 0 00-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 101.06 1.06L10 11.06l3.72 3.72a.75.75 0 101.06-1.06L11.06 10l3.72-3.72a.75.75 0 00-1.06-1.06L10 8.94 6.28 5.22z" />
                  </svg>
                </button>
              </div>
            ))}
          </div>
        )}

        {/* Attachment validation error */}
        {attachmentError && (
          <div
            className="px-4 pt-1 text-[11px] text-red-600 dark:text-red-400"
            data-testid="aurabot-attachment-error"
          >
            {attachmentError}
          </div>
        )}

        <div className="px-4 py-3">
          <div className="flex items-end gap-2">
            {/* KB selector */}
            <KnowledgeBaseSelector />

            {/* P1: image attachment trigger */}
            <button
              type="button"
              onClick={handleAttachClick}
              disabled={isLoading}
              className="flex-shrink-0 rounded-lg p-2 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 disabled:cursor-not-allowed disabled:opacity-50 dark:hover:bg-gray-700"
              title="Attach image"
              data-testid="aurabot-attach-image"
            >
              <PaperclipIcon className="h-4.5 w-4.5" />
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept={ACCEPTED_IMAGE_MIME_TYPES}
              multiple
              onChange={handleFilesSelected}
              className="hidden"
              data-testid="aurabot-file-input"
            />

            {/* Input */}
            <div className="relative flex-1">
              <textarea
                ref={inputRef}
                value={inputValue}
                onChange={handleInputChange}
                onKeyDown={handleKeyDown}
                placeholder="Type a message... (Enter to send)"
                rows={1}
                className="w-full resize-none rounded-xl border border-gray-300 bg-white px-4 py-2.5 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none"
                style={{
                  minHeight: '44px',
                  maxHeight: '120px',
                }}
                disabled={isLoading}
              />
            </div>

            {/* Send button — enabled when text OR attachments are present */}
            <button
              onClick={handleSend}
              data-testid="aurabot-send"
              disabled={(!inputValue.trim() && attachments.length === 0) || isLoading}
              className={`flex-shrink-0 rounded-xl p-2.5 transition-colors ${
                (inputValue.trim() || attachments.length > 0) && !isLoading
                  ? 'bg-blue-600 text-white hover:bg-blue-700'
                  : 'cursor-not-allowed bg-gray-200 text-gray-400'
              } `}
              title={t('aurabot.chat.send', undefined, '发送 (Cmd+Enter)')}
            >
              <SendIcon className="h-5 w-5" />
            </button>
          </div>

          {/* Keyboard hint */}
          <p className="mt-2 text-center text-xs text-gray-400">
            Cmd+J open | Enter send | Shift+Enter newline
          </p>
        </div>
      </div>
    </div>
  );
}

export default AuraBotChat;
