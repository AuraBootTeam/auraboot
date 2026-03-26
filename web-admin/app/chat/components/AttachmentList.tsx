/**
 * 附件列表组件
 * 显示当前会话的所有附件
 */

import { useState, useEffect } from 'react';
import { AttachmentCard } from '~/chat/components/AttachmentCard';
import {
  type TemporaryAttachment,
  getSessionAttachments,
  deleteAttachment,
} from '~/chat/services/fileService';
import { useToastContext } from '~/contexts/ToastContext';

interface AttachmentListProps {
  sessionId: string;
  onAttachmentClick?: (attachment: TemporaryAttachment) => void;
  refreshTrigger?: number; // 用于触发刷新
}

export function AttachmentList({
  sessionId,
  onAttachmentClick,
  refreshTrigger,
}: AttachmentListProps) {
  const { showErrorToast } = useToastContext();
  const [attachments, setAttachments] = useState<TemporaryAttachment[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 加载附件列表
  const loadAttachments = async () => {
    setIsLoading(true);
    setError(null);

    try {
      const data = await getSessionAttachments(sessionId);
      setAttachments(data);
    } catch (err: any) {
      console.error('Failed to load attachments:', err);
      setError(err.message || '加载失败');
    } finally {
      setIsLoading(false);
    }
  };

  // 初始加载和刷新
  useEffect(() => {
    loadAttachments();
  }, [sessionId, refreshTrigger]);

  // 轮询处理中的附件
  useEffect(() => {
    const processingAttachments = attachments.filter(
      (a) => a.status === 'processing' || a.status === 'pending',
    );

    if (processingAttachments.length === 0) {
      return;
    }

    // 每 3 秒轮询一次
    const interval = setInterval(() => {
      loadAttachments();
    }, 3000);

    return () => clearInterval(interval);
  }, [attachments]);

  // 删除附件
  const handleDelete = async (attachmentId: number) => {
    try {
      await deleteAttachment(attachmentId);
      setAttachments((prev) => prev.filter((a) => a.attachment_id !== attachmentId));
    } catch (err: any) {
      console.error('Failed to delete attachment:', err);
      showErrorToast(err.message || '删除失败');
    }
  };

  if (isLoading && attachments.length === 0) {
    return (
      <div className="flex items-center justify-center py-8 text-gray-500">
        <div className="mr-2 h-5 w-5 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
        加载中...
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-800">
        <div className="flex items-center space-x-2">
          <svg className="h-5 w-5 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
              clipRule="evenodd"
            />
          </svg>
          <span>{error}</span>
        </div>
        <button onClick={loadAttachments} className="mt-2 text-sm underline hover:no-underline">
          重试
        </button>
      </div>
    );
  }

  if (attachments.length === 0) {
    return (
      <div className="py-8 text-center text-sm text-gray-500">
        <div className="mb-2 text-3xl">📎</div>
        <div>暂无上传文件</div>
        <div className="mt-1 text-xs">点击下方按钮或拖拽文件到此处上传</div>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="mb-2 flex items-center justify-between">
        <div className="text-sm font-medium text-gray-700">已上传文件 ({attachments.length})</div>
        <button
          onClick={loadAttachments}
          className="text-xs text-gray-500 transition-colors hover:text-gray-700"
          title="刷新"
        >
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
            />
          </svg>
        </button>
      </div>

      {attachments.map((attachment) => (
        <AttachmentCard
          key={attachment.attachment_id}
          attachment={attachment}
          onDelete={handleDelete}
          onClick={onAttachmentClick}
        />
      ))}
    </div>
  );
}
