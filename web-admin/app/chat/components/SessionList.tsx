/**
 * 会话列表组件
 * 显示用户的历史会话
 */

import { useState, useEffect } from 'react';
import {
  type SessionInfo,
  getSessionList,
  formatTimeAgo,
  deleteSession,
} from '~/chat/services/sessionService';
import { useToastContext } from '~/contexts/ToastContext';

interface SessionListProps {
  currentSessionId?: string;
  onSessionSelect: (sessionId: string) => void;
  onNewSession: () => void;
}

export function SessionList({ currentSessionId, onSessionSelect, onNewSession }: SessionListProps) {
  const { showErrorToast } = useToastContext();
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadSessions = async () => {
    try {
      setLoading(true);
      setError(null);
      const result = await getSessionList({ limit: 50, offset: 0 });
      setSessions(result.sessions);
    } catch (err: any) {
      console.error('Failed to load sessions:', err);
      setError(err.message || '加载会话列表失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadSessions();
  }, []);

  const handleDelete = async (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation();

    if (!confirm('确定要删除这个会话吗？')) {
      return;
    }

    try {
      await deleteSession(sessionId);
      setSessions((prev) => prev.filter((s) => s.session_id !== sessionId));

      // 如果删除的是当前会话，创建新会话
      if (sessionId === currentSessionId) {
        onNewSession();
      }
    } catch (err: any) {
      console.error('Failed to delete session:', err);
      showErrorToast('删除会话失败：' + (err.message || '未知错误'));
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-8">
        <div className="h-6 w-6 animate-spin rounded-full border-b-2 border-blue-600"></div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4">
        <div className="text-sm text-red-700">{error}</div>
        <button
          onClick={loadSessions}
          className="mt-2 text-sm text-red-600 underline hover:text-red-700"
        >
          重试
        </button>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      {/* 新建会话按钮 */}
      <div className="border-b p-4">
        <button
          onClick={onNewSession}
          className="flex w-full items-center justify-center space-x-2 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
        >
          <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
          </svg>
          <span>新建对话</span>
        </button>
      </div>

      {/* 会话列表 */}
      <div className="flex-1 overflow-y-auto">
        {sessions.length === 0 ? (
          <div className="py-8 text-center text-gray-500">
            <div className="mb-2 text-4xl">💬</div>
            <div>暂无历史会话</div>
          </div>
        ) : (
          <div className="space-y-1 p-2">
            {sessions.map((session) => (
              <SessionCard
                key={session.session_id}
                session={session}
                isActive={session.session_id === currentSessionId}
                onClick={() => onSessionSelect(session.session_id)}
                onDelete={(e) => handleDelete(session.session_id, e)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

interface SessionCardProps {
  session: SessionInfo;
  isActive: boolean;
  onClick: () => void;
  onDelete: (e: React.MouseEvent) => void;
}

function SessionCard({ session, isActive, onClick, onDelete }: SessionCardProps) {
  const [showDelete, setShowDelete] = useState(false);

  return (
    <div
      onClick={onClick}
      onMouseEnter={() => setShowDelete(true)}
      onMouseLeave={() => setShowDelete(false)}
      className={`cursor-pointer rounded-lg p-3 transition-all ${isActive ? 'border-blue-200 bg-blue-50' : 'hover:bg-gray-50'} border`}
    >
      <div className="flex items-start justify-between">
        <div className="min-w-0 flex-1">
          {/* 会话摘要或最后一条消息 */}
          <div className="truncate font-medium text-gray-900">
            {session.summary || session.last_message || '新对话'}
          </div>

          {/* 时间和消息数 */}
          <div className="mt-1 flex items-center space-x-2 text-xs text-gray-500">
            <span>{formatTimeAgo(session.last_updated)}</span>
            <span>•</span>
            <span>{session.message_count} 条消息</span>
          </div>
        </div>

        {/* 删除按钮 */}
        {showDelete && (
          <button
            onClick={onDelete}
            className="ml-2 p-1 text-gray-400 transition-colors hover:text-red-600"
            title="删除会话"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
              />
            </svg>
          </button>
        )}
      </div>
    </div>
  );
}
