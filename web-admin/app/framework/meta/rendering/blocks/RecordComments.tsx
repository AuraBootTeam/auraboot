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
  commentPid: string;
  content: string;
  created_at: string;
  updated_at: string;
  is_edited: boolean;
  actorName?: string;
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
  const [editingId, setEditingId] = useState<string | null>(null);
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
  const handleEdit = async (commentPid: string) => {
    if (!editContent.trim()) return;
    try {
      await fetchResult<any>(`${basePath}/${commentPid}`, {
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
  const handleDelete = async (commentPid: string) => {
    try {
      await fetchResult<any>(`${basePath}/${commentPid}`, { method: 'delete' });
      loadComments();
    } catch {
      // Ignore
    }
  };

  const startEdit = (comment: Comment) => {
    setEditingId(comment.commentPid);
    setEditContent(comment.content);
  };

  if (loading) {
    return (
      <div className="text-text-3 flex items-center justify-center py-12 text-sm">
        {t('comment.loading', 'Loading comments...')}
      </div>
    );
  }

  return (
    <div className="space-y-4 p-4">
      {/* New comment input */}
      <div className="rounded-card border-border bg-panel border p-3">
        <textarea
          value={newComment}
          onChange={(e) => setNewComment(e.target.value)}
          placeholder={t('comment.placeholder', 'Write a comment...')}
          rows={3}
          className="rounded-control border-border bg-subtle text-text placeholder:text-text-3 w-full resize-none border px-3 py-2 text-sm outline-none focus:border-accent focus:ring-1 focus:ring-accent"
          data-testid="comment-input"
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
              e.preventDefault();
              handleSubmit();
            }
          }}
        />
        <div className="mt-2 flex items-center justify-between">
          <span className="text-text-3 text-xs">
            {locale === 'zh-CN' ? 'Ctrl+Enter 发送' : 'Ctrl+Enter to send'}
          </span>
          <button
            onClick={handleSubmit}
            disabled={!newComment.trim() || submitting}
            className="rounded-control bg-accent hover:bg-accent-hover px-4 py-1.5 text-sm text-white transition-colors disabled:opacity-50"
            data-testid="comment-submit"
          >
            {submitting ? t('comment.submitting', 'Sending...') : t('comment.submit', 'Comment')}
          </button>
        </div>
      </div>

      {/* Comment list */}
      {comments.length === 0 ? (
        <div className="text-text-3 py-8 text-center text-sm">
          {t('comment.empty', 'No comments yet. Be the first to comment.')}
        </div>
      ) : (
        <div className="space-y-3" data-testid="comment-list">
          {comments.map((comment) => (
            <div
              key={comment.commentPid}
              className="rounded-card bg-panel border-border border p-3"
              data-testid={`comment-${comment.commentPid}`}
            >
              {/* Header: user + time */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="rounded-pill bg-accent-weak text-accent flex h-7 w-7 items-center justify-center text-xs font-medium">
                    {(comment.actorName || 'U').charAt(0).toUpperCase()}
                  </div>
                  <span className="text-text text-sm font-medium">
                    {comment.actorName || 'User'}
                  </span>
                  <span className="text-text-3 text-xs" title={comment.created_at}>
                    {dayjs(comment.created_at).fromNow()}
                  </span>
                  {comment.is_edited && (
                    <span className="text-text-3 text-xs italic">
                      ({t('comment.edited', 'edited')})
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => startEdit(comment)}
                    className="text-text-3 hover:bg-hover hover:text-text-2 rounded p-1 transition-colors"
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
                    onClick={() => handleDelete(comment.commentPid)}
                    className="text-text-3 hover:bg-status-red-bg hover:text-status-red rounded p-1 transition-colors"
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
              {editingId === comment.commentPid ? (
                <div className="mt-2">
                  <textarea
                    value={editContent}
                    onChange={(e) => setEditContent(e.target.value)}
                    rows={2}
                    className="rounded-control border-border bg-subtle text-text w-full border px-3 py-2 text-sm outline-none focus:border-accent"
                    data-testid="comment-edit-input"
                  />
                  <div className="mt-1 flex gap-2">
                    <button
                      onClick={() => handleEdit(comment.commentPid)}
                      className="bg-accent hover:bg-accent-hover rounded px-3 py-1 text-xs text-white"
                    >
                      {t('comment.save', 'Save')}
                    </button>
                    <button
                      onClick={() => {
                        setEditingId(null);
                        setEditContent('');
                      }}
                      className="text-text-2 hover:text-text-2 rounded px-3 py-1 text-xs"
                    >
                      {t('comment.cancel', 'Cancel')}
                    </button>
                  </div>
                </div>
              ) : (
                <p className="text-text-2 mt-2 text-sm whitespace-pre-wrap">
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
