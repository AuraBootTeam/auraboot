/**
 * CRM Web Forms List Page
 *
 * Manage web forms for lead capture:
 * - List: name, parent channel, field count, enabled status, created date
 * - Create: modal to name + select WEB_FORM channel, then redirect to editor
 * - Delete with confirmation
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import {
  PlusIcon,
  PencilIcon,
  TrashIcon,
  DocumentTextIcon,
  XMarkIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';

// ─── Types ────────────────────────────────────────────────────────────────────

interface WebForm {
  pid: string;
  name: string;
  channelPid: string;
  channelName?: string;
  fieldCount: number;
  enabled: boolean;
  createdAt: string;
}

interface InboundChannel {
  pid: string;
  name: string;
  channelType: string;
}

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

export default function WebFormsPage() {
  const navigate = useNavigate();
  const { showToast } = useToastContext();

  const [forms, setForms] = useState<WebForm[]>([]);
  const [channels, setChannels] = useState<InboundChannel[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [formsData, channelsData] = await Promise.all([
        apiFetch<WebForm[]>('/api/crm/web-forms?channelPid=all'),
        apiFetch<InboundChannel[]>('/api/crm/inbound-channels'),
      ]);
      setForms(formsData ?? []);
      setChannels((channelsData ?? []).filter((c) => c.channelType === 'web_form'));
    } catch {
      setForms([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleDelete = async (form: WebForm) => {
    if (!confirm(`Delete form "${form.name}"? This cannot be undone.`)) return;
    try {
      await apiFetch(`/api/crm/web-forms/${form.pid}`, { method: 'delete' });
      setForms((prev) => prev.filter((f) => f.pid !== form.pid));
      showToast('Form deleted', 'success');
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Failed to delete form', 'error');
    }
  };

  const handleCreated = (newForm: WebForm) => {
    setShowCreate(false);
    navigate(`/crm/settings/web-form-editor/${newForm.pid}`);
  };

  return (
    <div className="mx-auto max-w-5xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Web Forms</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Build embeddable forms that capture leads directly from your website.
          </p>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
          data-testid="webform-create-btn"
        >
          <PlusIcon className="h-4 w-4" />
          New Form
        </button>
      </div>

      {/* Content */}
      {loading ? (
        <div className="py-12 text-center text-gray-400">Loading forms...</div>
      ) : forms.length === 0 ? (
        <FormsEmptyState onAdd={() => setShowCreate(true)} />
      ) : (
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
            <thead className="bg-gray-50 dark:bg-gray-900/30">
              <tr>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Name
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Channel
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Fields
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Status
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Created
                </th>
                <th className="px-4 py-3 text-right text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody
              className="divide-y divide-gray-200 dark:divide-gray-700"
              data-testid="webform-list"
            >
              {forms.map((wf) => (
                <tr
                  key={wf.pid}
                  className="hover:bg-gray-50 dark:hover:bg-gray-700/30"
                  data-testid="webform-row"
                >
                  <td className="px-4 py-3 text-sm font-medium text-gray-900 dark:text-white">
                    {wf.name}
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                    {wf.channelName ?? '—'}
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                    {wf.fieldCount ?? 0} field{wf.fieldCount !== 1 ? 's' : ''}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${
                        wf.enabled
                          ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                          : 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400'
                      }`}
                    >
                      {wf.enabled ? 'Active' : 'Disabled'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                    {new Date(wf.createdAt).toLocaleDateString()}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <div className="flex items-center justify-end gap-1">
                      <button
                        onClick={() => navigate(`/crm/settings/web-form-editor/${wf.pid}`)}
                        className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-700 dark:hover:text-gray-200"
                        title="Edit Form"
                        data-testid="webform-edit-btn"
                      >
                        <PencilIcon className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => handleDelete(wf)}
                        className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-900/20"
                        title="Delete"
                        data-testid="webform-delete-btn"
                      >
                        <TrashIcon className="h-4 w-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showCreate && (
        <CreateFormModal
          channels={channels}
          onClose={() => setShowCreate(false)}
          onCreated={handleCreated}
        />
      )}
    </div>
  );
}

// ─── Empty State ──────────────────────────────────────────────────────────────

function FormsEmptyState({ onAdd }: { onAdd: () => void }) {
  return (
    <div className="py-16 text-center text-gray-400" data-testid="webform-empty">
      <DocumentTextIcon className="mx-auto mb-3 h-12 w-12 opacity-30" />
      <p className="mb-1 font-medium text-gray-500 dark:text-gray-400">No web forms yet</p>
      <p className="mb-4 text-sm">
        Create a web form to embed lead capture on your website. Forms are linked to Web Form
        channels.
      </p>
      <button
        onClick={onAdd}
        className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
      >
        Create First Form
      </button>
    </div>
  );
}

// ─── Create Form Modal ────────────────────────────────────────────────────────

function CreateFormModal({
  channels,
  onClose,
  onCreated,
}: {
  channels: InboundChannel[];
  onClose: () => void;
  onCreated: (form: WebForm) => void;
}) {
  const [name, setName] = useState('');
  const [channelPid, setChannelPid] = useState(channels[0]?.pid ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) {
      setError('Form name is required.');
      return;
    }
    if (!channelPid) {
      setError('Please select a Web Form channel. Create one in Inbound Channels first.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const created = await apiFetch<WebForm>('/api/crm/web-forms', {
        method: 'post',
        body: JSON.stringify({ name, channelPid }),
      });
      onCreated(created);
    } catch (err: unknown) {
      setError((err instanceof Error ? err.message : null) ?? 'Failed to create form');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
      <div className="mx-4 w-full max-w-md overflow-hidden rounded-2xl bg-white shadow-2xl dark:bg-gray-800">
        <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4 dark:border-gray-700">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">New Web Form</h3>
          <button onClick={onClose} className="rounded-lg p-1.5 hover:bg-gray-100 dark:hover:bg-gray-700">
            <XMarkIcon className="h-5 w-5 text-gray-400" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4 px-6 py-5">
          {error && (
            <div className="rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-400">
              {error}
            </div>
          )}

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Form Name <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="e.g. Contact Us Form"
              data-testid="webform-field-name"
              autoFocus
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
              Web Form Channel <span className="text-red-500">*</span>
            </label>
            {channels.length === 0 ? (
              <p className="rounded-lg border border-yellow-200 bg-yellow-50 px-3 py-2 text-sm text-yellow-700">
                No Web Form channels found. Please create a "Web Form" channel in{' '}
                <a href="/crm/settings/inbound-channels" className="underline">
                  Inbound Channels
                </a>{' '}
                first.
              </p>
            ) : (
              <select
                value={channelPid}
                onChange={(e) => setChannelPid(e.target.value)}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                data-testid="webform-field-channel"
              >
                {channels.map((ch) => (
                  <option key={ch.pid} value={ch.pid}>
                    {ch.name}
                  </option>
                ))}
              </select>
            )}
          </div>
        </form>

        <div className="flex items-center justify-end gap-3 border-t border-gray-100 bg-gray-50 px-6 py-4 dark:border-gray-700 dark:bg-gray-900/30">
          <button
            type="button"
            onClick={onClose}
            disabled={saving}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit as unknown as React.MouseEventHandler}
            disabled={saving || channels.length === 0}
            data-testid="webform-create-confirm-btn"
            className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Creating...' : 'Create & Edit'}
          </button>
        </div>
      </div>
    </div>
  );
}
