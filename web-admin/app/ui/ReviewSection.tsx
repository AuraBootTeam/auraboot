import { useState, useEffect, useCallback } from 'react';
import { StarIcon } from '@heroicons/react/24/solid';
import {
  StarIcon as StarOutlineIcon,
  HandThumbUpIcon,
  ChatBubbleLeftIcon,
} from '@heroicons/react/24/outline';

// ---- Types ----------------------------------------------------------------

interface Review {
  pid: string;
  targetType: string;
  targetId: string;
  parentId?: string;
  userId: number;
  userName: string;
  rating?: number;
  title?: string;
  content?: string;
  helpfulCount: number;
  replyCount: number;
  owner: boolean;
  replies?: Review[];
  createdAt: string;
  updatedAt: string;
}

interface ReviewSummary {
  targetType: string;
  targetId: string;
  totalCount: number;
  averageRating?: number;
  distribution?: Record<string, number>;
}

// ---- Config ---------------------------------------------------------------

const RATING_CONFIG: Record<
  string,
  { ratingMode: 'required' | 'optional' | 'disabled'; maxDepth: number }
> = {
  MARKETPLACE_PLUGIN: { ratingMode: 'required', maxDepth: 3 },
  TOPIC: { ratingMode: 'disabled', maxDepth: 5 },
  KNOWLEDGE_ARTICLE: { ratingMode: 'optional', maxDepth: 3 },
};

const DEFAULT_CONFIG = { ratingMode: 'optional' as const, maxDepth: 3 };

// ---- Helpers --------------------------------------------------------------

function timeAgo(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return 'just now / 刚刚';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(dateStr).toLocaleDateString();
}

function StarRating({
  value,
  onChange,
  readOnly = false,
  size = 'sm',
}: {
  value: number;
  onChange?: (v: number) => void;
  readOnly?: boolean;
  size?: 'sm' | 'md';
}) {
  const [hover, setHover] = useState(0);
  const cls = size === 'md' ? 'h-6 w-6' : 'h-4 w-4';
  return (
    <div className="flex items-center gap-0.5">
      {[1, 2, 3, 4, 5].map((s) => {
        const filled = (hover || value) >= s;
        return readOnly ? (
          <StarIcon key={s} className={`${cls} ${filled ? 'text-amber-400' : 'text-gray-200'}`} />
        ) : (
          <button
            key={s}
            type="button"
            onMouseEnter={() => setHover(s)}
            onMouseLeave={() => setHover(0)}
            onClick={() => onChange?.(s)}
            className="focus:outline-none"
          >
            {filled ? (
              <StarIcon className={`${cls} text-amber-400`} />
            ) : (
              <StarOutlineIcon className={`${cls} text-gray-300 hover:text-amber-300`} />
            )}
          </button>
        );
      })}
    </div>
  );
}

// ---- Write Form -----------------------------------------------------------

interface WriteFormProps {
  ratingMode: 'required' | 'optional' | 'disabled';
  parentId?: string;
  onSubmit: (data: {
    rating?: number;
    title?: string;
    content: string;
    parentId?: string;
  }) => Promise<void>;
  onCancel?: () => void;
  placeholder?: string;
}

