/**
 * Chat 主页面
 */

import { useState, useCallback, useEffect } from 'react';
import { useLoaderData } from 'react-router';
import type { Route } from '~/chat/+types/ChatPage';
import { MessageList } from '~/chat/components/MessageList';
import { ChatInput } from '~/chat/components/ChatInput';
import { ErrorDisplay, type ErrorType } from '~/chat/components/ErrorDisplay';
import { FileUploadButton } from '~/chat/components/ChatFileUploadButton';
import { AttachmentList } from '~/chat/components/AttachmentList';
import { SessionList } from '~/chat/components/SessionList';
import type { ChatMessage, ToolStatus } from '~/chat/types';
import type { TemporaryAttachment } from '~/chat/services/fileService';
import { streamChat } from '~/chat/services/chatService';
import { getSessionHistory } from '~/chat/services/sessionService';
import { getTokenFromRequest } from '~/services/session';

/**
 * Loader: 在服务器端获取 token
 */
export async function loader({ request }: Route.LoaderArgs) {
  const token = await getTokenFromRequest(request);
  return { token };
}

export default function ChatPage() {
  const { token } = useLoaderData<typeof loader>();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<{ type: ErrorType; message?: string } | null>(null);
  const [sessionId, setSessionId] = useState<string>(() => {
    // 生成或获取 session ID
    return `session-${Date.now()}`;
  });
  const [refreshAttachments, setRefreshAttachments] = useState(0);
  const [showAttachments, setShowAttachments] = useState(false);
  const [showSessions, setShowSessions] = useState(false);

  // 加载会话历史
  const loadHistory = useCallback(async () => {
    try {
      const history = await getSessionHistory(sessionId);
      setMessages(history);
    } catch (err) {
      console.error('Failed to load history:', err);
      // 历史加载失败不影响新对话
    }
  }, [sessionId]);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const handleSendMessage = useCallback(
    async (content: string) => {
      // 清除之前的错误
      setError(null);

      // 添加用户消息
      const userMessage: ChatMessage = {
        id: `user-${Date.now()}`,
        role: 'user',
        content,
        timestamp: new Date(),
      };
      setMessages((prev) => [...prev, userMessage]);

      // 开始加载
      setIsLoading(true);

      // 创建助手消息（用于流式更新）
      const assistantMessageId = `assistant-${Date.now()}`;
      let assistantContent = '';
      let thinkingProcess = '';
      let citations: any[] = [];
      let toolStatuses: ToolStatus[] = [];
      let hasError = false;

      try {
        // 流式接收响应，传递 token
        for await (const event of streamChat(
          {
            sessionId,
            message: content,
            stream: true,
          },
          token,
        )) {
          if (event.type === 'text_delta') {
            // 文本增量
            assistantContent += event.data.content || '';
            updateAssistantMessage(
              assistantMessageId,
              assistantContent,
              thinkingProcess,
              citations,
              toolStatuses,
            );
          } else if (event.type === 'reasoning_delta') {
            // 推理过程增量
            thinkingProcess += event.data.content || '';
            updateAssistantMessage(
              assistantMessageId,
              assistantContent,
              thinkingProcess,
              citations,
              toolStatuses,
            );
          } else if (event.type === 'citation') {
            // 引用信息
            if (event.data.citations) {
              citations = event.data.citations;
              updateAssistantMessage(
                assistantMessageId,
                assistantContent,
                thinkingProcess,
                citations,
                toolStatuses,
              );
            }
          } else if (event.type === 'tool_status') {
            // 工具调用状态
            if (event.data.tool_name) {
              const existingIndex = toolStatuses.findIndex(
                (t) => t.tool_name === event.data.tool_name,
              );

              const newStatus: ToolStatus = {
                tool_name: event.data.tool_name,
                status:
                  event.data.status === 'success'
                    ? 'completed'
                    : event.data.status === 'error'
                      ? 'failed'
                      : event.data.status === 'pending'
                        ? 'running'
                        : event.data.status || 'running',
                message: event.data.message || '',
                result: event.data.result,
                error: event.data.error,
              };

              if (existingIndex >= 0) {
                toolStatuses[existingIndex] = newStatus;
              } else {
                toolStatuses.push(newStatus);
              }

              updateAssistantMessage(
                assistantMessageId,
                assistantContent,
                thinkingProcess,
                citations,
                toolStatuses,
              );
            }
          } else if (event.type === 'error') {
            // 错误
            hasError = true;
            const errorMessage = event.data.message || '发生错误';
            updateAssistantMessage(
              assistantMessageId,
              assistantContent,
              thinkingProcess,
              citations,
              toolStatuses,
              errorMessage,
            );
            setError({ type: 'server', message: errorMessage });
          } else if (event.type === 'done') {
            // 完成
            break;
          }
        }

        // 如果没有收到任何内容且没有错误，显示错误
        if (!assistantContent && !hasError) {
          throw new Error('No response received');
        }
      } catch (err: any) {
        console.error('Chat error:', err);

        // 判断错误类型
        let errorType: ErrorType = 'unknown';
        let errorMessage = err.message;

        if (err.name === 'AbortError' || errorMessage.includes('timeout')) {
          errorType = 'timeout';
        } else if (errorMessage.includes('fetch') || errorMessage.includes('network')) {
          errorType = 'network';
        } else if (errorMessage.includes('http')) {
          errorType = 'server';
        }

        setError({ type: errorType, message: errorMessage });

        // 更新助手消息显示错误
        updateAssistantMessage(
          assistantMessageId,
          assistantContent || '抱歉，我遇到了一些问题',
          thinkingProcess,
          citations,
          toolStatuses,
          errorMessage,
        );
      } finally {
        setIsLoading(false);
      }
    },
    [sessionId, token],
  );

  const updateAssistantMessage = (
    id: string,
    content: string,
    thinkingProcess?: string,
    citations?: any[],
    toolStatuses?: ToolStatus[],
    error?: string,
  ) => {
    setMessages((prev) => {
      const existing = prev.find((m) => m.id === id);
      if (existing) {
        // 更新现有消息
        return prev.map((m) =>
          m.id === id ? { ...m, content, thinkingProcess, citations, toolStatuses, error } : m,
        );
      } else {
        // 添加新消息
        return [
          ...prev,
          {
            id,
            role: 'assistant' as const,
            content,
            thinkingProcess,
            citations,
            toolStatuses,
            error,
            timestamp: new Date(),
          },
        ];
      }
    });
  };

  const handleRetry = () => {
    setError(null);
    // 重新发送最后一条用户消息
    const lastUserMessage = [...messages].reverse().find((m) => m.role === 'user');
    if (lastUserMessage) {
      handleSendMessage(lastUserMessage.content);
    }
  };

  const handleDismissError = () => {
    setError(null);
  };

  const handleUploadSuccess = (_attachment: TemporaryAttachment) => {
    setRefreshAttachments((prev) => prev + 1);
    setShowAttachments(true);
  };

  const handleUploadError = (errorMessage: string) => {
    setError({ type: 'server', message: errorMessage });
  };

  const handleAttachmentClick = (_attachment: TemporaryAttachment) => {
    // TODO: 实现文件详情查看
  };

  const handleSessionSelect = async (newSessionId: string) => {
    setSessionId(newSessionId);
    setMessages([]);
    setShowSessions(false);
    await loadHistory();
  };

  const handleNewSession = () => {
    const newSessionId = `session-${Date.now()}`;
    setSessionId(newSessionId);
    setMessages([]);
    setShowSessions(false);
  };

  return (
    <div className="flex h-screen bg-gray-50">
      {/* Session List Sidebar */}
      {showSessions && (
        <div className="flex w-80 flex-col border-r bg-white">
          <div className="flex items-center justify-between border-b px-4 py-4">
            <h2 className="text-lg font-semibold text-gray-900">历史会话</h2>
            <button
              onClick={() => setShowSessions(false)}
              className="text-gray-400 hover:text-gray-600"
            >
              ✕
            </button>
          </div>
          <SessionList
            currentSessionId={sessionId}
            onSessionSelect={handleSessionSelect}
            onNewSession={handleNewSession}
          />
        </div>
      )}

      {/* Main Chat Area */}
      <div className="flex flex-1 flex-col">
        {/* Header */}
        <div className="border-b bg-white px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-4">
              <button
                onClick={() => setShowSessions(!showSessions)}
                className="rounded-lg p-2 text-gray-600 transition-colors hover:bg-gray-100"
                title="会话列表"
              >
                <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M4 6h16M4 12h16M4 18h16"
                  />
                </svg>
              </button>
              <div>
                <h1 className="text-xl font-semibold text-gray-900">金融 AI 研究助手</h1>
                <p className="mt-1 text-sm text-gray-500">基于 LangGraph 的智能对话系统</p>
              </div>
            </div>
            <button
              onClick={() => setShowAttachments(!showAttachments)}
              className="flex items-center space-x-2 rounded-lg bg-gray-100 px-4 py-2 text-sm transition-colors hover:bg-gray-200"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"
                />
              </svg>
              <span>{showAttachments ? '隐藏' : '显示'}附件</span>
            </button>
          </div>
        </div>

        {/* Error Display */}
        {error && (
          <div className="px-6 pt-4">
            <ErrorDisplay
              type={error.type}
              message={error.message}
              onRetry={handleRetry}
              onDismiss={handleDismissError}
            />
          </div>
        )}

        {/* Messages */}
        <MessageList messages={messages} isLoading={isLoading} />

        {/* Input Area with File Upload */}
        <div className="border-t bg-white p-4">
          <div className="flex items-end space-x-2">
            <FileUploadButton
              sessionId={sessionId}
              onUploadSuccess={handleUploadSuccess}
              onUploadError={handleUploadError}
              disabled={isLoading}
            />
            <div className="flex-1">
              <ChatInput
                onSend={handleSendMessage}
                disabled={isLoading}
                placeholder="请输入您的问题..."
              />
            </div>
          </div>
        </div>
      </div>

      {/* Attachments Sidebar */}
      {showAttachments && (
        <div className="flex w-80 flex-col border-l bg-white">
          <div className="border-b px-4 py-4">
            <h2 className="text-lg font-semibold text-gray-900">上传文件</h2>
            <p className="mt-1 text-xs text-gray-500">支持 PDF、Excel、Word、图片、文本</p>
          </div>
          <div className="flex-1 overflow-y-auto p-4">
            <AttachmentList
              sessionId={sessionId}
              onAttachmentClick={handleAttachmentClick}
              refreshTrigger={refreshAttachments}
            />
          </div>
        </div>
      )}
    </div>
  );
}
