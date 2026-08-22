/**
 * RecordComments — reusable, two-level record discussion thread.
 *
 * The renderer stays model-agnostic. Detail-page DSL supplies modelCode/recordPid;
 * the platform API enforces record visibility, author ownership, tenant-scoped
 * mentions, reply normalization, cascade deletion, and recipient notifications.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import dayjs from 'dayjs';
import {
  AtSign,
  ChevronDown,
  LoaderCircle,
  MessageSquareText,
  Pencil,
  Reply,
  Send,
  Trash2,
  X,
} from 'lucide-react';
import { toast } from 'sonner';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

interface MentionUser {
  pid: string;
  displayName: string;
  email?: string;
  departmentName?: string;
}

interface CommentItem {
  commentPid: string;
  parentPid?: string;
  replyToUserPid?: string;
  replyToName?: string;
  content: string;
  created_at: string;
  updated_at: string;
  is_edited: boolean;
  actorName?: string;
  canEdit?: boolean;
  mentionedUsers?: MentionUser[];
  replies?: CommentItem[];
}

interface CommentPage {
  items: CommentItem[];
  total: number;
  commentCount: number;
  page: number;
  size: number;
  hasMore: boolean;
}

interface ComposerValue {
  content: string;
  mentionUserPids: string[];
}

export interface RecordCommentsProps {
  modelCode: string;
  recordPid: string;
  token?: string;
  locale?: string;
  t?: (key: string) => string;
}

const PAGE_SIZE = 10;
const MAX_CONTENT_LENGTH = 3_000;

export function RecordComments({
  modelCode,
  recordPid,
  locale,
  t: externalT,
}: RecordCommentsProps) {
  const [comments, setComments] = useState<CommentItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [replyTarget, setReplyTarget] = useState<CommentItem | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);

  const l = useCallback(
    (key: string, zh: string, en: string) => {
      const translated = externalT?.(key);
      if (translated && translated !== key) return translated;
      return locale?.toLowerCase().startsWith('zh') ? zh : en;
    },
    [externalT, locale],
  );

  const basePath = `/api/records/${encodeURIComponent(modelCode)}/${encodeURIComponent(recordPid)}/comments`;

  const loadComments = useCallback(
    async (targetPage = 1, append = false) => {
      append ? setLoadingMore(true) : setLoading(true);
      try {
        const response = await fetchResult<CommentPage>(
          `${basePath}/page?page=${targetPage}&size=${PAGE_SIZE}`,
        );
        if (!ResultHelper.isSuccess(response) || !response.data) {
          throw new Error(
            response.message || l('comment.loadError', '评论加载失败', 'Failed to load comments'),
          );
        }
        const pageData = response.data;
        setComments((current) => (append ? [...current, ...pageData.items] : pageData.items));
        setTotal(pageData.commentCount);
        setPage(pageData.page);
        setHasMore(pageData.hasMore);
      } catch (error) {
        toast.error(
          error instanceof Error
            ? error.message
            : l('comment.loadError', '评论加载失败', 'Failed to load comments'),
        );
      } finally {
        setLoading(false);
        setLoadingMore(false);
      }
    },
    [basePath, l],
  );

  useEffect(() => {
    if (modelCode && recordPid) void loadComments();
  }, [loadComments, modelCode, recordPid]);

  const createComment = async (value: ComposerValue, parentPid?: string) => {
    if (submitting) return false;
    setSubmitting(true);
    try {
      const response = await fetchResult<CommentItem>(basePath, {
        method: 'post',
        params: { ...value, parentPid },
      });
      if (!ResultHelper.isSuccess(response)) {
        throw new Error(
          response.message || l('comment.createError', '评论发送失败', 'Failed to post comment'),
        );
      }
      setReplyTarget(null);
      await loadComments();
      toast.success(l('comment.created', '评论已发送', 'Comment posted'));
      return true;
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : l('comment.createError', '评论发送失败', 'Failed to post comment'),
      );
      return false;
    } finally {
      setSubmitting(false);
    }
  };

  const editComment = async (commentPid: string, value: ComposerValue) => {
    try {
      const response = await fetchResult<CommentItem>(
        `${basePath}/${encodeURIComponent(commentPid)}`,
        {
          method: 'put',
          params: value,
        },
      );
      if (!ResultHelper.isSuccess(response)) {
        throw new Error(
          response.message || l('comment.editError', '评论保存失败', 'Failed to save comment'),
        );
      }
      setEditingId(null);
      await loadComments();
      toast.success(l('comment.updated', '评论已更新', 'Comment updated'));
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : l('comment.editError', '评论保存失败', 'Failed to save comment'),
      );
    }
  };

  const deleteComment = async (commentPid: string) => {
    try {
      const response = await fetchResult<boolean>(`${basePath}/${encodeURIComponent(commentPid)}`, {
        method: 'delete',
      });
      if (!ResultHelper.isSuccess(response)) {
        throw new Error(
          response.message || l('comment.deleteError', '评论删除失败', 'Failed to delete comment'),
        );
      }
      await loadComments();
      toast.success(l('comment.deleted', '评论已删除', 'Comment deleted'));
    } catch (error) {
      toast.error(
        error instanceof Error
          ? error.message
          : l('comment.deleteError', '评论删除失败', 'Failed to delete comment'),
      );
    }
  };

  return (
    <section
      className="border-border bg-panel rounded-card overflow-hidden border shadow-sm"
      data-testid="record-comments"
    >
      <header className="border-border flex items-center justify-between border-b px-5 py-4">
        <div className="flex items-center gap-3">
          <span className="bg-accent-weak text-accent rounded-card flex h-9 w-9 items-center justify-center">
            <MessageSquareText className="h-4 w-4" aria-hidden="true" />
          </span>
          <div>
            <h3 className="text-text text-sm font-semibold">
              {l('comment.discussion', '跟进评论', 'Discussion')}
            </h3>
            <p className="text-text-3 mt-0.5 text-xs">
              {l(
                'comment.collaborationHint',
                '回复同事或使用 @ 提醒相关成员',
                'Reply to teammates or use @ to notify them',
              )}
            </p>
          </div>
        </div>
        <span className="border-border bg-subtle text-text-2 rounded-pill border px-2.5 py-1 text-xs tabular-nums">
          {total}
        </span>
      </header>

      <div className="border-border border-b p-4">
        <CommentComposer
          key={`root-${recordPid}-${total}`}
          locale={locale}
          l={l}
          submitting={submitting}
          placeholder={l(
            'comment.placeholder',
            '写下跟进，输入 @ 可提醒成员…',
            'Write an update; type @ to notify a teammate…',
          )}
          submitLabel={l('comment.submit', '发送评论', 'Post comment')}
          onSubmit={(value) => createComment(value)}
        />
      </div>

      {loading ? (
        <div
          className="text-text-3 flex items-center justify-center gap-2 py-12 text-sm"
          data-testid="comment-loading"
        >
          <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" />
          {l('comment.loading', '正在加载评论…', 'Loading comments…')}
        </div>
      ) : comments.length === 0 ? (
        <div className="px-6 py-12 text-center" data-testid="comment-empty">
          <MessageSquareText className="text-text-3 mx-auto h-8 w-8" aria-hidden="true" />
          <p className="text-text-2 mt-3 text-sm font-medium">
            {l('comment.emptyTitle', '还没有跟进评论', 'No comments yet')}
          </p>
          <p className="text-text-3 mt-1 text-xs">
            {l(
              'comment.emptyHint',
              '发出第一条评论，开始围绕这条记录协作。',
              'Post the first comment to start collaborating on this record.',
            )}
          </p>
        </div>
      ) : (
        <div className="divide-border divide-y" data-testid="comment-list">
          {comments.map((comment) => (
            <CommentCard
              key={comment.commentPid}
              comment={comment}
              depth={0}
              locale={locale}
              l={l}
              editingId={editingId}
              replyTarget={replyTarget}
              submitting={submitting}
              onEditStart={(item) => {
                setReplyTarget(null);
                setEditingId(item.commentPid);
              }}
              onEditCancel={() => setEditingId(null)}
              onEdit={editComment}
              onReplyStart={(item) => {
                setEditingId(null);
                setReplyTarget(item);
              }}
              onReplyCancel={() => setReplyTarget(null)}
              onReply={(item, value) => createComment(value, item.commentPid)}
              onDelete={deleteComment}
            />
          ))}
        </div>
      )}

      {hasMore && (
        <div className="border-border flex justify-center border-t p-3">
          <button
            type="button"
            onClick={() => void loadComments(page + 1, true)}
            disabled={loadingMore}
            className="text-text-2 hover:bg-hover focus-visible:ring-accent-weak rounded-control inline-flex h-8 items-center gap-2 px-3 text-xs font-medium transition-colors focus-visible:ring-3 focus-visible:outline-none disabled:opacity-50"
            data-testid="comment-load-more"
          >
            {loadingMore ? (
              <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <ChevronDown className="h-3.5 w-3.5" />
            )}
            {l('comment.loadMore', '加载更多评论', 'Load more comments')}
          </button>
        </div>
      )}
    </section>
  );
}

interface CommentCardProps {
  comment: CommentItem;
  depth: number;
  locale?: string;
  l: (key: string, zh: string, en: string) => string;
  editingId: string | null;
  replyTarget: CommentItem | null;
  submitting: boolean;
  onEditStart: (comment: CommentItem) => void;
  onEditCancel: () => void;
  onEdit: (commentPid: string, value: ComposerValue) => Promise<void>;
  onReplyStart: (comment: CommentItem) => void;
  onReplyCancel: () => void;
  onReply: (comment: CommentItem, value: ComposerValue) => Promise<void | boolean>;
  onDelete: (commentPid: string) => Promise<void>;
}

function CommentCard(props: CommentCardProps) {
  const {
    comment,
    depth,
    locale,
    l,
    editingId,
    replyTarget,
    submitting,
    onEditStart,
    onEditCancel,
    onEdit,
    onReplyStart,
    onReplyCancel,
    onReply,
    onDelete,
  } = props;
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const isEditing = editingId === comment.commentPid;
  const isReplying = replyTarget?.commentPid === comment.commentPid;
  const actor = comment.actorName || l('comment.unknownUser', '用户', 'User');

  return (
    <article
      className={depth === 0 ? 'px-5 py-4' : 'border-border ml-11 border-l-2 py-3 pr-1 pl-4'}
      data-testid={`comment-${comment.commentPid}`}
    >
      <div className="flex gap-3">
        <div className="bg-accent-weak text-accent rounded-pill flex h-8 w-8 shrink-0 items-center justify-center text-xs font-semibold">
          {actor.charAt(0).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="text-text text-sm font-semibold">{actor}</span>
            {comment.replyToName && (
              <span className="text-text-3 text-xs">
                {l('comment.replyTo', '回复', 'replied to')}{' '}
                <span className="text-text-2">@{comment.replyToName}</span>
              </span>
            )}
            <time className="text-text-3 text-xs" dateTime={comment.created_at}>
              {dayjs(comment.created_at).format('YYYY-MM-DD HH:mm')}
            </time>
            {comment.is_edited && (
              <span className="text-text-3 text-xs">{l('comment.edited', '已编辑', 'edited')}</span>
            )}
          </div>

          {isEditing ? (
            <div className="mt-3">
              <CommentComposer
                autoFocus
                locale={locale}
                l={l}
                initialContent={comment.content}
                initialMentions={comment.mentionedUsers}
                submitLabel={l('comment.save', '保存修改', 'Save changes')}
                placeholder={l('comment.editPlaceholder', '修改评论…', 'Edit comment…')}
                onSubmit={(value) => onEdit(comment.commentPid, value)}
                onCancel={onEditCancel}
              />
            </div>
          ) : (
            <p className="text-text-2 mt-2 text-sm leading-6 whitespace-pre-wrap">
              {comment.content}
            </p>
          )}

          {!isEditing && (
            <div className="mt-2 flex min-h-7 items-center gap-1">
              <button
                type="button"
                onClick={() => onReplyStart(comment)}
                className="text-text-3 hover:bg-hover hover:text-text-2 rounded-control inline-flex h-7 items-center gap-1.5 px-2 text-xs transition-colors"
              >
                <Reply className="h-3.5 w-3.5" aria-hidden="true" />
                {l('comment.reply', '回复', 'Reply')}
              </button>
              {comment.canEdit && !confirmingDelete && (
                <>
                  <button
                    type="button"
                    onClick={() => onEditStart(comment)}
                    className="text-text-3 hover:bg-hover hover:text-text-2 rounded-control inline-flex h-7 items-center gap-1.5 px-2 text-xs transition-colors"
                  >
                    <Pencil className="h-3.5 w-3.5" aria-hidden="true" />
                    {l('comment.edit', '编辑', 'Edit')}
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmingDelete(true)}
                    className="text-text-3 hover:bg-status-red-bg hover:text-status-red rounded-control inline-flex h-7 items-center gap-1.5 px-2 text-xs transition-colors"
                  >
                    <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                    {l('comment.delete', '删除', 'Delete')}
                  </button>
                </>
              )}
              {confirmingDelete && (
                <span className="bg-status-red-bg text-status-red rounded-control inline-flex items-center gap-1 px-2 py-1 text-xs">
                  {l('comment.deleteConfirm', '确认删除？', 'Delete this comment?')}
                  <button
                    type="button"
                    className="font-semibold underline"
                    onClick={() => void onDelete(comment.commentPid)}
                  >
                    {l('comment.confirm', '确认', 'Confirm')}
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmingDelete(false)}
                    aria-label={l('comment.cancel', '取消', 'Cancel')}
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </span>
              )}
            </div>
          )}

          {isReplying && (
            <div className="bg-subtle border-border rounded-card mt-3 border p-3">
              <p className="text-text-3 mb-2 text-xs">
                {l('comment.replyingTo', '回复', 'Replying to')}{' '}
                <span className="text-text-2 font-medium">@{actor}</span>
              </p>
              <CommentComposer
                autoFocus
                locale={locale}
                l={l}
                submitting={submitting}
                placeholder={l(
                  'comment.replyPlaceholder',
                  '写下回复，输入 @ 可提醒其他成员…',
                  'Write a reply; type @ to notify others…',
                )}
                submitLabel={l('comment.sendReply', '发送回复', 'Post reply')}
                onSubmit={(value) => onReply(comment, value)}
                onCancel={onReplyCancel}
              />
            </div>
          )}
        </div>
      </div>

      {depth === 0 &&
        comment.replies?.map((reply) => (
          <CommentCard key={reply.commentPid} {...props} comment={reply} depth={1} />
        ))}
    </article>
  );
}

interface CommentComposerProps {
  locale?: string;
  l: (key: string, zh: string, en: string) => string;
  initialContent?: string;
  initialMentions?: MentionUser[];
  placeholder: string;
  submitLabel: string;
  submitting?: boolean;
  autoFocus?: boolean;
  onSubmit: (value: ComposerValue) => void | boolean | Promise<void | boolean>;
  onCancel?: () => void;
}

function CommentComposer({
  l,
  initialContent = '',
  initialMentions = [],
  placeholder,
  submitLabel,
  submitting = false,
  autoFocus = false,
  onSubmit,
  onCancel,
}: CommentComposerProps) {
  const [content, setContent] = useState(initialContent);
  const [mentions, setMentions] = useState<MentionUser[]>(initialMentions);
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const [suggestions, setSuggestions] = useState<MentionUser[]>([]);
  const [searching, setSearching] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const remaining = MAX_CONTENT_LENGTH - content.length;
  const canSubmit = content.trim().length > 0 && remaining >= 0 && !submitting;

  useEffect(() => {
    if (autoFocus) textareaRef.current?.focus();
  }, [autoFocus]);

  useEffect(() => {
    if (mentionQuery === null) {
      setSuggestions([]);
      return;
    }
    let active = true;
    const timer = window.setTimeout(async () => {
      setSearching(true);
      try {
        const response = await fetchResult<MentionUser[]>(
          `/api/admin/users/search?keyword=${encodeURIComponent(mentionQuery)}&size=8`,
        );
        if (active && ResultHelper.isSuccess(response) && Array.isArray(response.data)) {
          setSuggestions(
            response.data.filter((user) => !mentions.some((item) => item.pid === user.pid)),
          );
        }
      } catch {
        if (active) setSuggestions([]);
      } finally {
        if (active) setSearching(false);
      }
    }, 180);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [mentionQuery, mentions]);

  const detectMentionQuery = (value: string, caret: number) => {
    const beforeCaret = value.slice(0, caret);
    const match = beforeCaret.match(/(?:^|\s)@([^\s@]*)$/);
    setMentionQuery(match ? match[1] : null);
  };

  const handleChange = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = event.target.value;
    setContent(value);
    setMentions((current) => current.filter((user) => value.includes(`@${user.displayName}`)));
    detectMentionQuery(value, event.target.selectionStart);
  };

  const selectMention = (user: MentionUser) => {
    const textarea = textareaRef.current;
    const caret = textarea?.selectionStart ?? content.length;
    const beforeCaret = content.slice(0, caret);
    const match = beforeCaret.match(/(?:^|\s)@([^\s@]*)$/);
    if (!match) return;
    const atIndex = beforeCaret.lastIndexOf('@');
    const next = `${content.slice(0, atIndex)}@${user.displayName} ${content.slice(caret)}`;
    const nextCaret = atIndex + user.displayName.length + 2;
    setContent(next);
    setMentions((current) => [...current.filter((item) => item.pid !== user.pid), user]);
    setMentionQuery(null);
    window.requestAnimationFrame(() => {
      textarea?.focus();
      textarea?.setSelectionRange(nextCaret, nextCaret);
    });
  };

  const removeMention = (user: MentionUser) => {
    setMentions((current) => current.filter((item) => item.pid !== user.pid));
    setContent((current) => current.replaceAll(`@${user.displayName}`, user.displayName));
  };

  const submit = async () => {
    if (!canSubmit) return;
    const succeeded = await onSubmit({
      content: content.trim(),
      mentionUserPids: mentions.map((user) => user.pid),
    });
    if (!onCancel && succeeded !== false) {
      setContent('');
      setMentions([]);
      setMentionQuery(null);
    }
  };

  return (
    <div className="relative">
      <div className="border-border bg-subtle focus-within:border-accent focus-within:ring-accent-weak rounded-card border transition-shadow focus-within:ring-3">
        <textarea
          ref={textareaRef}
          value={content}
          onChange={handleChange}
          onClick={(event) => detectMentionQuery(content, event.currentTarget.selectionStart)}
          onKeyDown={(event) => {
            if (event.key === 'Escape' && mentionQuery !== null) setMentionQuery(null);
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
              event.preventDefault();
              void submit();
            }
          }}
          placeholder={placeholder}
          rows={3}
          maxLength={MAX_CONTENT_LENGTH + 1}
          className="text-text placeholder:text-text-3 min-h-20 w-full resize-y bg-transparent px-3.5 py-3 text-sm leading-6 outline-none"
          data-testid="comment-input"
        />
        <div className="border-border flex flex-wrap items-center justify-between gap-2 border-t px-3 py-2">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-text-3 inline-flex items-center gap-1 text-xs">
              <AtSign className="h-3.5 w-3.5" />
              {l('comment.mentionHint', '提及成员', 'Mention people')}
            </span>
            {mentions.map((user) => (
              <span
                key={user.pid}
                className="bg-accent-weak text-accent rounded-pill inline-flex items-center gap-1 px-2 py-0.5 text-xs"
              >
                @{user.displayName}
                <button
                  type="button"
                  onClick={() => removeMention(user)}
                  aria-label={l('comment.removeMention', '移除提及', 'Remove mention')}
                >
                  <X className="h-3 w-3" />
                </button>
              </span>
            ))}
          </div>
          <span
            className={
              remaining < 0
                ? 'text-status-red text-xs tabular-nums'
                : 'text-text-3 text-xs tabular-nums'
            }
          >
            {content.length}/{MAX_CONTENT_LENGTH}
          </span>
        </div>
      </div>

      {mentionQuery !== null && (
        <div
          className="border-border bg-panel rounded-card absolute top-full left-3 z-20 mt-1 w-72 overflow-hidden border shadow-lg"
          data-testid="mention-suggestions"
        >
          <div className="border-border text-text-3 border-b px-3 py-2 text-xs">
            {l('comment.mentionSearch', '选择要提醒的成员', 'Choose someone to notify')}
          </div>
          {searching ? (
            <div className="text-text-3 flex items-center gap-2 px-3 py-4 text-xs">
              <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
              {l('comment.searchingUsers', '正在搜索成员…', 'Searching people…')}
            </div>
          ) : suggestions.length === 0 ? (
            <div className="text-text-3 px-3 py-4 text-xs">
              {l('comment.noUsers', '没有匹配的成员', 'No matching people')}
            </div>
          ) : (
            <div className="max-h-56 overflow-y-auto py-1">
              {suggestions.map((user) => (
                <button
                  key={user.pid}
                  type="button"
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => selectMention(user)}
                  className="hover:bg-hover flex w-full items-center gap-2 px-3 py-2 text-left transition-colors"
                >
                  <span className="bg-accent-weak text-accent rounded-pill flex h-7 w-7 shrink-0 items-center justify-center text-xs font-semibold">
                    {user.displayName.charAt(0).toUpperCase()}
                  </span>
                  <span className="min-w-0">
                    <span className="text-text block truncate text-sm">{user.displayName}</span>
                    <span className="text-text-3 block truncate text-xs">
                      {user.departmentName || user.email}
                    </span>
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      <div className="mt-2 flex items-center justify-between gap-3">
        <span className="text-text-3 text-xs">
          {l('comment.shortcut', 'Ctrl/⌘ + Enter 发送', 'Ctrl/⌘ + Enter to post')}
        </span>
        <div className="flex items-center gap-2">
          {onCancel && (
            <button
              type="button"
              onClick={onCancel}
              className="text-text-2 hover:bg-hover rounded-control h-8 px-3 text-xs font-medium transition-colors"
            >
              {l('comment.cancel', '取消', 'Cancel')}
            </button>
          )}
          <button
            type="button"
            onClick={() => void submit()}
            disabled={!canSubmit}
            className="bg-accent hover:bg-accent-hover focus-visible:ring-accent-weak rounded-control inline-flex h-8 items-center gap-2 px-3.5 text-xs font-semibold text-white transition-colors focus-visible:ring-3 focus-visible:outline-none disabled:opacity-50"
            data-testid="comment-submit"
          >
            {submitting ? (
              <LoaderCircle className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Send className="h-3.5 w-3.5" />
            )}
            {submitting ? l('comment.submitting', '发送中…', 'Posting…') : submitLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