function WriteForm({ ratingMode, parentId, onSubmit, onCancel, placeholder }: WriteFormProps) {
  const [content, setContent] = useState('');
  const [title, setTitle] = useState('');
  const [rating, setRating] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!content.trim()) {
      setError('Please enter a comment / 请输入内容');
      return;
    }
    if (ratingMode === 'required' && !rating) {
      setError('Please select a rating / 请选择评分');
      return;
    }
    setError('');
    setSubmitting(true);
    try {
      await onSubmit({
        content: content.trim(),
        title: title.trim() || undefined,
        rating: ratingMode !== 'disabled' && rating ? rating : undefined,
        parentId,
      });
      setContent('');
      setTitle('');
      setRating(0);
    } catch (err: any) {
      setError(err?.message || 'Submission failed / 提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      {ratingMode !== 'disabled' && !parentId && (
        <div className="flex items-center gap-2">
          <span className="text-sm text-gray-500">Rating / 评分:</span>
          <StarRating value={rating} onChange={setRating} size="md" />
          {ratingMode === 'required' && !rating && (
            <span className="text-xs text-gray-400">(required / 必填)</span>
          )}
        </div>
      )}
      {!parentId && (
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Title (optional) / 标题（选填）"
          className="w-full rounded-lg border border-gray-200 px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-300 focus:outline-none"
        />
      )}
      <textarea
        value={content}
        onChange={(e) => setContent(e.target.value)}
        placeholder={
          placeholder ||
          (parentId ? 'Write a reply... / 写回复...' : 'Write a review... / 写评论...')
        }
        rows={parentId ? 2 : 4}
        className="w-full resize-none rounded-lg border border-gray-200 px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-300 focus:outline-none"
      />
      {error && <p className="text-xs text-red-500">{error}</p>}
      <div className="flex items-center gap-2">
        <button
          type="submit"
          disabled={submitting}
          className="rounded-lg bg-indigo-600 px-4 py-1.5 text-sm font-medium text-white transition-colors hover:bg-indigo-700 disabled:opacity-50"
        >
          {submitting
            ? 'Submitting... / 提交中...'
            : parentId
              ? 'Reply / 回复'
              : 'Submit Review / 提交评论'}
        </button>
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="px-4 py-1.5 text-sm text-gray-500 hover:text-gray-700"
          >
            Cancel / 取消
          </button>
        )}
      </div>
    </form>
  );
}

// ---- Single Review Item ---------------------------------------------------

interface ReviewItemProps {
  review: Review;
  depth: number;
  maxDepth: number;
  ratingMode: 'required' | 'optional' | 'disabled';
  onVote: (pid: string) => void;
  onReply: (parentId: string, content: string, rating?: number) => Promise<void>;
}

