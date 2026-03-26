/**
 * CRM Inbound Channels Management Page
 *
 * Manage inbound lead capture channels:
 * - List view: name, type badge, enabled toggle, masked API key + copy, rate limit
 * - Create/Edit modal: channel type selector with dynamic config sections
 * - Field mapping editor (external field → lead field)
 * - API key regeneration
 */

import { useState, useEffect, useCallback } from 'react';
import {
  PlusIcon,
  PencilIcon,
  TrashIcon,
  ClipboardDocumentIcon,
  ArrowPathIcon,
  XMarkIcon,
  KeyIcon,
  PlusCircleIcon,
  MinusCircleIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';

// ─── Types ────────────────────────────────────────────────────────────────────

type ChannelType =
  | 'generic_webhook'
  | 'email_imap'
  | 'web_form'
  | 'wechat_work'
  | 'facebook_lead_ads'
  | 'email_inbound_webhook';

interface FieldMapping {
  externalField: string;
  leadField: string;
}

interface InboundChannel {
  pid: string;
  name: string;
  channelType: ChannelType;
  enabled: boolean;
  apiKey: string;
  rateLimitPerMinute: number;
  fieldMappings?: FieldMapping[];
  config?: Record<string, string>;
  createdAt: string;
}

interface ChannelFormState {
  name: string;
  channelType: ChannelType;
  enabled: boolean;
  rateLimitPerMinute: number;
  fieldMappings: FieldMapping[];
  // GENERIC_WEBHOOK
  hmacSecret: string;
  // EMAIL_IMAP
  imapHost: string;
  imapPort: number;
  imapSsl: boolean;
  imapUsername: string;
  imapPassword: string;
  // WECHAT_WORK
  wxCorpId: string;
  wxAgentId: string;
  wxToken: string;
  wxEncodingAesKey: string;
  wxContactSecret: string;
  // FACEBOOK_LEAD_ADS
  fbAppSecret: string;
  fbPageAccessToken: string;
  fbVerifyToken: string;
  fbPageId: string;
  // EMAIL_INBOUND_WEBHOOK
  emailWebhookSecret: string;
}

const CHANNEL_TYPES: { value: ChannelType; label: string; color: string }[] = [
  { value: 'generic_webhook', label: 'Generic Webhook', color: 'bg-purple-100 text-purple-700' },
  { value: 'email_imap', label: 'Email (IMAP)', color: 'bg-blue-100 text-blue-700' },
  { value: 'web_form', label: 'Web Form', color: 'bg-green-100 text-green-700' },
  { value: 'wechat_work', label: 'WeCom', color: 'bg-emerald-100 text-emerald-700' },
  { value: 'facebook_lead_ads', label: 'Facebook Lead Ads', color: 'bg-blue-100 text-blue-800' },
  {
    value: 'email_inbound_webhook',
    label: 'Email Inbound Webhook',
    color: 'bg-orange-100 text-orange-700',
  },
];

const defaultForm: ChannelFormState = {
  name: '',
  channelType: 'generic_webhook',
  enabled: true,
  rateLimitPerMinute: 60,
  fieldMappings: [],
  hmacSecret: '',
  imapHost: '',
  imapPort: 993,
  imapSsl: true,
  imapUsername: '',
  imapPassword: '',
  wxCorpId: '',
  wxAgentId: '',
  wxToken: '',
  wxEncodingAesKey: '',
  wxContactSecret: '',
  fbAppSecret: '',
  fbPageAccessToken: '',
  fbVerifyToken: '',
  fbPageId: '',
  emailWebhookSecret: '',
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

function buildConfig(form: ChannelFormState): Record<string, string> {
  switch (form.channelType) {
    case 'generic_webhook':
      return form.hmacSecret ? { hmacSecret: form.hmacSecret } : {};
    case 'email_imap':
      return {
        host: form.imapHost,
        port: String(form.imapPort),
        ssl: String(form.imapSsl),
        username: form.imapUsername,
        password: form.imapPassword,
      };
    case 'wechat_work':
      return {
        corpId: form.wxCorpId,
        agentId: form.wxAgentId,
        token: form.wxToken,
        encodingAesKey: form.wxEncodingAesKey,
        contactSecret: form.wxContactSecret,
      };
    case 'facebook_lead_ads':
      return {
        appSecret: form.fbAppSecret,
        pageAccessToken: form.fbPageAccessToken,
        verifyToken: form.fbVerifyToken,
        pageId: form.fbPageId,
      };
    case 'email_inbound_webhook':
      return form.emailWebhookSecret ? { webhookSecret: form.emailWebhookSecret } : {};
    default:
      return {};
  }
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function InboundChannelsPage() {
  const { showToast } = useToastContext();
  const [channels, setChannels] = useState<InboundChannel[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [editTarget, setEditTarget] = useState<InboundChannel | null>(null);
  const [regenLoading, setRegenLoading] = useState<Record<string, boolean>>({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiFetch<InboundChannel[]>('/api/crm/inbound-channels');
      setChannels(data ?? []);
    } catch {
      setChannels([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setEditTarget(null);
    setShowModal(true);
  };

  const openEdit = (ch: InboundChannel) => {
    setEditTarget(ch);
    setShowModal(true);
  };

  const handleDelete = async (ch: InboundChannel) => {
    if (!confirm(`Delete channel "${ch.name}"? This cannot be undone.`)) return;
    try {
      await apiFetch(`/api/crm/inbound-channels/${ch.pid}`, { method: 'delete' });
      setChannels((prev) => prev.filter((c) => c.pid !== ch.pid));
      showToast('Channel deleted', 'success');
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Failed to delete channel', 'error');
    }
  };

  const handleRegenKey = async (ch: InboundChannel) => {
    if (!confirm(`Regenerate API key for "${ch.name}"? The old key will stop working immediately.`))
      return;
    setRegenLoading((prev) => ({ ...prev, [ch.pid]: true }));
    try {
      const updated = await apiFetch<InboundChannel>(
        `/api/crm/inbound-channels/${ch.pid}/regenerate-key`,
        { method: 'post' },
      );
      setChannels((prev) => prev.map((c) => (c.pid === ch.pid ? updated : c)));
      showToast('API key regenerated', 'success');
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Failed to regenerate key', 'error');
    } finally {
      setRegenLoading((prev) => ({ ...prev, [ch.pid]: false }));
    }
  };

  const handleToggleEnabled = async (ch: InboundChannel) => {
    try {
      await apiFetch(`/api/crm/inbound-channels/${ch.pid}/toggle?enabled=${String(!ch.enabled)}`, {
        method: 'post',
      });
      setChannels((prev) =>
        prev.map((c) => (c.pid === ch.pid ? { ...c, enabled: !ch.enabled } : c)),
      );
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Failed to update channel', 'error');
    }
  };

  const handleSaved = (saved: InboundChannel) => {
    setChannels((prev) => {
      const idx = prev.findIndex((c) => c.pid === saved.pid);
      if (idx >= 0) {
        const next = [...prev];
        next[idx] = saved;
        return next;
      }
      return [saved, ...prev];
    });
    setShowModal(false);
    showToast(editTarget ? 'Channel updated' : 'Channel created', 'success');
  };

  const copyKey = (key: string) => {
    navigator.clipboard.writeText(key).then(() => showToast('API key copied', 'success'));
  };

  return (
    <div className="mx-auto max-w-5xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Inbound Channels</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Configure lead ingestion channels — webhooks, email, web forms, and social integrations.
          </p>
        </div>
        <button
          onClick={openCreate}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
          data-testid="channel-create-btn"
        >
          <PlusIcon className="h-4 w-4" />
          New Channel
        </button>
      </div>

      {/* Content */}
      {loading ? (
        <div className="py-12 text-center text-gray-400">Loading channels...</div>
      ) : channels.length === 0 ? (
        <ChannelEmptyState onAdd={openCreate} />
      ) : (
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
            <thead className="bg-gray-50 dark:bg-gray-900/30">
              <tr>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Name
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Type
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Enabled
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  API Key
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Rate Limit
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
              data-testid="channel-list"
            >
              {channels.map((ch) => {
                const typeInfo = CHANNEL_TYPES.find((t) => t.value === ch.channelType);
                const masked =
                  ch.apiKey.length > 8
                    ? ch.apiKey.slice(0, 4) + '••••••••' + ch.apiKey.slice(-4)
                    : '••••••••';
                return (
                  <tr
                    key={ch.pid}
                    className="hover:bg-gray-50 dark:hover:bg-gray-700/30"
                    data-testid="channel-row"
                  >
                    <td className="px-4 py-3 text-sm font-medium text-gray-900 dark:text-white">
                      {ch.name}
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${typeInfo?.color ?? 'bg-gray-100 text-gray-600'}`}
                      >
                        {typeInfo?.label ?? ch.channelType}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <button
                        onClick={() => handleToggleEnabled(ch)}
                        className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
                          ch.enabled ? 'bg-blue-600' : 'bg-gray-300 dark:bg-gray-600'
                        }`}
                        title={ch.enabled ? 'Disable' : 'Enable'}
                        data-testid="channel-toggle"
                      >
                        <span
                          className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${
                            ch.enabled ? 'translate-x-4.5' : 'translate-x-0.5'
                          }`}
                        />
                      </button>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <code className="rounded bg-gray-100 px-2 py-0.5 font-mono text-xs text-gray-700 dark:bg-gray-700 dark:text-gray-300">
                          {masked}
                        </code>
                        <button
                          onClick={() => copyKey(ch.apiKey)}
                          className="text-gray-400 transition-colors hover:text-blue-600"
                          title="Copy API Key"
                        >
                          <ClipboardDocumentIcon className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                      {ch.rateLimitPerMinute}/min
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                      {new Date(ch.createdAt).toLocaleDateString()}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => handleRegenKey(ch)}
                          disabled={regenLoading[ch.pid]}
                          className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-yellow-50 hover:text-yellow-600 dark:hover:bg-yellow-900/20"
                          title="Regenerate API Key"
                        >
                          {regenLoading[ch.pid] ? (
                            <ArrowPathIcon className="h-4 w-4 animate-spin" />
                          ) : (
                            <KeyIcon className="h-4 w-4" />
                          )}
                        </button>
                        <button
                          onClick={() => openEdit(ch)}
                          className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-700 dark:hover:text-gray-200"
                          title="Edit"
                          data-testid="channel-edit-btn"
                        >
                          <PencilIcon className="h-4 w-4" />
                        </button>
                        <button
                          onClick={() => handleDelete(ch)}
                          className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-900/20"
                          title="Delete"
                          data-testid="channel-delete-btn"
                        >
                          <TrashIcon className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {showModal && (
        <ChannelFormModal
          initial={editTarget}
          onClose={() => setShowModal(false)}
          onSaved={handleSaved}
        />
      )}
    </div>
  );
}

// ─── Empty State ──────────────────────────────────────────────────────────────

function ChannelEmptyState({ onAdd }: { onAdd: () => void }) {
  return (
    <div className="py-16 text-center text-gray-400" data-testid="channel-empty">
      <KeyIcon className="mx-auto mb-3 h-12 w-12 opacity-30" />
      <p className="mb-1 font-medium text-gray-500 dark:text-gray-400">No inbound channels yet</p>
      <p className="mb-4 text-sm">
        Create a channel to start capturing leads from webhooks, email, or web forms.
      </p>
      <button
        onClick={onAdd}
        className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
      >
        Create First Channel
      </button>
    </div>
  );
}

// ─── Form Modal ───────────────────────────────────────────────────────────────

function ChannelFormModal({
  initial,
  onClose,
  onSaved,
}: {
  initial: InboundChannel | null;
  onClose: () => void;
  onSaved: (saved: InboundChannel) => void;
}) {
  const buildInitialForm = (): ChannelFormState => {
    if (!initial) return defaultForm;
    const cfg = initial.config ?? {};
    return {
      name: initial.name,
      channelType: initial.channelType,
      enabled: initial.enabled,
      rateLimitPerMinute: initial.rateLimitPerMinute,
      fieldMappings: initial.fieldMappings ?? [],
      hmacSecret: cfg['hmacSecret'] ?? '',
      imapHost: cfg['host'] ?? '',
      imapPort: Number(cfg['port'] ?? 993),
      imapSsl: cfg['ssl'] !== 'false',
      imapUsername: cfg['username'] ?? '',
      imapPassword: cfg['password'] ?? '',
      wxCorpId: cfg['corpId'] ?? '',
      wxAgentId: cfg['agentId'] ?? '',
      wxToken: cfg['token'] ?? '',
      wxEncodingAesKey: cfg['encodingAesKey'] ?? '',
      wxContactSecret: cfg['contactSecret'] ?? '',
      fbAppSecret: cfg['appSecret'] ?? '',
      fbPageAccessToken: cfg['pageAccessToken'] ?? '',
      fbVerifyToken: cfg['verifyToken'] ?? '',
      fbPageId: cfg['pageId'] ?? '',
      emailWebhookSecret: cfg['webhookSecret'] ?? '',
    };
  };

  const [form, setForm] = useState<ChannelFormState>(buildInitialForm);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const patch = (partial: Partial<ChannelFormState>) =>
    setForm((prev) => ({ ...prev, ...partial }));

  const addMapping = () =>
    patch({ fieldMappings: [...form.fieldMappings, { externalField: '', leadField: '' }] });

  const removeMapping = (idx: number) =>
    patch({ fieldMappings: form.fieldMappings.filter((_, i) => i !== idx) });

  const updateMapping = (idx: number, key: keyof FieldMapping, value: string) => {
    const updated = form.fieldMappings.map((m, i) => (i === idx ? { ...m, [key]: value } : m));
    patch({ fieldMappings: updated });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.name.trim()) {
      setError('Channel name is required.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const body = {
        name: form.name,
        channelType: form.channelType,
        enabled: form.enabled,
        rateLimitPerMinute: form.rateLimitPerMinute,
        fieldMappings: form.fieldMappings.filter((m) => m.externalField && m.leadField),
        config: buildConfig(form),
      };
      const method = initial ? 'put' : 'post';
      const url = initial
        ? `/api/crm/inbound-channels/${initial.pid}`
        : '/api/crm/inbound-channels';
      const saved = await apiFetch<InboundChannel>(url, { method, body: JSON.stringify(body) });
      onSaved(saved);
    } catch (err: unknown) {
      setError((err instanceof Error ? err.message : null) ?? 'Failed to save channel');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
      <div className="mx-4 flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl dark:bg-gray-800">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4 dark:border-gray-700">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
            {initial ? 'Edit Channel' : 'New Inbound Channel'}
          </h3>
          <button onClick={onClose} className="rounded-lg p-1.5 hover:bg-gray-100 dark:hover:bg-gray-700">
            <XMarkIcon className="h-5 w-5 text-gray-400" />
          </button>
        </div>

        {/* Body */}
        <form onSubmit={handleSubmit} className="flex-1 space-y-5 overflow-y-auto px-6 py-5">
          {error && (
            <div className="rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-400">
              {error}
            </div>
          )}

          <div className="grid grid-cols-2 gap-4">
            <FormField label="Channel Name" required>
              <input
                type="text"
                value={form.name}
                onChange={(e) => patch({ name: e.target.value })}
                className="input-base"
                placeholder="e.g. Website Contact Form"
                data-testid="channel-field-name"
              />
            </FormField>

            <FormField label="Channel Type" required>
              <select
                value={form.channelType}
                onChange={(e) => patch({ channelType: e.target.value as ChannelType })}
                className="input-base"
                data-testid="channel-field-type"
              >
                {CHANNEL_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </FormField>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <FormField label="Rate Limit (requests/min)">
              <input
                type="number"
                value={form.rateLimitPerMinute}
                min={1}
                max={10000}
                onChange={(e) => patch({ rateLimitPerMinute: Number(e.target.value) })}
                className="input-base"
              />
            </FormField>
            <div className="flex items-end pb-0.5">
              <label className="flex cursor-pointer items-center gap-3 select-none">
                <div
                  className={`relative inline-flex h-5 w-9 cursor-pointer items-center rounded-full transition-colors ${
                    form.enabled ? 'bg-blue-600' : 'bg-gray-300'
                  }`}
                  onClick={() => patch({ enabled: !form.enabled })}
                >
                  <span
                    className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${
                      form.enabled ? 'translate-x-4.5' : 'translate-x-0.5'
                    }`}
                  />
                </div>
                <span className="text-sm text-gray-700 dark:text-gray-300">Enabled</span>
              </label>
            </div>
          </div>

          {/* Dynamic Config Section */}
          <ChannelConfigSection form={form} onChange={patch} />

          {/* Field Mappings */}
          <div>
            <div className="mb-2 flex items-center justify-between">
              <label className="text-sm font-medium text-gray-700 dark:text-gray-300">
                Field Mappings
              </label>
              <button
                type="button"
                onClick={addMapping}
                className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700"
              >
                <PlusCircleIcon className="h-4 w-4" />
                Add Mapping
              </button>
            </div>
            {form.fieldMappings.length === 0 ? (
              <p className="text-xs text-gray-400 italic">
                No field mappings. Add mappings to translate external fields to lead fields.
              </p>
            ) : (
              <div className="space-y-2">
                {form.fieldMappings.map((m, idx) => (
                  <div key={idx} className="flex items-center gap-2">
                    <input
                      type="text"
                      value={m.externalField}
                      onChange={(e) => updateMapping(idx, 'externalField', e.target.value)}
                      className="input-base flex-1"
                      placeholder="External field"
                    />
                    <span className="text-gray-400">→</span>
                    <input
                      type="text"
                      value={m.leadField}
                      onChange={(e) => updateMapping(idx, 'leadField', e.target.value)}
                      className="input-base flex-1"
                      placeholder="Lead field"
                    />
                    <button
                      type="button"
                      onClick={() => removeMapping(idx)}
                      className="text-red-400 hover:text-red-600"
                    >
                      <MinusCircleIcon className="h-4 w-4" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </form>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 border-t border-gray-100 bg-gray-50 px-6 py-4 dark:border-gray-700 dark:bg-gray-900/30">
          <button
            type="button"
            onClick={onClose}
            disabled={saving}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit as unknown as React.MouseEventHandler}
            disabled={saving}
            data-testid="channel-save-btn"
            className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving...' : initial ? 'Save Changes' : 'Create Channel'}
          </button>
        </div>
      </div>

      {/* Inline styles for reusable input class */}
      <style>{`
        .input-base {
          width: 100%;
          border-radius: 0.5rem;
          border: 1px solid #d1d5db;
          background: white;
          padding: 0.375rem 0.75rem;
          font-size: 0.875rem;
          color: #111827;
        }
        .dark .input-base {
          border-color: #4b5563;
          background: #374151;
          color: white;
        }
        .input-base:focus {
          outline: none;
          ring: 2px solid #3b82f6;
        }
      `}</style>
    </div>
  );
}

// ─── Dynamic Config Section ───────────────────────────────────────────────────

function ChannelConfigSection({
  form,
  onChange,
}: {
  form: ChannelFormState;
  onChange: (partial: Partial<ChannelFormState>) => void;
}) {
  switch (form.channelType) {
    case 'generic_webhook':
      return (
        <FormField label="HMAC Secret" hint="Used to verify webhook payload signatures">
          <input
            type="password"
            value={form.hmacSecret}
            onChange={(e) => onChange({ hmacSecret: e.target.value })}
            className="input-base"
            placeholder="Optional signing secret"
            autoComplete="new-password"
          />
        </FormField>
      );

    case 'email_imap':
      return (
        <div className="space-y-4 rounded-lg border border-gray-200 bg-gray-50 p-4 dark:border-gray-600 dark:bg-gray-900/30">
          <p className="text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
            IMAP Configuration
          </p>
          <div className="grid grid-cols-2 gap-4">
            <FormField label="Host" required>
              <input
                type="text"
                value={form.imapHost}
                onChange={(e) => onChange({ imapHost: e.target.value })}
                className="input-base"
                placeholder="imap.gmail.com"
              />
            </FormField>
            <FormField label="Port">
              <input
                type="number"
                value={form.imapPort}
                onChange={(e) => onChange({ imapPort: Number(e.target.value) })}
                className="input-base"
                min={1}
                max={65535}
              />
            </FormField>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <FormField label="Username / Email" required>
              <input
                type="text"
                value={form.imapUsername}
                onChange={(e) => onChange({ imapUsername: e.target.value })}
                className="input-base"
                placeholder="leads@company.com"
              />
            </FormField>
            <FormField label="Password">
              <input
                type="password"
                value={form.imapPassword}
                onChange={(e) => onChange({ imapPassword: e.target.value })}
                className="input-base"
                placeholder="App password"
                autoComplete="new-password"
              />
            </FormField>
          </div>
          <label className="flex cursor-pointer items-center gap-2 select-none">
            <input
              type="checkbox"
              checked={form.imapSsl}
              onChange={(e) => onChange({ imapSsl: e.target.checked })}
              className="rounded border-gray-300"
            />
            <span className="text-sm text-gray-700 dark:text-gray-300">Use SSL/TLS</span>
          </label>
        </div>
      );

    case 'web_form':
      return (
        <p className="text-sm text-gray-400 italic">
          Web Form channels are configured via the Web Forms page. No additional config needed here.
        </p>
      );

    case 'wechat_work':
      return (
        <div className="space-y-4 rounded-lg border border-gray-200 bg-gray-50 p-4 dark:border-gray-600 dark:bg-gray-900/30">
          <p className="text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
            WeCom (企业微信) Configuration
          </p>
          <div className="grid grid-cols-2 gap-4">
            <FormField label="Corp ID (企业ID)" required>
              <input
                type="text"
                value={form.wxCorpId}
                onChange={(e) => onChange({ wxCorpId: e.target.value })}
                className="input-base"
              />
            </FormField>
            <FormField label="Agent ID" required>
              <input
                type="text"
                value={form.wxAgentId}
                onChange={(e) => onChange({ wxAgentId: e.target.value })}
                className="input-base"
              />
            </FormField>
          </div>
          <FormField label="Token">
            <input
              type="text"
              value={form.wxToken}
              onChange={(e) => onChange({ wxToken: e.target.value })}
              className="input-base"
            />
          </FormField>
          <FormField label="Encoding AES Key">
            <input
              type="password"
              value={form.wxEncodingAesKey}
              onChange={(e) => onChange({ wxEncodingAesKey: e.target.value })}
              className="input-base"
              autoComplete="new-password"
            />
          </FormField>
          <FormField label="Contact Secret">
            <input
              type="password"
              value={form.wxContactSecret}
              onChange={(e) => onChange({ wxContactSecret: e.target.value })}
              className="input-base"
              autoComplete="new-password"
            />
          </FormField>
        </div>
      );

    case 'facebook_lead_ads':
      return (
        <div className="space-y-4 rounded-lg border border-gray-200 bg-gray-50 p-4 dark:border-gray-600 dark:bg-gray-900/30">
          <p className="text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
            Facebook Lead Ads Configuration
          </p>
          <div className="grid grid-cols-2 gap-4">
            <FormField label="App Secret" required>
              <input
                type="password"
                value={form.fbAppSecret}
                onChange={(e) => onChange({ fbAppSecret: e.target.value })}
                className="input-base"
                autoComplete="new-password"
              />
            </FormField>
            <FormField label="Page ID" required>
              <input
                type="text"
                value={form.fbPageId}
                onChange={(e) => onChange({ fbPageId: e.target.value })}
                className="input-base"
              />
            </FormField>
          </div>
          <FormField label="Page Access Token">
            <input
              type="password"
              value={form.fbPageAccessToken}
              onChange={(e) => onChange({ fbPageAccessToken: e.target.value })}
              className="input-base"
              autoComplete="new-password"
            />
          </FormField>
          <FormField label="Verify Token">
            <input
              type="text"
              value={form.fbVerifyToken}
              onChange={(e) => onChange({ fbVerifyToken: e.target.value })}
              className="input-base"
              placeholder="Random string for webhook verification"
            />
          </FormField>
        </div>
      );

    case 'email_inbound_webhook':
      return (
        <FormField label="Webhook Secret" hint="Used to verify incoming email webhook payloads">
          <input
            type="password"
            value={form.emailWebhookSecret}
            onChange={(e) => onChange({ emailWebhookSecret: e.target.value })}
            className="input-base"
            placeholder="Optional signing secret"
            autoComplete="new-password"
          />
        </FormField>
      );

    default:
      return null;
  }
}

// ─── Field helper ─────────────────────────────────────────────────────────────

function FormField({
  label,
  hint,
  required,
  children,
}: {
  label: string;
  hint?: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
        {label}
        {required && <span className="ml-0.5 text-red-500">*</span>}
      </label>
      {children}
      {hint && <p className="mt-1 text-xs text-gray-400 dark:text-gray-500">{hint}</p>}
    </div>
  );
}
