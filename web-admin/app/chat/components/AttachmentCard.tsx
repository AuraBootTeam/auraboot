/**
 * 附件卡片组件
 * 显示单个附件的信息和操作
 */

import { useState, useEffect } from 'react';
import {
  type TemporaryAttachment,
  formatFileSize,
  getFileTypeIcon,
  getFileTypeLabel,
} from '~/chat/services/fileService';

interface AttachmentCardProps {
  attachment: TemporaryAttachment;
  onDelete?: (id: number) => void;
  onClick?: (attachment: TemporaryAttachment) => void;
}

export function AttachmentCard({ attachment, onDelete, onClick }: AttachmentCardProps) {
  const [timeRemaining, setTimeRemaining] = useState<string>('');

  // 计算剩余时间
  useEffect(() => {
    const updateTimeRemaining = () => {
      const now = new Date();
      const expiresAt = new Date(attachment.expires_at);
      const diff = expiresAt.getTime() - now.getTime();

      if (diff <= 0) {
        setTimeRemaining('已过期');
        return;
      }

      const hours = Math.floor(diff / (1000 * 60 * 60));
      const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));

      if (hours > 0) {
        setTimeRemaining(`${hours}小时${minutes}分钟后过期`);
      } else {
        setTimeRemaining(`${minutes}分钟后过期`);
      }
    };

    updateTimeRemaining();
    const interval = setInterval(updateTimeRemaining, 60000); // 每分钟更新

    return () => clearInterval(interval);
  }, [attachment.expires_at]);

  const getStatusColor = (status: string) => {
    const colors: Record<string, string> = {
      pending: 'text-yellow-600 bg-yellow-50',
      processing: 'text-blue-600 bg-blue-50',
      completed: 'text-green-600 bg-green-50',
      failed: 'text-red-600 bg-red-50',
    };
    return colors[status] || 'text-gray-600 bg-gray-50';
  };

  const getStatusLabel = (status: string) => {
    const labels: Record<string, string> = {
      pending: '等待处理',
      processing: '处理中',
      completed: '已完成',
      failed: '失败',
    };
    return labels[status] || status;
  };

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (confirm(`确定要删除 "${attachment.file_name}" 吗？`)) {
      onDelete?.(attachment.attachment_id);
    }
  };

  const handleClick = () => {
    onClick?.(attachment);
  };

  return (
    <div
      className={`rounded-lg border border-gray-200 bg-white p-3 transition-colors hover:border-gray-300 ${
        onClick ? 'cursor-pointer' : ''
      }`}
      onClick={handleClick}
    >
      <div className="flex items-start space-x-3">
        {/* 文件图标 */}
        <div className="flex-shrink-0 text-2xl">{getFileTypeIcon(attachment.file_type)}</div>

        {/* 文件信息 */}
        <div className="min-w-0 flex-1">
          <div className="mb-1 flex items-center space-x-2">
            <span className="truncate font-medium text-gray-900">{attachment.file_name}</span>
            <span className="flex-shrink-0 rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
              {getFileTypeLabel(attachment.file_type)}
            </span>
          </div>

          <div className="flex items-center space-x-2 text-xs text-gray-500">
            <span>{formatFileSize(attachment.file_size)}</span>
            <span>•</span>
            <span className={`rounded px-2 py-0.5 ${getStatusColor(attachment.status)}`}>
              {getStatusLabel(attachment.status)}
            </span>
          </div>

          {/* 处理模式 */}
          {attachment.processing_mode && (
            <div className="mt-1 text-xs text-gray-500">
              {attachment.processing_mode === 'vectorized' ? '🔍 深度检索' : '⚡ 快速分析'}
            </div>
          )}

          {/* 过期时间 */}
          <div className="mt-1 text-xs text-gray-400">{timeRemaining}</div>
        </div>

        {/* 操作按钮 */}
        <div className="flex flex-shrink-0 items-center space-x-1">
          {/* 处理中动画 */}
          {attachment.status === 'processing' && (
            <div className="h-4 w-4 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          )}

          {/* 删除按钮 */}
          <button
            onClick={handleDelete}
            className="rounded p-1 text-gray-400 transition-colors hover:bg-red-50 hover:text-red-600"
            title="删除"
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
        </div>
      </div>
    </div>
  );
}
