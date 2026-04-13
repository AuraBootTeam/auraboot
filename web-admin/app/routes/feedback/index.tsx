import { useState } from 'react';
import { useLoaderData, useNavigate, Form, useActionData, useNavigation } from 'react-router';
import type { LoaderFunctionArgs, ActionFunctionArgs } from 'react-router';
import { requireAuth } from '~/shared/services/session';
import {
  listFeedback,
  createFeedback,
  toggleVote,
  updateFeedbackStatus,
  deleteFeedback,
} from '~/shared/services/feedback';
import type {
  FeedbackItem,
  FeedbackType,
  FeedbackStatus,
  FeedbackPriority,
} from '~/types/feedback';

// --- Loader ---
export async function loader({ request }: LoaderFunctionArgs) {
  await requireAuth(request);
  const url = new URL(request.url);
  const type = url.searchParams.get('type') || undefined;
  const status = url.searchParams.get('status') || undefined;
  const sortBy = url.searchParams.get('sortBy') || 'voteCount';
  const sortOrder = url.searchParams.get('sortOrder') || 'desc';
  const pageNum = parseInt(url.searchParams.get('pageNum') || '1');

  const data = await listFeedback(request, {
    pageNum,
    pageSize: 20,
    type,
    status,
    sortBy,
    sortOrder,
  });

  return {
    feedbackList: data.records,
    total: data.total,
    pageNum,
    type,
    status,
    sortBy,
    sortOrder,
  };
}

// --- Action ---
export async function action({ request }: ActionFunctionArgs) {
  const formData = await request.formData();
  const intent = formData.get('intent') as string;

  try {
    if (intent === 'create') {
      const fb = await createFeedback(request, {
        type: formData.get('type') as FeedbackType,
        title: formData.get('title') as string,
        description: (formData.get('description') as string) || undefined,
        priority: (formData.get('priority') as FeedbackPriority) || undefined,
        metadata: (formData.get('metadata') as string) || undefined,
      });
      return { success: true, action: 'create', data: fb };
    }

    if (intent === 'vote') {
      const feedbackId = parseInt(formData.get('feedbackId') as string);
      const voted = await toggleVote(request, feedbackId);
      return { success: true, action: 'vote', voted };
    }

    if (intent === 'updateStatus') {
      const feedbackId = parseInt(formData.get('feedbackId') as string);
      const newStatus = formData.get('status') as string;
      await updateFeedbackStatus(request, feedbackId, newStatus);
      return { success: true, action: 'updateStatus' };
    }

    if (intent === 'delete') {
      const feedbackId = parseInt(formData.get('feedbackId') as string);
      await deleteFeedback(request, feedbackId);
      return { success: true, action: 'delete' };
    }

    return { success: false, error: 'Unknown intent' };
  } catch (error) {
    return {
      success: false,
      error: error instanceof Error ? error.message : 'Operation failed',
    };
  }
}

// --- Constants ---
const TYPE_LABELS: Record<FeedbackType, { label: string; color: string; icon: string }> = {
  bug: { label: 'Bug', color: 'bg-red-100 text-red-800', icon: '🐛' },
  feature: { label: 'Feature', color: 'bg-purple-100 text-purple-800', icon: '✨' },
  improvement: { label: 'Improvement', color: 'bg-blue-100 text-blue-800', icon: '💡' },
  question: { label: 'Question', color: 'bg-yellow-100 text-yellow-800', icon: '❓' },
};

const STATUS_LABELS: Record<FeedbackStatus, { label: string; color: string }> = {
  open: { label: 'Open', color: 'bg-green-100 text-green-800' },
  acknowledged: { label: 'Acknowledged', color: 'bg-blue-100 text-blue-800' },
  in_progress: { label: 'In Progress', color: 'bg-yellow-100 text-yellow-800' },
  resolved: { label: 'Resolved', color: 'bg-gray-100 text-gray-800' },
  closed: { label: 'Closed', color: 'bg-gray-200 text-gray-600' },
};

const PRIORITY_LABELS: Record<FeedbackPriority, { label: string; color: string }> = {
  low: { label: 'Low', color: 'text-gray-500' },
  medium: { label: 'Medium', color: 'text-blue-600' },
  high: { label: 'High', color: 'text-orange-600' },
  critical: { label: 'Critical', color: 'text-red-600 font-bold' },
};

