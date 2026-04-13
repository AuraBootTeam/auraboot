/**
 * Webhooks Settings Page — GAP-011
 *
 * Manage outbound webhook subscriptions with:
 * - List view: name, URL, event type, status toggle
 * - Create/edit dialog: name, URL, secret, event type, model filter, retries, timeout
 * - Delivery history per webhook
 */

import { useState, useEffect, useCallback } from 'react';
import {
  PlusIcon,
  PencilIcon,
  TrashIcon,
  ClockIcon,
  CheckCircleIcon,
  XCircleIcon,
  SignalIcon,
  ChevronDownIcon,
  ChevronUpIcon,
} from '@heroicons/react/24/outline';

// ─── Types ────────────────────────────────────────────────────────────────────

interface WebhookSubscription {
  pid: string;
  name: string;
  targetUrl: string;
  eventType: string;
  modelCode?: string;
  filterExpression?: string;
  secret?: string;
  headers?: string;
  maxRetries: number;
  timeoutMs: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

interface DeliveryLog {
  pid: string;
  subscriptionPid: string;
  eventId?: string;
  requestUrl: string;
  requestBody?: string;
  responseStatus?: number;
  responseBody?: string;
  deliveryStatus: 'success' | 'failed' | 'pending';
  retryCount: number;
  errorMessage?: string;
  deliveredAt?: string;
  createdAt: string;
}

const EVENT_TYPES = [
  { value: 'CommandExecuted', label: 'Command Executed' },
  { value: 'record.created', label: 'Record Created' },
  { value: 'record.updated', label: 'Record Updated' },
  { value: 'record.deleted', label: 'Record Deleted' },
  { value: 'state.changed', label: 'State Changed' },
  { value: 'workflow.started', label: 'Workflow Started' },
  { value: 'workflow.completed', label: 'Workflow Completed' },
];

// ─── API helpers ──────────────────────────────────────────────────────────────

async function apiFetch(path: string, opts?: RequestInit) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  const code = json.code === undefined || json.code === null ? undefined : String(json.code);
  if (code !== undefined && code !== '0' && code !== '200') {
    throw new Error(json.message ?? 'API error');
  }
  return json.data;
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function WebhooksPage() {
  const [webhooks, setWebhooks] = useState<WebhookSubscription[]>([]);
  const [loading, setLoading] = useState(true);
  const [editTarget, setEditTarget] = useState<WebhookSubscription | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [expandedPid, setExpandedPid] = useState<string | null>(null);
  const [deliveries, setDeliveries] = useState<Record<string, DeliveryLog[]>>({});
  const [deliveryLoading, setDeliveryLoading] = useState<Record<string, boolean>>({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiFetch('/api/webhooks');
      setWebhooks(data ?? []);
    } catch {
      setWebhooks([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const toggleEnabled = async (wh: WebhookSubscription) => {
    const path = `/api/webhooks/${wh.pid}/${wh.enabled ? 'disable' : 'enable'}`;
    await apiFetch(path, { method: 'put' });
    setWebhooks((prev) => prev.map((w) => (w.pid === wh.pid ? { ...w, enabled: !w.enabled } : w)));
  };

  const deleteWebhook = async (pid: string) => {
    if (!confirm('Delete this webhook?')) return;
    await apiFetch(`/api/webhooks/${pid}`, { method: 'delete' });
    setWebhooks((prev) => prev.filter((w) => w.pid !== pid));
  };

  const openCreate = () => {
    setEditTarget(null);
    setShowForm(true);
  };
  const openEdit = (wh: WebhookSubscription) => {
    setEditTarget(wh);
    setShowForm(true);
  };

  const handleSaved = (saved: WebhookSubscription) => {
    setWebhooks((prev) => {
      const idx = prev.findIndex((w) => w.pid === saved.pid);
      if (idx >= 0) {
        const next = [...prev];
        next[idx] = saved;
        return next;
      }
      return [saved, ...prev];
    });
    setShowForm(false);
  };

  const toggleDeliveries = async (pid: string) => {
    if (expandedPid === pid) {
      setExpandedPid(null);
      return;
    }
    setExpandedPid(pid);
    if (deliveries[pid]) return; // already loaded
    setDeliveryLoading((prev) => ({ ...prev, [pid]: true }));
    try {
      const data = await apiFetch(`/api/webhooks/${pid}/deliveries`);
      setDeliveries((prev) => ({ ...prev, [pid]: data ?? [] }));
    } catch {
      setDeliveries((prev) => ({ ...prev, [pid]: [] }));
    } finally {
      setDeliveryLoading((prev) => ({ ...prev, [pid]: false }));
    }
  };

  return (
    <div className="mx-auto max-w-5xl p-6">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Webhooks</h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Send HTTP POST notifications to external URLs when platform events occur.
          </p>
        </div>
        <button
          onClick={openCreate}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
          data-testid="webhook-create-btn"
        >
          <PlusIcon className="h-4 w-4" />
          Add Webhook
        </button>
      </div>

      {loading ? (
        <div className="py-12 text-center text-gray-400">Loading webhooks...</div>
      ) : webhooks.length === 0 ? (
        <EmptyState onAdd={openCreate} />
      ) : (
        <div className="space-y-3" data-testid="webhook-list">
          {webhooks.map((wh) => (
            <WebhookRow
              key={wh.pid}
              webhook={wh}
              expanded={expandedPid === wh.pid}
              deliveries={deliveries[wh.pid] ?? []}
              deliveryLoading={deliveryLoading[wh.pid] ?? false}
              onToggleEnabled={() => toggleEnabled(wh)}
              onEdit={() => openEdit(wh)}
              onDelete={() => deleteWebhook(wh.pid)}
              onToggleDeliveries={() => toggleDeliveries(wh.pid)}
            />
          ))}
        </div>
      )}

      {showForm && (
        <WebhookFormDialog
          initial={editTarget}
          onClose={() => setShowForm(false)}
          onSaved={handleSaved}
        />
      )}
    </div>
  );
}

// ─── Webhook Row ──────────────────────────────────────────────────────────────

function WebhookRow({
  webhook: wh,
  expanded,
  deliveries,
  deliveryLoading,
  onToggleEnabled,
  onEdit,
  onDelete,
  onToggleDeliveries,
}: {
  webhook: WebhookSubscription;
  expanded: boolean;
  deliveries: DeliveryLog[];
  deliveryLoading: boolean;
  onToggleEnabled: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onToggleDeliveries: () => void;
}) {
  return (
    <div
      className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800"
      data-testid="webhook-row"
    >
      <div className="flex items-center gap-4 p-4">
        {/* Status indicator */}
        <div
          className={`h-2.5 w-2.5 flex-shrink-0 rounded-full ${wh.enabled ? 'bg-green-500' : 'bg-gray-300 dark:bg-gray-600'}`}
        />

        {/* Main info */}
        <div className="min-w-0 flex-1">
          <div className="mb-0.5 flex items-center gap-2">
            <span
              className="text-sm font-medium text-gray-900 dark:text-white"
              data-testid="webhook-name"
            >
              {wh.name}
            </span>
            <EventTypeBadge eventType={wh.eventType} />
          </div>
          <p className="truncate text-xs text-gray-500 dark:text-gray-400">{wh.targetUrl}</p>
          {wh.modelCode && (
            <p className="mt-0.5 text-xs text-blue-600 dark:text-blue-400">Model: {wh.modelCode}</p>
          )}
        </div>

        {/* Actions */}
        <div className="flex flex-shrink-0 items-center gap-2">
          {/* Enable/disable toggle */}
          <button
            onClick={onToggleEnabled}
            className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
              wh.enabled ? 'bg-blue-600' : 'bg-gray-300 dark:bg-gray-600'
            }`}
            title={wh.enabled ? 'Disable' : 'Enable'}
            data-testid="webhook-toggle"
          >
            <span
              className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${
                wh.enabled ? 'translate-x-4.5' : 'translate-x-0.5'
              }`}
            />
          </button>

          <button
            onClick={onToggleDeliveries}
            className="flex items-center gap-1 rounded-lg border border-gray-200 px-2 py-1 text-xs text-gray-500 transition-colors hover:text-gray-700 dark:border-gray-600 dark:text-gray-400 dark:hover:text-gray-200"
            data-testid="webhook-deliveries-btn"
          >
            <SignalIcon className="h-3.5 w-3.5" />
            Deliveries
            {expanded ? (
              <ChevronUpIcon className="h-3 w-3" />
            ) : (
              <ChevronDownIcon className="h-3 w-3" />
            )}
          </button>

          <button
            onClick={onEdit}
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-700 dark:hover:text-gray-200"
            title="Edit"
            data-testid="webhook-edit-btn"
          >
            <PencilIcon className="h-4 w-4" />
          </button>
          <button
            onClick={onDelete}
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-900/20"
            title="Delete"
            data-testid="webhook-delete-btn"
          >
            <TrashIcon className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Delivery history panel */}
      {expanded && (
        <div className="border-t border-gray-100 bg-gray-50 dark:border-gray-700 dark:bg-gray-900/30">
          <div className="p-4">
            <h4 className="mb-3 text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
              Recent Deliveries
            </h4>
            {deliveryLoading ? (
              <p className="text-sm text-gray-400">Loading...</p>
            ) : deliveries.length === 0 ? (
              <p className="text-sm text-gray-400">No delivery attempts yet.</p>
            ) : (
              <div className="space-y-2">
                {deliveries.map((log) => (
                  <DeliveryLogRow key={log.pid} log={log} />
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Delivery Log Row ─────────────────────────────────────────────────────────

function DeliveryLogRow({ log }: { log: DeliveryLog }) {
  const [open, setOpen] = useState(false);
  const statusIcon =
    log.deliveryStatus === 'success' ? (
      <CheckCircleIcon className="h-4 w-4 text-green-500" />
    ) : log.deliveryStatus === 'pending' ? (
      <ClockIcon className="h-4 w-4 text-yellow-500" />
    ) : (
      <XCircleIcon className="h-4 w-4 text-red-500" />
    );

  return (
    <div className="rounded-lg border border-gray-200 bg-white text-xs dark:border-gray-700 dark:bg-gray-800">
      <button
        className="flex w-full items-center gap-3 p-3 text-left"
        onClick={() => setOpen((p) => !p)}
      >
        {statusIcon}
        <span
          className={`font-medium ${
            log.deliveryStatus === 'success'
              ? 'text-green-700 dark:text-green-400'
              : log.deliveryStatus === 'failed'
                ? 'text-red-700 dark:text-red-400'
                : 'text-yellow-700 dark:text-yellow-400'
          }`}
        >
          {log.deliveryStatus}
        </span>
        {log.responseStatus && (
          <span className="text-gray-500 dark:text-gray-400">HTTP {log.responseStatus}</span>
        )}
        {log.retryCount > 0 && <span className="text-gray-400">(retry #{log.retryCount})</span>}
        <span className="flex-1 text-right text-gray-400">
          {log.createdAt ? new Date(log.createdAt).toLocaleString() : ''}
        </span>
        {open ? (
          <ChevronUpIcon className="h-3 w-3 text-gray-400" />
        ) : (
          <ChevronDownIcon className="h-3 w-3 text-gray-400" />
        )}
      </button>
      {open && (
        <div className="space-y-2 border-t border-gray-100 p-3 dark:border-gray-700">
          {log.errorMessage && (
            <div>
              <span className="font-medium text-gray-500">Error:</span>{' '}
              <span className="text-red-600 dark:text-red-400">{log.errorMessage}</span>
            </div>
          )}
          {log.requestBody && (
            <div>
              <span className="mb-1 block font-medium text-gray-500">Request Body:</span>
              <pre className="overflow-x-auto rounded bg-gray-100 p-2 text-xs text-gray-700 dark:bg-gray-900 dark:text-gray-300">
                {tryPrettyJson(log.requestBody)}
              </pre>
            </div>
          )}
          {log.responseBody && (
            <div>
              <span className="mb-1 block font-medium text-gray-500">Response Body:</span>
              <pre className="overflow-x-auto rounded bg-gray-100 p-2 text-xs text-gray-700 dark:bg-gray-900 dark:text-gray-300">
                {tryPrettyJson(log.responseBody)}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function tryPrettyJson(s: string) {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}

// ─── Event Type Badge ─────────────────────────────────────────────────────────

function EventTypeBadge({ eventType }: { eventType: string }) {
  const label = EVENT_TYPES.find((e) => e.value === eventType)?.label ?? eventType;
  return (
    <span className="rounded-full bg-blue-100 px-2 py-0.5 text-xs text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
      {label}
    </span>
  );
}

// ─── Empty State ──────────────────────────────────────────────────────────────

function EmptyState({ onAdd }: { onAdd: () => void }) {
  return (
    <div className="py-16 text-center text-gray-400" data-testid="webhook-empty">
      <SignalIcon className="mx-auto mb-3 h-12 w-12 opacity-30" />
      <p className="mb-1 font-medium text-gray-500 dark:text-gray-400">No webhooks yet</p>
      <p className="mb-4 text-sm">
        Create a webhook to start receiving platform event notifications in external systems.
      </p>
      <button
        onClick={onAdd}
        className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
      >
        Add First Webhook
      </button>
    </div>
  );
}

// ─── Form Dialog ──────────────────────────────────────────────────────────────

interface FormState {
  name: string;
  targetUrl: string;
  eventType: string;
  modelCode: string;
  filterExpression: string;
  secret: string;
  maxRetries: number;
  timeoutMs: number;
  enabled: boolean;
}

function WebhookFormDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: WebhookSubscription | null;
  onClose: () => void;
  onSaved: (saved: WebhookSubscription) => void;
}) {
  const [form, setForm] = useState<FormState>({
    name: initial?.name ?? '',
    targetUrl: initial?.targetUrl ?? '',
    eventType: initial?.eventType ?? 'CommandExecuted',
    modelCode: initial?.modelCode ?? '',
    filterExpression: initial?.filterExpression ?? '',
    secret: initial?.secret ?? '',
    maxRetries: initial?.maxRetries ?? 3,
    timeoutMs: initial?.timeoutMs ?? 10000,
    enabled: initial?.enabled ?? true,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const set = (key: keyof FormState, value: any) => setForm((prev) => ({ ...prev, [key]: value }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.name.trim() || !form.targetUrl.trim()) {
      setError('Name and URL are required.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const body: Record<string, any> = {
        name: form.name,
        targetUrl: form.targetUrl,
        eventType: form.eventType,
        modelCode: form.modelCode || undefined,
        filterExpression: form.filterExpression || undefined,
        secret: form.secret || undefined,
        maxRetries: form.maxRetries,
        timeoutMs: form.timeoutMs,
        enabled: form.enabled,
      };
      const method = initial ? 'put' : 'post';
      const url = initial ? `/api/webhooks/${initial.pid}` : '/api/webhooks';
      const saved = await apiFetch(url, {
        method,
        body: JSON.stringify(body),
      });
      onSaved(saved);
    } catch (err: any) {
      setError(err?.message ?? 'Failed to save webhook');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
      data-testid="webhook-form-dialog"
    >
      <div className="mx-4 w-full max-w-lg overflow-hidden rounded-2xl bg-white shadow-2xl dark:bg-gray-800">
        <div className="flex items-center justify-between border-b border-gray-100 p-5 dark:border-gray-700">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
            {initial ? 'Edit Webhook' : 'Create Webhook'}
          </h3>
          <button
            onClick={onClose}
            className="rounded-lg p-1.5 hover:bg-gray-100 dark:hover:bg-gray-700"
          >
            <XCircleIcon className="h-5 w-5 text-gray-400" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="max-h-[70vh] space-y-4 overflow-y-auto p-5">
          {error && (
            <div className="rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-400">
              {error}
            </div>
          )}

          <Field label="Name" required>
            <input
              type="text"
              value={form.name}
              onChange={(e) => set('name', e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="My Webhook"
              data-testid="webhook-field-name"
            />
          </Field>

          <Field label="Target URL" required>
            <input
              type="url"
              value={form.targetUrl}
              onChange={(e) => set('targetUrl', e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="https://example.com/webhook"
              data-testid="webhook-field-url"
            />
          </Field>

          <Field label="Event Type" required>
            <select
              value={form.eventType}
              onChange={(e) => set('eventType', e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              data-testid="webhook-field-event-type"
            >
              {EVENT_TYPES.map((et) => (
                <option key={et.value} value={et.value}>
                  {et.label}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Model Filter" hint="Leave blank to receive events for all models">
            <input
              type="text"
              value={form.modelCode}
              onChange={(e) => set('modelCode', e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="e.g. crm-lead"
            />
          </Field>

          <Field
            label="Secret (HMAC-SHA256)"
            hint="Used to sign payloads via X-Webhook-Signature header"
          >
            <input
              type="password"
              value={form.secret}
              onChange={(e) => set('secret', e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder={
                initial?.secret?.startsWith('****') ? '(unchanged)' : 'Optional signing secret'
              }
              autoComplete="new-password"
            />
          </Field>

          <Field
            label="Filter Expression"
            hint="SpEL expression evaluated against event payload (e.g. payload.status == 'active')"
          >
            <input
              type="text"
              value={form.filterExpression}
              onChange={(e) => set('filterExpression', e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="Optional"
            />
          </Field>

          <div className="grid grid-cols-2 gap-4">
            <Field label="Max Retries">
              <input
                type="number"
                value={form.maxRetries}
                min={0}
                max={10}
                onChange={(e) => set('maxRetries', Number(e.target.value))}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              />
            </Field>
            <Field label="Timeout (ms)">
              <input
                type="number"
                value={form.timeoutMs}
                min={1000}
                max={60000}
                step={1000}
                onChange={(e) => set('timeoutMs', Number(e.target.value))}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              />
            </Field>
          </div>

          <label className="flex cursor-pointer items-center gap-3 select-none">
            <input
              type="checkbox"
              checked={form.enabled}
              onChange={(e) => set('enabled', e.target.checked)}
              className="rounded border-gray-300 text-blue-600"
            />
            <span className="text-sm text-gray-700 dark:text-gray-300">Enabled</span>
          </label>
        </form>

        <div className="flex items-center justify-end gap-3 border-t border-gray-100 bg-gray-50 p-5 dark:border-gray-700 dark:bg-gray-900/30">
          <button
            type="button"
            onClick={onClose}
            disabled={saving}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit as any}
            disabled={saving}
            data-testid="webhook-save-btn"
            className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving...' : initial ? 'Save Changes' : 'Create Webhook'}
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({
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
