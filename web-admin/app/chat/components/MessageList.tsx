/**
 * 消息列表组件
 */

import { useEffect, useRef } from 'react';
import type { ChatMessage, Citation } from '~/chat/types';
import { CitationPanel } from '~/chat/components/CitationPanel';
import { ToolStatusIndicator } from '~/chat/components/ToolStatusIndicator';

interface MessageListProps {
  messages: ChatMessage[];
  isLoading?: boolean;
}

export function MessageList({ messages, isLoading }: MessageListProps) {
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const previousMessageCountRef = useRef(messages.length);

  useEffect(() => {
    const isNewMessage = messages.length > previousMessageCountRef.current;
    messagesEndRef.current?.scrollIntoView({
      behavior: isNewMessage ? 'smooth' : 'auto',
      block: 'end',
    });
    previousMessageCountRef.current = messages.length;
  }, [messages]);

  return (
    <div className="flex-1 space-y-4 overflow-y-auto p-4">
      {messages.length === 0 && !isLoading && (
        <div className="flex h-full items-center justify-center text-gray-500">
          <div className="text-center">
            <p className="mb-2 text-lg">👋 你好！</p>
            <p>我是金融 AI 研究助手，有什么可以帮你的吗？</p>
          </div>
        </div>
      )}

      {messages.map((message) => (
        <MessageBubble key={message.id} message={message} />
      ))}

      {isLoading && (
        <div className="flex items-center space-x-2 text-gray-500">
          <div className="animate-pulse">●</div>
          <div className="animation-delay-200 animate-pulse">●</div>
          <div className="animation-delay-400 animate-pulse">●</div>
        </div>
      )}

      <div ref={messagesEndRef} />
    </div>
  );
}

interface MessageBubbleProps {
  message: ChatMessage;
}

function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user';

  const handleCitationClick = (citation: Citation) => {
    if (citation.pdf_url) {
      window.open(citation.pdf_url, '_blank');
    }
  };

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div
        className={`max-w-[70%] rounded-lg px-4 py-2 ${
          isUser
            ? 'bg-blue-500 text-white'
            : message.error
              ? 'border border-red-300 bg-red-100 text-red-800'
              : 'bg-gray-100 text-gray-900'
        }`}
      >
        {/* 推理过程（如果有） */}
        {message.thinkingProcess && (
          <details className="mb-2 text-sm opacity-75">
            <summary className="cursor-pointer hover:opacity-100">💭 推理过程</summary>
            <div className="mt-2 border-l-2 border-current pl-4 whitespace-pre-wrap">
              {message.thinkingProcess}
            </div>
          </details>
        )}

        {/* 工具调用状态（如果有） */}
        {!isUser && message.toolStatuses && message.toolStatuses.length > 0 && (
          <div className="mb-2">
            <ToolStatusIndicator toolStatuses={message.toolStatuses} />
          </div>
        )}

        {/* 消息内容 */}
        <div className="whitespace-pre-wrap">{message.content}</div>

        {/* 引用面板 */}
        {!isUser && message.citations && message.citations.length > 0 && (
          <CitationPanel citations={message.citations} onCitationClick={handleCitationClick} />
        )}

        {/* 错误信息 */}
        {message.error && <div className="mt-2 text-sm text-red-600">⚠️ {message.error}</div>}

        {/* 时间戳 */}
        <div className={`mt-1 text-xs ${isUser ? 'text-blue-100' : 'text-gray-500'}`}>
          {message.timestamp.toLocaleTimeString()}
        </div>
      </div>
    </div>
  );
}