function ReviewItem({ review, depth, maxDepth, ratingMode, onVote, onReply }: ReviewItemProps) {
  const [showReplyForm, setShowReplyForm] = useState(false);

  const handleReplySubmit = async (data: { content: string; rating?: number }) => {
    await onReply(review.pid, data.content, data.rating);
    setShowReplyForm(false);
  };

  const indent = depth > 0;

  return (
    <div className={`${indent ? 'ml-6 border-l-2 border-indigo-100 pl-4' : ''}`}>
      <div className={`${review.owner ? 'rounded-lg bg-indigo-50 p-4' : 'py-3'}`}>
        <div className="flex items-start gap-3">
          {/* Avatar placeholder */}
          <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-gray-200">
            <span className="text-xs font-semibold text-gray-600">
              {(review.userName || 'U').charAt(0).toUpperCase()}
            </span>
          </div>

          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium text-gray-900">
                {review.userName || 'Anonymous'}
              </span>
              {review.owner && (
                <span className="rounded-full bg-indigo-600 px-2 py-0.5 text-xs font-medium text-white">
                  Developer / 开发者
                </span>
              )}
              {review.rating && ratingMode !== 'disabled' && (
                <StarRating value={review.rating} readOnly />
              )}
              <span className="text-xs text-gray-400">{timeAgo(review.createdAt)}</span>
            </div>

            {review.title && (
              <p className="mt-1 text-sm font-semibold text-gray-800">{review.title}</p>
            )}
            {review.content && (
              <p className="mt-1 text-sm whitespace-pre-wrap text-gray-700">{review.content}</p>
            )}

            <div className="mt-2 flex items-center gap-3">
              <button
                onClick={() => onVote(review.pid)}
                className="flex items-center gap-1 text-xs text-gray-400 transition-colors hover:text-indigo-600"
              >
                <HandThumbUpIcon className="h-3.5 w-3.5" />
                {review.helpfulCount > 0
                  ? `Helpful (${review.helpfulCount}) / 有用`
                  : 'Helpful / 有用'}
              </button>
              {depth < maxDepth && (
                <button
                  onClick={() => setShowReplyForm((v) => !v)}
                  className="flex items-center gap-1 text-xs text-gray-400 transition-colors hover:text-indigo-600"
                >
                  <ChatBubbleLeftIcon className="h-3.5 w-3.5" />
                  Reply / 回复
                </button>
              )}
            </div>

            {showReplyForm && (
              <div className="mt-3">
                <WriteForm
                  ratingMode="disabled"
                  parentId={review.pid}
                  onSubmit={handleReplySubmit}
                  onCancel={() => setShowReplyForm(false)}
                />
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Nested replies */}
      {review.replies && review.replies.length > 0 && depth < maxDepth && (
        <div className="mt-2 space-y-2">
          {review.replies.map((child) => (
            <ReviewItem
              key={child.pid}
              review={child}
              depth={depth + 1}
              maxDepth={maxDepth}
              ratingMode={ratingMode}
              onVote={onVote}
              onReply={onReply}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ---- Rating Summary Bar --------------------------------------------------

function RatingSummary({ summary }: { summary: ReviewSummary }) {
  const dist = summary.distribution || {};
  const total = summary.totalCount || 0;

  return (
    <div className="mb-6 flex items-start gap-8">
      {/* Overall score */}
      <div className="flex flex-shrink-0 flex-col items-center gap-1">
        <span className="text-4xl font-bold text-gray-900">
          {summary.averageRating ? summary.averageRating.toFixed(1) : '—'}
        </span>
        {summary.averageRating && (
          <StarRating value={Math.round(summary.averageRating)} readOnly size="sm" />
        )}
        <span className="text-xs text-gray-400">{total} reviews / 条评论</span>
      </div>

      {/* Distribution bars */}
      <div className="flex-1 space-y-1">
        {[5, 4, 3, 2, 1].map((star) => {
          const count = dist[String(star)] || 0;
          const pct = total > 0 ? Math.round((count / total) * 100) : 0;
          return (
            <div key={star} className="flex items-center gap-2">
              <span className="w-4 text-xs text-gray-500">{star}</span>
              <StarIcon className="h-3 w-3 flex-shrink-0 text-amber-400" />
              <div className="h-2 flex-1 overflow-hidden rounded-full bg-gray-100">
                <div
                  className="h-2 rounded-full bg-amber-400 transition-all"
                  style={{ width: `${pct}%` }}
                />
              </div>
              <span className="w-6 text-xs text-gray-400">{count}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ---- Main Component -------------------------------------------------------

interface ReviewSectionProps {
  targetType: string;
  targetId: string;
}

export default function ReviewSection({ targetType, targetId }: ReviewSectionProps) {
  const config = RATING_CONFIG[targetType] ?? DEFAULT_CONFIG;

  const [summary, setSummary] = useState<ReviewSummary | null>(null);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [sort, setSort] = useState<'helpful' | 'newest'>('helpful');
  const [loading, setLoading] = useState(true);
  const [summaryLoading, setSummaryLoading] = useState(true);

  // Build nested tree from flat list
  const buildTree = useCallback((flat: Review[]): Review[] => {
    const map = new Map<string, Review>();
    const roots: Review[] = [];
    flat.forEach((r) => map.set(r.pid, { ...r, replies: [] }));
    map.forEach((r) => {
      if (r.parentId) {
        const parent = map.get(r.parentId);
        if (parent) {
          parent.replies = parent.replies || [];
          parent.replies.push(r);
        } else {
          roots.push(r);
        }
      } else {
        roots.push(r);
      }
    });
    return roots;
  }, []);

  const fetchReviews = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch(
        `/api/reviews?targetType=${encodeURIComponent(targetType)}&targetId=${encodeURIComponent(targetId)}&sort=${sort}`,
      );
      if (res.ok) {
        const json = await res.json();
        const list: Review[] = json.data?.records ?? json.data ?? json ?? [];
        setReviews(buildTree(Array.isArray(list) ? list : []));
      }
    } catch {
      // silently ignore — backend may be unavailable
    } finally {
      setLoading(false);
    }
  }, [targetType, targetId, sort, buildTree]);

  const fetchSummary = useCallback(async () => {
    setSummaryLoading(true);
    try {
      const res = await fetch(
        `/api/reviews/summary?targetType=${encodeURIComponent(targetType)}&targetId=${encodeURIComponent(targetId)}`,
      );
      if (res.ok) {
        const json = await res.json();
        setSummary(json.data ?? json);
      }
    } catch {
      // silently ignore
    } finally {
      setSummaryLoading(false);
    }
  }, [targetType, targetId]);

  useEffect(() => {
    fetchReviews();
  }, [fetchReviews]);

  useEffect(() => {
    fetchSummary();
  }, [fetchSummary]);

  const handleSubmitReview = async (data: {
    content: string;
    rating?: number;
    title?: string;
    parentId?: string;
  }) => {
    const res = await fetch('/api/reviews', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        targetType,
        targetId,
        ...data,
      }),
    });
    if (!res.ok) {
      const json = await res.json().catch(() => ({}));
      throw new Error(json?.message || json?.error || `HTTP ${res.status}`);
    }
    // Refresh both
    await Promise.all([fetchReviews(), fetchSummary()]);
  };

  const handleReply = async (parentId: string, content: string, rating?: number) => {
    await handleSubmitReview({ content, parentId, rating });
  };

  const handleVote = async (pid: string) => {
    try {
      await fetch(`/api/reviews/${encodeURIComponent(pid)}/vote?voteType=HELPFUL`, {
        method: 'post',
      });
      await fetchReviews();
    } catch {
      // silently ignore
    }
  };

  return (
    <div className="mt-6 rounded-lg border border-gray-200 bg-white p-6">
      {/* Section header */}
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold text-gray-900">
          Reviews / 评论
          {summary && summary.totalCount > 0 && (
            <span className="ml-2 text-sm font-normal text-gray-400">({summary.totalCount})</span>
          )}
        </h2>
        <div className="flex items-center gap-1">
          {(['helpful', 'newest'] as const).map((s) => (
            <button
              key={s}
              onClick={() => setSort(s)}
              className={`rounded-full px-3 py-1 text-xs transition-colors ${
                sort === s
                  ? 'bg-indigo-600 text-white'
                  : 'bg-gray-100 text-gray-500 hover:bg-gray-200'
              }`}
            >
              {s === 'helpful' ? 'Most Helpful / 最有用' : 'Newest / 最新'}
            </button>
          ))}
        </div>
      </div>

      {/* Rating summary */}
      {config.ratingMode !== 'disabled' && !summaryLoading && summary && summary.totalCount > 0 && (
        <RatingSummary summary={summary} />
      )}

      {/* Write form */}
      <div className="mb-6 rounded-lg border border-gray-100 bg-gray-50 p-4">
        <h3 className="mb-3 text-sm font-medium text-gray-700">Write a Review / 写评论</h3>
        <WriteForm ratingMode={config.ratingMode} onSubmit={handleSubmitReview} />
      </div>

      {/* Review list */}
      {loading ? (
        <div className="flex justify-center py-8">
          <div className="h-6 w-6 animate-spin rounded-full border-b-2 border-indigo-600" />
        </div>
      ) : reviews.length === 0 ? (
        <div className="py-12 text-center text-gray-400">
          <ChatBubbleLeftIcon className="mx-auto mb-2 h-10 w-10 text-gray-200" />
          <p className="text-sm">No reviews yet / 暂无评论</p>
          <p className="mt-1 text-xs">Be the first to leave a review! / 抢先留下第一条评论！</p>
        </div>
      ) : (
        <div className="space-y-4 divide-y divide-gray-100">
          {reviews.map((review) => (
            <div key={review.pid} className="pt-4 first:pt-0">
              <ReviewItem
                review={review}
                depth={0}
                maxDepth={config.maxDepth}
                ratingMode={config.ratingMode}
                onVote={handleVote}
                onReply={handleReply}
              />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