// --- Component ---
export default function FeedbackPage() {
  const {
    feedbackList,
    total,
    pageNum,
    type: activeType,
    status: activeStatus,
    sortBy,
    sortOrder,
  } = useLoaderData<typeof loader>();
  const actionData = useActionData<typeof action>();
  const navigation = useNavigation();
  const navigate = useNavigate();
  const [showCreateModal, setShowCreateModal] = useState(false);

  const isSubmitting = navigation.state === 'submitting';

  const handleFilter = (key: string, value: string | null) => {
    const params = new URLSearchParams();
    if (key === 'type' && value) params.set('type', value);
    else if (activeType) params.set('type', activeType);

    if (key === 'status' && value) params.set('status', value);
    else if (activeStatus) params.set('status', activeStatus);

    if (key === 'sortBy' && value) {
      params.set('sortBy', value);
      params.set('sortOrder', value === 'voteCount' ? 'desc' : 'desc');
    } else {
      if (sortBy) params.set('sortBy', sortBy);
      if (sortOrder) params.set('sortOrder', sortOrder);
    }

    // Reset type/status filter if clicking active one
    if (key === 'type' && value === activeType) params.delete('type');
    if (key === 'status' && value === activeStatus) params.delete('status');

    navigate(`/feedback?${params.toString()}`);
  };

  return (
    <div className="mx-auto max-w-4xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Feedback & Feature Requests</h1>
          <p className="mt-1 text-sm text-gray-500">
            Share ideas, report bugs, and vote on what matters most.
          </p>
        </div>
        <button
          onClick={() => setShowCreateModal(true)}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-blue-700"
        >
          + New Feedback
        </button>
      </div>

      {/* Error */}
      {actionData && !actionData.success && (
        <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-red-700">
          {actionData.error}
        </div>
      )}

      {/* Filters */}
      <div className="mb-4 flex flex-wrap gap-2">
        <span className="mr-1 self-center text-sm font-medium text-gray-500">Type:</span>
        {(Object.keys(TYPE_LABELS) as FeedbackType[]).map((t) => (
          <button
            key={t}
            onClick={() => handleFilter('type', t)}
            className={`rounded-full px-3 py-1 text-xs font-medium transition ${
              activeType === t
                ? 'ring-2 ring-blue-500 ' + TYPE_LABELS[t].color
                : TYPE_LABELS[t].color + ' opacity-60 hover:opacity-100'
            }`}
          >
            {TYPE_LABELS[t].icon} {TYPE_LABELS[t].label}
          </button>
        ))}

        <span className="mr-1 ml-4 self-center text-sm font-medium text-gray-500">Status:</span>
        {(Object.keys(STATUS_LABELS) as FeedbackStatus[]).map((s) => (
          <button
            key={s}
            onClick={() => handleFilter('status', s)}
            className={`rounded-full px-3 py-1 text-xs font-medium transition ${
              activeStatus === s
                ? 'ring-2 ring-blue-500 ' + STATUS_LABELS[s].color
                : STATUS_LABELS[s].color + ' opacity-60 hover:opacity-100'
            }`}
          >
            {STATUS_LABELS[s].label}
          </button>
        ))}
      </div>

      {/* Sort */}
      <div className="mb-6 flex gap-2">
        <span className="mr-1 self-center text-sm text-gray-500">Sort by:</span>
        <button
          onClick={() => handleFilter('sortBy', 'voteCount')}
          className={`rounded px-3 py-1 text-xs font-medium ${
            sortBy === 'voteCount'
              ? 'bg-blue-100 text-blue-800'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          Most Voted
        </button>
        <button
          onClick={() => handleFilter('sortBy', 'createdAt')}
          className={`rounded px-3 py-1 text-xs font-medium ${
            sortBy === 'createdAt' || !sortBy
              ? 'bg-blue-100 text-blue-800'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          Newest
        </button>
      </div>

      {/* Feedback List */}
      <div className="space-y-3">
        {feedbackList.length === 0 && (
          <div className="py-12 text-center text-gray-400">
            No feedback found. Be the first to submit!
          </div>
        )}
        {feedbackList.map((item: FeedbackItem) => (
          <FeedbackCard key={item.id} item={item} isSubmitting={isSubmitting} />
        ))}
      </div>

      {/* Pagination */}
      {total > 20 && (
        <div className="mt-6 flex justify-center gap-2">
          {pageNum > 1 && (
            <button
              onClick={() => {
                const params = new URLSearchParams(window.location.search);
                params.set('pageNum', String(pageNum - 1));
                navigate(`/feedback?${params.toString()}`);
              }}
              className="rounded bg-gray-100 px-3 py-1 text-sm text-gray-600 hover:bg-gray-200"
            >
              Previous
            </button>
          )}
          <span className="px-3 py-1 text-sm text-gray-500">
            Page {pageNum} of {Math.ceil(total / 20)}
          </span>
          {pageNum * 20 < total && (
            <button
              onClick={() => {
                const params = new URLSearchParams(window.location.search);
                params.set('pageNum', String(pageNum + 1));
                navigate(`/feedback?${params.toString()}`);
              }}
              className="rounded bg-gray-100 px-3 py-1 text-sm text-gray-600 hover:bg-gray-200"
            >
              Next
            </button>
          )}
        </div>
      )}

      {/* Create Modal */}
      {showCreateModal && (
        <CreateFeedbackModal
          onClose={() => setShowCreateModal(false)}
          isSubmitting={isSubmitting}
        />
      )}
    </div>
  );
}

// --- Feedback Card ---
function FeedbackCard({ item, isSubmitting }: { item: FeedbackItem; isSubmitting: boolean }) {
  const typeInfo = TYPE_LABELS[item.type] || TYPE_LABELS.question;
  const statusInfo = STATUS_LABELS[item.status] || STATUS_LABELS.open;
  const priorityInfo = PRIORITY_LABELS[item.priority] || PRIORITY_LABELS.medium;

  return (
    <div className="flex gap-4 rounded-lg border border-gray-200 bg-white p-4 transition hover:shadow-sm">
      {/* Vote Column */}
      <Form method="post" className="flex-shrink-0">
        <input type="hidden" name="intent" value="vote" />
        <input type="hidden" name="feedbackId" value={item.id} />
        <button
          type="submit"
          disabled={isSubmitting}
          className={`flex flex-col items-center rounded-lg border px-3 py-2 transition ${
            item.votedByCurrentUser
              ? 'border-blue-300 bg-blue-50 text-blue-600'
              : 'border-gray-200 text-gray-400 hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600'
          }`}
        >
          <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 15l7-7 7 7" />
          </svg>
          <span className="text-sm font-bold">{item.voteCount}</span>
        </button>
      </Form>

      {/* Content */}
      <div className="min-w-0 flex-1">
        <div className="mb-1 flex items-center gap-2">
          <span className={`rounded px-2 py-0.5 text-xs font-medium ${typeInfo.color}`}>
            {typeInfo.icon} {typeInfo.label}
          </span>
          <span className={`rounded px-2 py-0.5 text-xs font-medium ${statusInfo.color}`}>
            {statusInfo.label}
          </span>
          <span className={`text-xs ${priorityInfo.color}`}>{priorityInfo.label}</span>
        </div>
        <h3 className="truncate text-base font-semibold text-gray-900">{item.title}</h3>
        {item.description && (
          <p className="mt-1 line-clamp-2 text-sm text-gray-500">{item.description}</p>
        )}
        <div className="mt-2 flex items-center gap-4 text-xs text-gray-400">
          <span>by {item.userName || 'Anonymous'}</span>
          <span>{new Date(item.createdAt).toLocaleDateString()}</span>
          <span>
            {item.commentCount} comment{item.commentCount !== 1 ? 's' : ''}
          </span>
        </div>
      </div>
    </div>
  );
}

// --- Create Feedback Modal ---
function CreateFeedbackModal({
  onClose,
  isSubmitting,
}: {
  onClose: () => void;
  isSubmitting: boolean;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="mx-4 w-full max-w-lg rounded-xl bg-white p-6 shadow-xl">
        <h2 className="mb-4 text-lg font-bold">Submit Feedback</h2>
        <Form method="post" onSubmit={() => setTimeout(onClose, 100)}>
          <input type="hidden" name="intent" value="create" />

          {/* Type */}
          <label className="mb-1 block text-sm font-medium text-gray-700">Type</label>
          <select
            name="type"
            required
            className="mb-3 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
          >
            <option value="feature">Feature Request</option>
            <option value="bug">Bug Report</option>
            <option value="improvement">Improvement</option>
            <option value="question">Question</option>
          </select>

          {/* Title */}
          <label className="mb-1 block text-sm font-medium text-gray-700">Title</label>
          <input
            name="title"
            required
            maxLength={200}
            placeholder="Brief summary..."
            className="mb-3 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
          />

          {/* Description */}
          <label className="mb-1 block text-sm font-medium text-gray-700">Description</label>
          <textarea
            name="description"
            rows={4}
            placeholder="Provide more details..."
            className="mb-3 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
          />

          {/* Priority */}
          <label className="mb-1 block text-sm font-medium text-gray-700">Priority</label>
          <select
            name="priority"
            className="mb-4 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
          >
            <option value="medium">Medium</option>
            <option value="low">Low</option>
            <option value="high">High</option>
            <option value="critical">Critical</option>
          </select>

          {/* Browser metadata (hidden, auto-populated) */}
          <input
            type="hidden"
            name="metadata"
            value={
              typeof window !== 'undefined'
                ? JSON.stringify({
                    pageUrl: window.location.href,
                    userAgent: navigator.userAgent,
                    screenSize: `${window.screen.width}x${window.screen.height}`,
                  })
                : ''
            }
          />

          {/* Actions */}
          <div className="flex justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg px-4 py-2 text-sm text-gray-600 transition hover:bg-gray-100"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-blue-700 disabled:opacity-50"
            >
              {isSubmitting ? 'Submitting...' : 'Submit'}
            </button>
          </div>
        </Form>
      </div>
    </div>
  );
}
