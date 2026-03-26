/**
 * EmailSequenceListPage — manage email sequences.
 *
 * Features:
 *  - Table of sequences with status badges
 *  - "Create Sequence" inline form
 *  - Click row → navigate to sequence editor
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import {
  EnvelopeIcon,
  PlusIcon,
  ChevronRightIcon,
} from '@heroicons/react/24/outline';
import {
  listSequences,
  createSequence,
  type EmailSequence,
} from '~/services/emailService';
import { useToastContext } from '~/contexts/ToastContext';

function statusBadge(status: string) {
  const styles: Record<string, string> = {
    draft: 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
    active: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    paused: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
    archived: 'bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-500',
  };
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-xs font-medium capitalize ${styles[status] || styles.draft}`}
    >
      {status}
    </span>
  );
}

function timeAgo(dateStr: string): string {
  const d = new Date(dateStr);
  return d.toLocaleDateString();
}

export default function EmailSequenceListPage() {
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [sequences, setSequences] = useState<EmailSequence[]>([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [newName, setNewName] = useState('');
  const [newDesc, setNewDesc] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setSequences(await listSequences());
    } catch {
      showErrorToast('Failed to load sequences');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newName.trim()) return;
    setCreating(true);
    try {
      const seq = await createSequence({ name: newName.trim(), description: newDesc.trim() || undefined });
      if (seq) {
        showSuccessToast('Sequence created');
        setShowForm(false);
        setNewName('');
        setNewDesc('');
        navigate(`/email-sequence/${seq.id}`);
      } else {
        showErrorToast('Failed to create sequence');
      }
    } catch {
      showErrorToast('Failed to create sequence');
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="mx-auto max-w-4xl px-4 py-6" data-testid="email-sequence-list-page">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <EnvelopeIcon className="h-7 w-7 text-gray-700 dark:text-gray-300" />
          <div>
            <h1 className="text-xl font-semibold text-gray-900 dark:text-white">
              Email Sequences
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400">
              Automated email drip campaigns
            </p>
          </div>
        </div>
        <button
          onClick={() => setShowForm((v) => !v)}
          data-testid="create-sequence-btn"
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
        >
          <PlusIcon className="h-4 w-4" />
          New Sequence
        </button>
      </div>

      {/* Create form */}
      {showForm && (
        <div className="mb-6 rounded-xl border border-blue-200 bg-blue-50 p-5 dark:border-blue-800/50 dark:bg-blue-900/10">
          <h2 className="mb-4 text-sm font-semibold text-gray-900 dark:text-white">
            Create New Sequence
          </h2>
          <form onSubmit={handleCreate} className="space-y-3">
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                Name <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder="e.g. Lead Nurture Sequence"
                data-testid="sequence-name-input"
                required
                className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                Description
              </label>
              <input
                type="text"
                value={newDesc}
                onChange={(e) => setNewDesc(e.target.value)}
                placeholder="Optional description"
                data-testid="sequence-desc-input"
                className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              />
            </div>
            <div className="flex gap-2">
              <button
                type="submit"
                disabled={creating || !newName.trim()}
                className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
              >
                {creating ? (
                  <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                ) : null}
                Create
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowForm(false);
                  setNewName('');
                  setNewDesc('');
                }}
                className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300"
              >
                Cancel
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Sequence list */}
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      ) : sequences.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-300 py-16 dark:border-gray-600"
          data-testid="sequence-empty-state"
        >
          <EnvelopeIcon className="mb-3 h-10 w-10 text-gray-300 dark:text-gray-600" />
          <p className="mb-1 text-sm font-medium text-gray-600 dark:text-gray-400">
            No sequences yet
          </p>
          <p className="mb-4 text-xs text-gray-400 dark:text-gray-500">
            Create your first email sequence to automate outreach
          </p>
          <button
            onClick={() => setShowForm(true)}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            <PlusIcon className="h-4 w-4" />
            New Sequence
          </button>
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <table className="w-full text-sm" data-testid="sequence-table">
            <thead>
              <tr className="border-b border-gray-200 dark:border-gray-700">
                <th className="px-5 py-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
                  Name
                </th>
                <th className="px-5 py-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
                  Status
                </th>
                <th className="px-5 py-3 text-left text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
                  Created
                </th>
                <th className="px-5 py-3" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
              {sequences.map((seq) => (
                <tr
                  key={seq.id}
                  data-testid={`sequence-row-${seq.id}`}
                  className="cursor-pointer transition-colors hover:bg-gray-50 dark:hover:bg-gray-750"
                  onClick={() => navigate(`/email-sequence/${seq.id}`)}
                >
                  <td className="px-5 py-4">
                    <p className="font-medium text-gray-900 dark:text-white">{seq.name}</p>
                    {seq.description && (
                      <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400 truncate max-w-xs">
                        {seq.description}
                      </p>
                    )}
                  </td>
                  <td className="px-5 py-4">{statusBadge(seq.status)}</td>
                  <td className="px-5 py-4 text-gray-500 dark:text-gray-400">
                    {timeAgo(seq.createdAt)}
                  </td>
                  <td className="px-5 py-4 text-right">
                    <ChevronRightIcon className="inline h-4 w-4 text-gray-400" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
