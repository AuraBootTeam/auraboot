/**
 * CRM Lead Merge Queue
 *
 * Review and action duplicate lead merge candidates:
 * - Filter tabs: All, Pending, Merged, Rejected
 * - Table with match score progress bar, status badge, created date
 * - Click row to open detail panel with side-by-side comparison
 * - Actions: Merge, Reject, Create New
 */

import { useState, useEffect, useCallback } from 'react';
import {
  CheckCircleIcon,
  XCircleIcon,
  PlusCircleIcon,
  FunnelIcon,
  ArrowPathIcon,
  XMarkIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';

// ─── Types ────────────────────────────────────────────────────────────────────

type MergeStatus = 'pending' | 'merged' | 'rejected' | 'created_new';

interface MergeQueueItem {
  id: string;
  inboundCompanyName?: string;
  inboundName?: string;
  candidateLeadName?: string;
  candidateLeadId?: string;
  matchScore: number;
  status: MergeStatus;
  createdAt: string;
}

interface MergeQueueDetail extends MergeQueueItem {
  inboundData: Record<string, unknown>;
  candidateData: Record<string, unknown>;
}

type FilterTab = 'all' | MergeStatus;

const TABS: { key: FilterTab; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'pending', label: 'Pending' },
  { key: 'merged', label: 'Merged' },
  { key: 'rejected', label: 'Rejected' },
];

const STATUS_CONFIG: Record<MergeStatus, { label: string; bg: string; text: string }> = {
  pending: { label: 'Pending', bg: 'bg-yellow-100', text: 'text-yellow-700' },
  merged: { label: 'Merged', bg: 'bg-green-100', text: 'text-green-700' },
  rejected: { label: 'Rejected', bg: 'bg-red-100', text: 'text-red-700' },
  created_new: { label: 'Created New', bg: 'bg-blue-100', text: 'text-blue-700' },
};

// ─── API helpers ──────────────────────────────────────────────────────────────

