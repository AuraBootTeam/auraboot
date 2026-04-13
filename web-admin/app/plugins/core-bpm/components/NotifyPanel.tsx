/**
 * Notification panel for a single notify type (CC or URGE).
 *
 * Bug-fix: loadRecords no longer depends on showErrorToast (which
 * gets a new reference every render), breaking the infinite
 * useEffect → error → re-render → useEffect loop.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { Button } from '~/components/ui/button';
import { RefreshCw, Mail, BellRing, AlertCircle } from 'lucide-react';
import { useToastContext } from '~/contexts/ToastContext';
import {
  getReceivedNotifications,
  markAsRead,
  type NotifyRecord,
} from '../services/bpmNotifyService';

// ==================== Helper ====================

function formatDate(dateString?: string): string {
  if (!dateString) return '-';
  try {
    return new Date(dateString).toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return dateString;
  }
}

// ==================== Component ====================

interface NotifyPanelProps {
  userId: number;
  type: 'CC' | 'urge';
}

export function NotifyPanel({ userId, type }: NotifyPanelProps) {
  const { showErrorToast } = useToastContext();
  const showErrorRef = useRef(showErrorToast);
  showErrorRef.current = showErrorToast;

  const [records, setRecords] = useState<NotifyRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadRecords = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getReceivedNotifications(userId, type);
      setRecords(data);
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Unknown error';
      console.error('Failed to load notifications:', msg);
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, [userId, type]);

  useEffect(() => {
    loadRecords();
  }, [loadRecords]);

  const handleMarkRead = async (pid: string) => {
    try {
      await markAsRead(pid);
      setRecords((prev) => prev.map((r) => (r.pid === pid ? { ...r, isRead: true } : r)));
    } catch {
      showErrorRef.current('标记已读失败');
    }
  };

  const Icon = type === 'CC' ? Mail : BellRing;

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12 text-gray-500">
        <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
        加载中...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-gray-400">
        <AlertCircle className="mb-3 h-10 w-10 text-amber-400 opacity-40" />
        <p className="mb-3 text-sm text-gray-500">加载失败</p>
        <Button variant="outline" size="sm" onClick={loadRecords}>
          重试
        </Button>
      </div>
    );
  }

  if (records.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-gray-400">
        <Icon className="mb-3 h-10 w-10 opacity-30" />
        <p className="text-sm">暂无消息</p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {records.map((record) => (
        <div
          key={record.pid}
          className={`rounded-lg border p-3 ${
            record.isRead ? 'border-gray-200 bg-white' : 'border-blue-200 bg-blue-50'
          }`}
        >
          <div className="flex items-start justify-between">
            <div className="flex-1">
              <p className="text-sm text-gray-800">{record.content || '无内容'}</p>
              <p className="mt-1 text-xs text-gray-500">
                任务: {record.taskId} | {formatDate(record.createdAt)}
              </p>
            </div>
            {!record.isRead && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleMarkRead(record.pid)}
                className="ml-2 text-xs text-blue-600 hover:text-blue-800"
              >
                标记已读
              </Button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
