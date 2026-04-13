/**
 * ApprovalCommentsBlock - DSL block that displays approval comments timeline
 * for a business record, with optional signature canvas and attachment upload.
 *
 * Block type: "approval-comments"
 * DSL config:
 * {
 *   "blockType": "approval-comments",
 *   "approvalComments": {
 *     "showSignature": true,
 *     "showAttachments": true,
 *     "showTimeline": true
 *   }
 * }
 *
 * Data source: GET /api/bpm/approval-tasks/trail/{businessKey}
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import {
  CheckCircle2,
  XCircle,
  Clock,
  Send,
  UserCircle,
  Paperclip,
  Download,
  PenLine,
  Trash2,
  RefreshCw,
  FileText,
  AlertCircle,
} from 'lucide-react';
import { cn } from '~/utils/cn';
import { get } from '~/services/http-client';

dayjs.extend(relativeTime);

// ==================== Types ====================

interface ApprovalCommentEntry {
  pid: string;
  taskTitle: string;
  taskDescription?: string;
  status: 'pending' | 'approved' | 'rejected' | 'expired' | 'cancelled';
  approvalComment?: string;
  approverName?: string;
  actualApproverId?: number;
  signature?: string;
  attachments?: AttachmentRef[];
  completedAt?: string;
  createdAt: string;
  priority?: string;
}

interface AttachmentRef {
  fileId: string;
  fileName: string;
  fileSize?: number;
  url?: string;
}

interface ApprovalCommentsConfig {
  showSignature?: boolean;
  showAttachments?: boolean;
  showTimeline?: boolean;
}

export interface ApprovalCommentsBlockProps {
  modelCode: string;
  recordPid: string;
  config?: ApprovalCommentsConfig;
  token?: string;
  locale?: string;
  t?: (key: string) => string;
}

// ==================== Status Config ====================

const STATUS_CONFIG: Record<
  string,
  {
    icon: typeof CheckCircle2;
    color: string;
    bgColor: string;
    borderColor: string;
    labelEn: string;
    labelZh: string;
  }
> = {
  approved: {
    icon: CheckCircle2,
    color: 'text-green-600',
    bgColor: 'bg-green-50',
    borderColor: 'border-green-200',
    labelEn: 'Approved',
    labelZh: 'Approved',
  },
  rejected: {
    icon: XCircle,
    color: 'text-red-600',
    bgColor: 'bg-red-50',
    borderColor: 'border-red-200',
    labelEn: 'Rejected',
    labelZh: 'Rejected',
  },
  pending: {
    icon: Clock,
    color: 'text-amber-600',
    bgColor: 'bg-amber-50',
    borderColor: 'border-amber-200',
    labelEn: 'Pending',
    labelZh: 'Pending',
  },
  expired: {
    icon: AlertCircle,
    color: 'text-gray-500',
    bgColor: 'bg-gray-50',
    borderColor: 'border-gray-200',
    labelEn: 'Expired',
    labelZh: 'Expired',
  },
  cancelled: {
    icon: XCircle,
    color: 'text-gray-500',
    bgColor: 'bg-gray-50',
    borderColor: 'border-gray-200',
    labelEn: 'Cancelled',
    labelZh: 'Cancelled',
  },
};

const DEFAULT_STATUS_CONFIG = {
  icon: Send,
  color: 'text-gray-500',
  bgColor: 'bg-gray-50',
  borderColor: 'border-gray-200',
  labelEn: 'Unknown',
  labelZh: 'Unknown',
};

// ==================== Helpers ====================

function formatFileSize(bytes?: number): string {
  if (!bytes) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatRelativeTime(dateStr: string, locale?: string): string {
  const d = dayjs(dateStr);
  const diffMinutes = dayjs().diff(d, 'minute');
  if (diffMinutes < 1) return locale === 'zh-CN' ? 'Just now' : 'Just now';
  if (diffMinutes < 60) return d.fromNow();
  if (diffMinutes < 1440) return d.fromNow();
  return d.format(locale === 'zh-CN' ? 'YYYY-MM-DD HH:mm' : 'MMM D, YYYY HH:mm');
}

// ==================== Signature Canvas ====================

interface SignatureCanvasProps {
  onSave: (dataUrl: string) => void;
  onClear: () => void;
  className?: string;
}

function SignatureCanvas({ onSave, onClear, className }: SignatureCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [isDrawing, setIsDrawing] = useState(false);
  const [hasContent, setHasContent] = useState(false);

  const getCoords = useCallback((e: React.MouseEvent | React.TouchEvent) => {
    const canvas = canvasRef.current;
    if (!canvas) return { x: 0, y: 0 };
    const rect = canvas.getBoundingClientRect();
    if ('touches' in e) {
      return {
        x: e.touches[0].clientX - rect.left,
        y: e.touches[0].clientY - rect.top,
      };
    }
    return {
      x: e.clientX - rect.left,
      y: e.clientY - rect.top,
    };
  }, []);

  const startDraw = useCallback(
    (e: React.MouseEvent | React.TouchEvent) => {
      e.preventDefault();
      const canvas = canvasRef.current;
      const ctx = canvas?.getContext('2d');
      if (!ctx) return;
      const { x, y } = getCoords(e);
      ctx.beginPath();
      ctx.moveTo(x, y);
      setIsDrawing(true);
    },
    [getCoords],
  );

  const draw = useCallback(
    (e: React.MouseEvent | React.TouchEvent) => {
      if (!isDrawing) return;
      e.preventDefault();
      const canvas = canvasRef.current;
      const ctx = canvas?.getContext('2d');
      if (!ctx) return;
      const { x, y } = getCoords(e);
      ctx.lineTo(x, y);
      ctx.strokeStyle = '#1a1a1a';
      ctx.lineWidth = 2;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.stroke();
      setHasContent(true);
    },
    [isDrawing, getCoords],
  );

  const endDraw = useCallback(() => {
    setIsDrawing(false);
  }, []);

  const handleClear = useCallback(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!ctx || !canvas) return;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    setHasContent(false);
    onClear();
  }, [onClear]);

  const handleSave = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || !hasContent) return;
    const dataUrl = canvas.toDataURL('image/png');
    onSave(dataUrl);
  }, [hasContent, onSave]);

  return (
    <div className={cn('space-y-2', className)}>
      <div className="rounded-lg border-2 border-dashed border-gray-300 bg-white">
        <canvas
          ref={canvasRef}
          width={400}
          height={150}
          className="w-full cursor-crosshair touch-none"
          onMouseDown={startDraw}
          onMouseMove={draw}
          onMouseUp={endDraw}
          onMouseLeave={endDraw}
          onTouchStart={startDraw}
          onTouchMove={draw}
          onTouchEnd={endDraw}
        />
      </div>
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={handleClear}
          className="inline-flex items-center gap-1 rounded-md bg-gray-100 px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-200"
        >
          <Trash2 className="h-3 w-3" />
          Clear
        </button>
        <button
          type="button"
          onClick={handleSave}
          disabled={!hasContent}
          className="inline-flex items-center gap-1 rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <PenLine className="h-3 w-3" />
          Confirm
        </button>
      </div>
    </div>
  );
}

// ==================== Main Component ====================

export function ApprovalCommentsBlock({
  modelCode,
  recordPid,
  config,
  token,
  locale,
  t: tProp,
}: ApprovalCommentsBlockProps) {
  const [entries, setEntries] = useState<ApprovalCommentEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const showSignature = config?.showSignature !== false;
  const showAttachments = config?.showAttachments !== false;
  const showTimeline = config?.showTimeline !== false;

  // Simple translation fallback
  const t = useCallback(
    (key: string) => {
      if (tProp) {
        const resolved = tProp(key);
        if (resolved && resolved !== key) return resolved;
      }
      // Built-in fallbacks for approval-specific keys
      const fallbacks: Record<string, string> = {
        'approval.comments.title': locale === 'zh-CN' ? 'Approval Comments' : 'Approval Comments',
        'approval.comments.noComments':
          locale === 'zh-CN' ? 'No approval comments yet' : 'No approval comments yet',
        'approval.comments.loading': locale === 'zh-CN' ? 'Loading...' : 'Loading...',
        'approval.comments.error': locale === 'zh-CN' ? 'Failed to load' : 'Failed to load',
        'approval.comments.signature': locale === 'zh-CN' ? 'Signature' : 'Signature',
        'approval.comments.attachments': locale === 'zh-CN' ? 'Attachments' : 'Attachments',
        'approval.status.APPROVED': 'Approved',
        'approval.status.REJECTED': 'Rejected',
        'approval.status.PENDING': 'Pending',
        'approval.status.EXPIRED': 'Expired',
        'approval.status.CANCELLED': 'Cancelled',
      };
      return fallbacks[key] || key;
    },
    [tProp, locale],
  );

  // Load approval trail
  useEffect(() => {
    if (!recordPid) {
      setLoading(false);
      return;
    }

    let cancelled = false;

    const loadComments = async () => {
      setLoading(true);
      setError(null);
      try {
        const result = await get<ApprovalCommentEntry[]>(
          `/api/bpm/approval-tasks/trail/${recordPid}`,
        );
        if (!cancelled && result.data) {
          setEntries(result.data);
        }
      } catch (err) {
        if (!cancelled) {
          console.error('[ApprovalCommentsBlock] Failed to load:', err);
          setError(t('approval.comments.error'));
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    loadComments();
    return () => {
      cancelled = true;
    };
  }, [recordPid, t]);

  // Loading state
  if (loading) {
    return (
      <div className="flex items-center justify-center py-8 text-sm text-gray-400">
        <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
        {t('approval.comments.loading')}
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className="py-4 text-center text-sm text-red-500">
        <AlertCircle className="mx-auto mb-1 h-5 w-5" />
        {error}
      </div>
    );
  }

  // Empty state
  if (entries.length === 0) {
    return (
      <div className="py-6 text-center text-sm text-gray-400">
        <FileText className="mx-auto mb-2 h-8 w-8 text-gray-300" />
        {t('approval.comments.noComments')}
      </div>
    );
  }

  if (!showTimeline) return null;

  return (
    <div className="space-y-0">
      {/* Vertical timeline */}
      <div className="relative">
        {/* Connector line */}
        <div className="absolute top-6 bottom-6 left-5 w-0.5 bg-gray-200" />

        <div className="space-y-0">
          {entries.map((entry, index) => {
            const statusConfig = STATUS_CONFIG[entry.status] || DEFAULT_STATUS_CONFIG;
            const Icon = statusConfig.icon;
            const isLast = index === entries.length - 1;
            const timestamp = entry.completedAt || entry.createdAt;

            return (
              <div key={entry.pid} className="relative flex items-start gap-4 py-3">
                {/* Status icon */}
                <div
                  className={cn(
                    'relative z-10 flex h-10 w-10 shrink-0 items-center justify-center rounded-full border',
                    statusConfig.bgColor,
                    statusConfig.borderColor,
                  )}
                >
                  <Icon className={cn('h-5 w-5', statusConfig.color)} />
                </div>

                {/* Content card */}
                <div className="min-w-0 flex-1">
                  <div
                    className={cn(
                      'rounded-lg border p-4',
                      statusConfig.borderColor,
                      statusConfig.bgColor,
                    )}
                  >
                    {/* Header: approver + status + time */}
                    <div className="mb-1 flex items-center justify-between gap-2">
                      <div className="flex min-w-0 items-center gap-2">
                        <UserCircle className="h-4 w-4 shrink-0 text-gray-400" />
                        <span className="truncate text-sm font-medium text-gray-900">
                          {entry.approverName || `User #${entry.actualApproverId || 'System'}`}
                        </span>
                        <span
                          className={cn(
                            'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
                            statusConfig.color,
                            statusConfig.bgColor,
                            'border',
                            statusConfig.borderColor,
                          )}
                        >
                          {t(`approval.status.${entry.status}`)}
                        </span>
                      </div>
                      <span className="text-xs whitespace-nowrap text-gray-400">
                        {formatRelativeTime(timestamp, locale)}
                      </span>
                    </div>

                    {/* Task title */}
                    {entry.taskTitle && (
                      <p className="mb-2 text-xs text-gray-500">{entry.taskTitle}</p>
                    )}

                    {/* Comment text */}
                    {entry.approvalComment && (
                      <div className="mt-2 rounded-md border border-gray-100 bg-white p-3">
                        <p className="text-sm whitespace-pre-wrap text-gray-700">
                          {entry.approvalComment}
                        </p>
                      </div>
                    )}

                    {/* Signature preview */}
                    {showSignature && entry.signature && (
                      <div className="mt-3">
                        <p className="mb-1 flex items-center gap-1 text-xs text-gray-500">
                          <PenLine className="h-3 w-3" />
                          {t('approval.comments.signature')}
                        </p>
                        <div className="inline-block rounded border border-gray-200 bg-white p-1">
                          <img
                            src={entry.signature}
                            alt="Signature"
                            className="max-h-16 max-w-[200px]"
                          />
                        </div>
                      </div>
                    )}

                    {/* Attachments */}
                    {showAttachments && entry.attachments && entry.attachments.length > 0 && (
                      <div className="mt-3">
                        <p className="mb-1 flex items-center gap-1 text-xs text-gray-500">
                          <Paperclip className="h-3 w-3" />
                          {t('approval.comments.attachments')} ({entry.attachments.length})
                        </p>
                        <div className="space-y-1">
                          {entry.attachments.map((att, ai) => (
                            <div
                              key={att.fileId || ai}
                              className="flex items-center gap-2 rounded border border-gray-100 bg-white p-2 text-sm"
                            >
                              <FileText className="h-4 w-4 shrink-0 text-gray-400" />
                              <span className="flex-1 truncate text-gray-700">{att.fileName}</span>
                              {att.fileSize && (
                                <span className="text-xs text-gray-400">
                                  {formatFileSize(att.fileSize)}
                                </span>
                              )}
                              {att.url && (
                                <a
                                  href={att.url}
                                  target="_blank"
                                  rel="noopener noreferrer"
                                  className="text-blue-600 hover:text-blue-800"
                                >
                                  <Download className="h-4 w-4" />
                                </a>
                              )}
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// Re-export SignatureCanvas for use in approval forms
export { SignatureCanvas };
export type { ApprovalCommentsConfig };
