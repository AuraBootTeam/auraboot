/**
 * ApprovalFormDrawer — slide-over drawer for viewing and acting on approval tasks.
 *
 * Sections:
 * 1. Business Context (read-only form snapshot)
 * 2. Comment input
 * 3. Approve / Reject buttons
 */

import React, { useState, useEffect } from 'react';
import {
  type ApprovalTaskDTO,
  getTaskDetail,
  approveTask,
  rejectTask,
} from '~/plugins/core-bpm/services/approvalService';

interface Props {
  taskPid: string;
  onClose: () => void;
  onActionComplete: () => void;
}

export const ApprovalFormDrawer: React.FC<Props> = ({ taskPid, onClose, onActionComplete }) => {
  const [task, setTask] = useState<ApprovalTaskDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      setLoading(true);
      try {
        const result = await getTaskDetail(taskPid);
        if (result.success && result.data) {
          setTask(result.data);
        }
      } catch (e) {
        console.error('Failed to load task:', e);
      } finally {
        setLoading(false);
      }
    })();
  }, [taskPid]);

  const handleApprove = async () => {
    setSubmitting(true);
    setError(null);
    try {
      const result = await approveTask(taskPid, { comment });
      if (result.success) {
        onActionComplete();
      } else {
        setError(result.message || 'Failed to approve');
      }
    } catch (e) {
      setError('Network error');
    } finally {
      setSubmitting(false);
    }
  };

  const handleReject = async () => {
    if (!comment.trim()) {
      setError('Comment is required for rejection');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await rejectTask(taskPid, { comment });
      if (result.success) {
        onActionComplete();
      } else {
        setError(result.message || 'Failed to reject');
      }
    } catch (e) {
      setError('Network error');
    } finally {
      setSubmitting(false);
    }
  };

  const isPending = task?.status === 'pending';

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/30"
        onClick={onClose}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
        role="button"
        tabIndex={0}
        aria-label="Close drawer"
      />

      {/* Drawer panel */}
      <div className="relative flex w-full max-w-lg flex-col bg-white shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-900">
            {isPending ? 'Review & Approve' : 'Task Detail'}
          </h2>
          <button type="button" onClick={onClose} className="text-gray-400 hover:text-gray-600">
            &times;
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 space-y-6 overflow-y-auto px-6 py-4">
          {loading ? (
            <div className="py-12 text-center text-gray-400">Loading...</div>
          ) : task ? (
            <>
              {/* Task info */}
              <div>
                <h3 className="mb-2 text-base font-medium text-gray-900">{task.taskTitle}</h3>
                {task.taskDescription && (
                  <p className="mb-2 text-sm text-gray-500">{task.taskDescription}</p>
                )}
                <div className="flex flex-wrap gap-2 text-xs">
                  <span className="rounded bg-gray-100 px-2 py-1">Status: {task.status}</span>
                  <span className="rounded bg-gray-100 px-2 py-1">Priority: {task.priority}</span>
                  {task.businessKey && (
                    <span className="rounded bg-gray-100 px-2 py-1">Key: {task.businessKey}</span>
                  )}
                  {task.deadlineAt && (
                    <span className="rounded bg-gray-100 px-2 py-1">
                      Deadline: {new Date(task.deadlineAt).toLocaleString()}
                    </span>
                  )}
                </div>
              </div>

              {/* Business context (form snapshot) */}
              {task.formSnapshot && Object.keys(task.formSnapshot).length > 0 && (
                <div>
                  <h4 className="mb-2 text-sm font-medium text-gray-700">Business Data</h4>
                  <div className="space-y-2 rounded-lg bg-gray-50 p-3">
                    {Object.entries(task.formSnapshot).map(([key, value]) => (
                      <div key={key} className="flex justify-between text-sm">
                        <span className="text-gray-500">{key}</span>
                        <span className="font-medium text-gray-900">
                          {value != null ? String(value) : '-'}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Previous approval data */}
              {task.approvalData && Object.keys(task.approvalData).length > 0 && (
                <div>
                  <h4 className="mb-2 text-sm font-medium text-gray-700">Approval Data</h4>
                  <div className="space-y-2 rounded-lg bg-green-50 p-3">
                    {Object.entries(task.approvalData).map(([key, value]) => (
                      <div key={key} className="flex justify-between text-sm">
                        <span className="text-gray-500">{key}</span>
                        <span className="text-gray-900">{String(value)}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Previous comment (for completed tasks) */}
              {!isPending && task.approvalComment && (
                <div>
                  <h4 className="mb-2 text-sm font-medium text-gray-700">Comment</h4>
                  <div className="rounded-lg bg-gray-50 p-3 text-sm text-gray-700">
                    {task.approvalComment}
                  </div>
                </div>
              )}

              {/* Comment input (for pending tasks) */}
              {isPending && (
                <div>
                  <h4 className="mb-2 text-sm font-medium text-gray-700">
                    Comment {task.status === 'pending' && '(required for rejection)'}
                  </h4>
                  <textarea
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    placeholder="Enter your comment..."
                    rows={3}
                    className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                </div>
              )}

              {/* Error */}
              {error && (
                <div className="rounded-lg bg-red-50 p-3 text-sm text-red-600">{error}</div>
              )}
            </>
          ) : (
            <div className="py-12 text-center text-gray-400">Task not found</div>
          )}
        </div>

        {/* Footer with action buttons (only for pending tasks) */}
        {isPending && task && (
          <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
            <button
              type="button"
              onClick={handleReject}
              disabled={submitting}
              className="rounded-lg bg-red-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-red-600 disabled:opacity-50"
            >
              {submitting ? 'Processing...' : 'Reject'}
            </button>
            <button
              type="button"
              onClick={handleApprove}
              disabled={submitting}
              className="rounded-lg bg-green-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-green-600 disabled:opacity-50"
            >
              {submitting ? 'Processing...' : 'Approve'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
