/**
 * RecordComments — Comment thread for a record.
 * Calls GET/POST/PUT/DELETE /api/records/{modelCode}/{recordPid}/comments.
 *
 * Features:
 * - Comment list with user name, timestamp, edited badge
 * - Add new comment with textarea
 * - Edit/delete own comments
 * - Time-relative display (e.g., "2 hours ago")
 */

import React, { useState, useEffect, useCallback } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

dayjs.extend(relativeTime);

interface Comment {
  id: number;
  content: string;
  created_by: number;
  created_at: string;
  updated_at: string;
  is_edited: boolean;
  actor_name?: string;
}

export interface RecordCommentsProps {
  modelCode: string;
  recordPid: string;
  token?: string;
  locale?: string;
  t?: (key: string) => string;
}

export function RecordComments({
  modelCode,
  recordPid,
  locale,
  t: externalT,
}: RecordCommentsProps) {
  const [comments, setComments] = useState<Comment[]>([]);
  const [loading, setLoading] = useState(true);
  const [newComment, setNewComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');

  const t = useCallback(
    (key: string, fallback?: string) => (externalT ? externalT(key) : (fallback ?? key)),
    [externalT],
  );

  const basePath = `/api/records/${modelCode}/${recordPid}/comments`;

  // Load comments
  const loadComments = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await fetchResult<any>(basePath);
      if (ResultHelper.isSuccess(resp) && Array.isArray(resp.data)) {
        setComments(resp.data);
      }
    } catch {
      // Ignore load errors
    } finally {
      setLoading(false);
    }
  }, [basePath]);

  useEffect(() => {
    if (modelCode && recordPid) {
      loadComments();
    }
  }, [modelCode, recordPid, loadComments]);

  // Add comment
  const handleSubmit = async () => {
    if (!newComment.trim() || submitting) return;
    setSubmitting(true);
    try {
      const resp = await fetchResult<any>(basePath, {
        method: 'post',
        params: { content: newComment.trim() },
      });
      if (ResultHelper.isSuccess(resp)) {
        setNewComment('');
        loadComments();
      }
    } catch {
      // Ignore
    } finally {
      setSubmitting(false);
    }
  };

  // Edit comment
  const handleEdit = async (commentId: number) => {
    if (!editContent.trim()) return;
    try {
      await fetchResult<any>(`${basePath}/${commentId}`, {
        method: 'put',
        params: { content: editContent.trim() },
      });
      setEditingId(null);
      setEditContent('');
      loadComments();
    } catch {
      // Ignore
    }
  };

  // Delete comment
  const handleDelete = async (commentId: number) => {
    try {
      await fetchResult<any>(`${basePath}/${commentId}`, { method: 'delete' });
      loadComments();
    } catch {
      // Ignore
    }
  };

  const startEdit = (comment: Comment) => {
    setEditingId(comment.id);
    setEditContent(comment.content);
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12 text-sm text-gray-400">
        {t('comment.loading', 'Loading comments...')}
      </div>
    );
  }

  return (
    <div className="space-y-4 p-4">
      {/* New comment input */}
      <div className="rounded-lg border border-gray-200 bg-white p-3 dark:border-gray-700 dark:bg-gray-800">
        <textarea
          value={newComment}
          onChange={(e) => setNewComment(e.target.value)}
          placeholder={t('comment.placeholder', 'Write a comment...')}
          rows={3}
          className="w-full resize-none rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm text-gray-900 placeholder-gray-400 outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-400 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
          data-testid="comment-input"
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
              e.preventDefault();
              handleSubmit();
            }
          }}
        />
        <div className="mt-2 flex items-center justify-between">
          <span className="text-xs text-gray-400">
            {locale === 'zh-CN' ? 'Ctrl+Enter 发送' : 'Ctrl+Enter to send'}
          </span>
          <button
            onClick={handleSubmit}
            disabled={!newComment.trim() || submitting}
            className="rounded-md bg-blue-600 px-4 py-1.5 text-sm text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
            data-testid="comment-submit"
          >
            {submitting ? t('comment.submitting', 'Sending...') : t('comment.submit', 'Comment')}
          </button>
        </div>
      </div>

      {/* Comment list */}
      {comments.length === 0 ? (
        <div className="py-8 text-center text-sm text-gray-400 dark:text-gray-500">
          {t('comment.empty', 'No comments yet. Be the first to comment.')}
        </div>
      ) : (
        <div className="space-y-3" data-testid="comment-list">
          {comments.map((comment) => (
            <div
              key={comment.id}
              className="rounded-lg border border-gray-100 bg-white p-3 dark:border-gray-700 dark:bg-gray-800"
              data-testid={`comment-${comment.id}`}
            >
              {/* Header: user + time */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="flex h-7 w-7 items-center justify-center rounded-full bg-blue-100 text-xs font-medium text-blue-600 dark:bg-blue-900 dark:text-blue-300">
                    {(comment.actor_name || 'U').charAt(0).toUpperCase()}
                  </div>
                  <span className="text-sm font-medium text-gray-800 dark:text-gray-200">
                    {comment.actor_name || `User #${comment.created_by}`}
                  </span>
                  <span className="text-xs text-gray-400" title={comment.created_at}>
                    {dayjs(comment.created_at).fromNow()}
                  </span>
                  {comment.is_edited && (
                    <span className="text-xs text-gray-400 italic">
                      ({t('comment.edited', 'edited')})
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => startEdit(comment)}
                    className="rounded p-1 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700"
                    title={t('comment.edit', 'Edit')}
                  >
                    <svg
                      className="h-3.5 w-3.5"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                      />
                    </svg>
                  </button>
                  <button
                    onClick={() => handleDelete(comment.id)}
                    className="rounded p-1 text-gray-400 transition-colors hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/20"
                    title={t('comment.delete', 'Delete')}
                  >
                    <svg
                      className="h-3.5 w-3.5"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
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

              {/* Content or edit form */}
              {editingId === comment.id ? (
                <div className="mt-2">
                  <textarea
                    value={editContent}
                    onChange={(e) => setEditContent(e.target.value)}
                    rows={2}
                    className="w-full rounded-md border border-gray-200 px-3 py-2 text-sm outline-none focus:border-blue-400 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
                    data-testid="comment-edit-input"
                  />
                  <div className="mt-1 flex gap-2">
                    <button
                      onClick={() => handleEdit(comment.id)}
                      className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700"
                    >
                      {t('comment.save', 'Save')}
                    </button>
                    <button
                      onClick={() => {
                        setEditingId(null);
                        setEditContent('');
                      }}
                      className="rounded px-3 py-1 text-xs text-gray-500 hover:text-gray-700"
                    >
                      {t('comment.cancel', 'Cancel')}
                    </button>
                  </div>
                </div>
              ) : (
                <p className="mt-2 text-sm whitespace-pre-wrap text-gray-700 dark:text-gray-300">
                  {comment.content}
                </p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