async function apiFetch<T = unknown>(path: string, opts?: RequestInit): Promise<T> {
  const jwt = localStorage.getItem('jwt');
  const res = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}),
    },
    ...opts,
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  if (json.code !== undefined && json.code != 0 && json.code != 200) {
    throw new Error(json.message ?? 'API error');
  }
  return json.data as T;
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function MergeQueuePage() {
  const { showToast } = useToastContext();
  const [items, setItems] = useState<MergeQueueItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<FilterTab>('all');
  const [selectedItem, setSelectedItem] = useState<MergeQueueDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [confirmAction, setConfirmAction] = useState<{
    type: 'merge' | 'reject' | 'create-new';
    item: MergeQueueDetail;
  } | null>(null);

  const load = useCallback(
    async (status?: FilterTab) => {
      setLoading(true);
      try {
        const qs = status && status !== 'all' ? `?status=${status}` : '';
        const data = await apiFetch<MergeQueueItem[]>(`/api/crm/merge-queue${qs}`);
        setItems(data ?? []);
      } catch (e: unknown) {
        showToast((e instanceof Error ? e.message : null) ?? 'Failed to load merge queue', 'error');
        setItems([]);
      } finally {
        setLoading(false);
      }
    },
    [showToast],
  );

  useEffect(() => {
    load(activeTab);
  }, [load, activeTab]);

  const openDetail = async (item: MergeQueueItem) => {
    setDetailLoading(true);
    setSelectedItem(null);
    try {
      const detail = await apiFetch<MergeQueueDetail>(`/api/crm/merge-queue/${item.id}`);
      setSelectedItem(detail);
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Failed to load detail', 'error');
    } finally {
      setDetailLoading(false);
    }
  };

  const handleAction = async (type: 'merge' | 'reject' | 'create-new') => {
    if (!selectedItem) return;
    setActionLoading(type);
    try {
      await apiFetch(`/api/crm/merge-queue/${selectedItem.id}/${type}`, { method: 'post' });
      const successMap = {
        merge: 'Lead merged successfully',
        reject: 'Duplicate rejected',
        'create-new': 'New lead created',
      };
      showToast(successMap[type], 'success');
      setSelectedItem(null);
      setConfirmAction(null);
      load(activeTab);
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Action failed', 'error');
    } finally {
      setActionLoading(null);
    }
  };

  const filteredItems =
    activeTab === 'all' ? items : items.filter((item) => item.status === activeTab);

  return (
    <div className="mx-auto max-w-5xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Lead Merge Queue</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Review and action potential duplicate leads detected during ingestion.
          </p>
        </div>
        <button
          onClick={() => load(activeTab)}
          className="flex items-center gap-2 rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-600 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300"
        >
          <ArrowPathIcon className="h-4 w-4" />
          Refresh
        </button>
      </div>

      {/* Filter Tabs */}
      <div className="mb-4 flex gap-1 border-b border-gray-200 dark:border-gray-700">
        {TABS.map((tab) => {
          const count =
            tab.key === 'all' ? items.length : items.filter((i) => i.status === tab.key).length;
          return (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className={`flex items-center gap-1.5 rounded-t-lg px-4 py-2.5 text-sm font-medium transition-colors ${
                activeTab === tab.key
                  ? 'border-b-2 border-blue-600 text-blue-600 dark:text-blue-400'
                  : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
              }`}
              data-testid={`merge-tab-${tab.key.toLowerCase()}`}
            >
              {tab.label}
              {count > 0 && (
                <span
                  className={`rounded-full px-1.5 py-0.5 text-xs ${
                    activeTab === tab.key
                      ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'
                      : 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400'
                  }`}
                >
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {/* Content */}
      {loading ? (
        <div className="py-12 text-center text-gray-400">Loading merge queue...</div>
      ) : filteredItems.length === 0 ? (
        <MergeQueueEmptyState activeTab={activeTab} />
      ) : (
        <div
          className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800"
          data-testid="merge-queue-list"
        >
          <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
            <thead className="bg-gray-50 dark:bg-gray-900/30">
              <tr>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Inbound Lead
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Match Candidate
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Match Score
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Status
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Created
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {filteredItems.map((item) => {
                const statusCfg = STATUS_CONFIG[item.status];
                const scoreColor =
                  item.matchScore >= 80
                    ? 'bg-green-500'
                    : item.matchScore >= 60
                      ? 'bg-yellow-400'
                      : 'bg-red-400';

                return (
                  <tr
                    key={item.id}
                    className="cursor-pointer hover:bg-blue-50 dark:hover:bg-blue-900/10"
                    onClick={() => openDetail(item)}
                    data-testid="merge-queue-row"
                  >
                    <td className="px-4 py-3">
                      <div className="text-sm font-medium text-gray-900 dark:text-white">
                        {item.inboundName ?? '(Unknown)'}
                      </div>
                      {item.inboundCompanyName && (
                        <div className="text-xs text-gray-400">{item.inboundCompanyName}</div>
                      )}
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                      {item.candidateLeadName ?? '—'}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <div className="h-2 w-24 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                          <div
                            className={`h-full rounded-full transition-all ${scoreColor}`}
                            style={{ width: `${item.matchScore}%` }}
                          />
                        </div>
                        <span className="text-xs font-medium text-gray-700 dark:text-gray-300">
                          {item.matchScore}%
                        </span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium dark:opacity-90 ${statusCfg.bg} ${statusCfg.text}`}
                      >
                        {statusCfg.label}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                      {new Date(item.createdAt).toLocaleDateString()}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Detail Panel */}
      {(detailLoading || selectedItem) && (
        <MergeDetailPanel
          item={selectedItem}
          loading={detailLoading}
          actionLoading={actionLoading}
          onClose={() => setSelectedItem(null)}
          onAction={(type) => {
            if (selectedItem) {
              setConfirmAction({ type, item: selectedItem });
            }
          }}
        />
      )}

      {/* Confirm Modal */}
      {confirmAction && (
        <ConfirmActionModal
          action={confirmAction.type}
          itemName={confirmAction.item.inboundName ?? 'this lead'}
          loading={actionLoading === confirmAction.type}
          onConfirm={() => handleAction(confirmAction.type)}
          onClose={() => setConfirmAction(null)}
        />
      )}
    </div>
  );
}

// ─── Empty State ──────────────────────────────────────────────────────────────

function MergeQueueEmptyState({ activeTab }: { activeTab: FilterTab }) {
  return (
    <div className="py-16 text-center text-gray-400" data-testid="merge-queue-empty">
      <FunnelIcon className="mx-auto mb-3 h-12 w-12 opacity-30" />
      <p className="mb-1 font-medium text-gray-500 dark:text-gray-400">
        {activeTab === 'all' ? 'No merge candidates found' : `No ${activeTab.toLowerCase()} items`}
      </p>
      <p className="text-sm">
        {activeTab === 'all'
          ? 'Potential duplicates will appear here when new leads arrive via inbound channels.'
          : `Switch to "All" to see items with other statuses.`}
      </p>
    </div>
  );
}

// ─── Merge Detail Panel ───────────────────────────────────────────────────────

function MergeDetailPanel({
  item,
  loading,
  actionLoading,
  onClose,
  onAction,
}: {
  item: MergeQueueDetail | null;
  loading: boolean;
  actionLoading: string | null;
  onClose: () => void;
  onAction: (type: 'merge' | 'reject' | 'create-new') => void;
}) {
  const isPending = item?.status === 'pending';

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-end bg-black/40 backdrop-blur-sm sm:items-start sm:pt-16">
      <div
        className="relative mx-auto flex h-full w-full max-w-4xl flex-col overflow-hidden bg-white shadow-2xl sm:max-h-[85vh] sm:rounded-2xl dark:bg-gray-800"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4 dark:border-gray-700">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
            Merge Review
            {item && (
              <span className="ml-2 text-sm font-normal text-gray-400">
                ({item.matchScore}% match)
              </span>
            )}
          </h3>
          <button
            onClick={onClose}
            className="rounded-lg p-1.5 hover:bg-gray-100 dark:hover:bg-gray-700"
          >
            <XMarkIcon className="h-5 w-5 text-gray-400" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-6">
          {loading ? (
            <div className="flex h-32 items-center justify-center text-gray-400">
              Loading details...
            </div>
          ) : !item ? null : (
            <div className="grid grid-cols-2 gap-6">
              {/* Inbound Data */}
              <div>
                <div className="mb-3 flex items-center gap-2">
                  <span className="rounded-full bg-blue-100 px-2.5 py-0.5 text-xs font-medium text-blue-700 dark:bg-blue-900/30 dark:text-blue-400">
                    Inbound (New)
                  </span>
                </div>
                <div className="space-y-2">
                  {Object.entries(item.inboundData ?? {}).map(([key, value]) => (
                    <DataRow key={key} label={key} value={value} />
                  ))}
                  {Object.keys(item.inboundData ?? {}).length === 0 && (
                    <p className="text-sm text-gray-400 italic">No data available</p>
                  )}
                </div>
              </div>

              {/* Existing Lead Data */}
              <div>
                <div className="mb-3 flex items-center gap-2">
                  <span className="rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-medium text-gray-600 dark:bg-gray-700 dark:text-gray-400">
                    Existing Lead
                  </span>
                  {item.candidateLeadName && (
                    <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
                      {item.candidateLeadName}
                    </span>
                  )}
                </div>
                <div className="space-y-2">
                  {Object.entries(item.candidateData ?? {}).map(([key, value]) => (
                    <DataRow
                      key={key}
                      label={key}
                      value={value}
                      highlight={
                        item.inboundData[key] !== undefined &&
                        String(item.inboundData[key]) === String(value)
                      }
                    />
                  ))}
                  {Object.keys(item.candidateData ?? {}).length === 0 && (
                    <p className="text-sm text-gray-400 italic">No data available</p>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Actions */}
        {isPending && item && (
          <div className="flex items-center justify-end gap-3 border-t border-gray-100 bg-gray-50 px-6 py-4 dark:border-gray-700 dark:bg-gray-900/30">
            <button
              onClick={() => onAction('reject')}
              disabled={!!actionLoading}
              className="flex items-center gap-2 rounded-lg border border-red-300 bg-white px-4 py-2 text-sm font-medium text-red-600 transition-colors hover:bg-red-50 disabled:opacity-50 dark:border-red-700 dark:bg-transparent dark:hover:bg-red-900/20"
              data-testid="merge-reject-btn"
            >
              <XCircleIcon className="h-4 w-4" />
              Reject
            </button>
            <button
              onClick={() => onAction('create-new')}
              disabled={!!actionLoading}
              className="flex items-center gap-2 rounded-lg border border-blue-300 bg-white px-4 py-2 text-sm font-medium text-blue-600 transition-colors hover:bg-blue-50 disabled:opacity-50 dark:border-blue-700 dark:bg-transparent dark:hover:bg-blue-900/20"
              data-testid="merge-create-new-btn"
            >
              <PlusCircleIcon className="h-4 w-4" />
              Create New Lead
            </button>
            <button
              onClick={() => onAction('merge')}
              disabled={!!actionLoading}
              className="flex items-center gap-2 rounded-lg bg-green-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-green-700 disabled:opacity-50"
              data-testid="merge-confirm-btn"
            >
              <CheckCircleIcon className="h-4 w-4" />
              Merge
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Data Row ─────────────────────────────────────────────────────────────────

function DataRow({
  label,
  value,
  highlight = false,
}: {
  label: string;
  value: unknown;
  highlight?: boolean;
}) {
  const displayValue = value === null || value === undefined ? '—' : String(value);
  return (
    <div
      className={`flex rounded-lg px-3 py-2 text-sm ${
        highlight ? 'bg-green-50 dark:bg-green-900/20' : 'bg-gray-50 dark:bg-gray-700/30'
      }`}
    >
      <span className="w-32 flex-shrink-0 font-medium text-gray-500 capitalize dark:text-gray-400">
        {label.replace(/_/g, ' ')}
      </span>
      <span
        className={`flex-1 ${highlight ? 'text-green-700 dark:text-green-400' : 'text-gray-700 dark:text-gray-300'}`}
      >
        {displayValue}
      </span>
    </div>
  );
}

// ─── Confirm Modal ────────────────────────────────────────────────────────────

function ConfirmActionModal({
  action,
  itemName,
  loading,
  onConfirm,
  onClose,
}: {
  action: 'merge' | 'reject' | 'create-new';
  itemName: string;
  loading: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const config = {
    merge: {
      title: 'Confirm Merge',
      message: `Merge the inbound data into the existing lead "${itemName}"? This will update the existing record.`,
      buttonLabel: 'Merge Lead',
      buttonClass: 'bg-green-600 hover:bg-green-700',
    },
    reject: {
      title: 'Reject Duplicate',
      message: `Mark this item as rejected and ignore the inbound data for "${itemName}"?`,
      buttonLabel: 'Reject',
      buttonClass: 'bg-red-600 hover:bg-red-700',
    },
    'create-new': {
      title: 'Create New Lead',
      message: `Create a new lead from the inbound data for "${itemName}" without merging?`,
      buttonLabel: 'Create New Lead',
      buttonClass: 'bg-blue-600 hover:bg-blue-700',
    },
  };

  const cfg = config[action];

  return (
    <div className="fixed inset-0 z-60 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="mx-4 w-full max-w-md overflow-hidden rounded-2xl bg-white shadow-2xl dark:bg-gray-800">
        <div className="border-b border-gray-100 px-6 py-4 dark:border-gray-700">
          <h3 className="text-base font-semibold text-gray-900 dark:text-white">{cfg.title}</h3>
        </div>
        <div className="px-6 py-5">
          <p className="text-sm text-gray-600 dark:text-gray-300">{cfg.message}</p>
        </div>
        <div className="flex items-center justify-end gap-3 border-t border-gray-100 bg-gray-50 px-6 py-4 dark:border-gray-700 dark:bg-gray-900/30">
          <button
            onClick={onClose}
            disabled={loading}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            disabled={loading}
            className={`rounded-lg px-5 py-2 text-sm font-medium text-white disabled:opacity-50 ${cfg.buttonClass}`}
            data-testid="confirm-action-btn"
          >
            {loading ? 'Processing...' : cfg.buttonLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
