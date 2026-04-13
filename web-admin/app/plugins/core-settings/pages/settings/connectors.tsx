/**
 * API Connectors Management Page — GAP-020
 *
 * Manage external REST API connectors:
 * - List connectors with type/status
 * - Create/edit: name, base URL, auth type, auth config (dynamic), test button
 * - "Test Connection" shows response time + status
 */

import { useState, useEffect, useCallback } from 'react';
import {
  PlusIcon,
  PencilIcon,
  TrashIcon,
  SignalIcon,
  CheckCircleIcon,
  XCircleIcon,
  ArrowPathIcon,
  ChevronDownIcon,
  ChevronUpIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';

// ─── Types ────────────────────────────────────────────────────────────────────

type AuthType = 'none' | 'api_key' | 'bearer' | 'basic';

interface ApiConnector {
  pid: string;
  name: string;
  baseUrl: string;
  authType: AuthType;
  authConfig?: string;
  defaultHeaders?: string;
  timeoutMs: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

interface ConnectorFormData {
  name: string;
  baseUrl: string;
  authType: AuthType;
  // API_KEY auth
  apiKeyHeaderName: string;
  apiKeyValue: string;
  // BEARER auth
  bearerToken: string;
  // BASIC auth
  basicUsername: string;
  basicPassword: string;
  // Misc
  defaultHeaders: string;
  timeoutMs: number;
  enabled: boolean;
}

const defaultForm: ConnectorFormData = {
  name: '',
  baseUrl: '',
  authType: 'none',
  apiKeyHeaderName: 'X-API-Key',
  apiKeyValue: '',
  bearerToken: '',
  basicUsername: '',
  basicPassword: '',
  defaultHeaders: '',
  timeoutMs: 30000,
  enabled: true,
};

// ─── API helpers ──────────────────────────────────────────────────────────────

const authHeaders = () => ({
  'Content-Type': 'application/json',
  Authorization: `Bearer ${localStorage.getItem('jwt')}`,
});

async function fetchConnectors(): Promise<ApiConnector[]> {
  const res = await fetch('/api/connectors', { headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to fetch connectors: ${res.status}`);
  const json = await res.json();
  return json.data ?? [];
}

function buildAuthConfig(form: ConnectorFormData): string | null {
  switch (form.authType) {
    case 'api_key':
      return JSON.stringify({ headerName: form.apiKeyHeaderName, apiKey: form.apiKeyValue });
    case 'bearer':
      return JSON.stringify({ token: form.bearerToken });
    case 'basic':
      return JSON.stringify({ username: form.basicUsername, password: form.basicPassword });
    default:
      return null;
  }
}

async function saveConnector(form: ConnectorFormData, pid?: string): Promise<ApiConnector> {
  const payload = {
    name: form.name,
    baseUrl: form.baseUrl,
    authType: form.authType,
    authConfig: buildAuthConfig(form),
    defaultHeaders: form.defaultHeaders || null,
    timeoutMs: form.timeoutMs,
    enabled: form.enabled,
  };
  const method = pid ? 'put' : 'post';
  const url = pid ? `/api/connectors/${pid}` : '/api/connectors';
  const res = await fetch(url, { method, headers: authHeaders(), body: JSON.stringify(payload) });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message ?? `Failed to save connector: ${res.status}`);
  }
  const json = await res.json();
  return json.data;
}

async function deleteConnector(pid: string): Promise<void> {
  const res = await fetch(`/api/connectors/${pid}`, { method: 'delete', headers: authHeaders() });
  if (!res.ok) throw new Error(`Failed to delete connector: ${res.status}`);
}

async function testConnector(pid: string): Promise<{ success: boolean; durationMs: number }> {
  const start = Date.now();
  const res = await fetch(`/api/connectors/${pid}/test`, {
    method: 'post',
    headers: authHeaders(),
  });
  const durationMs = Date.now() - start;
  return { success: res.ok, durationMs };
}

// ─── AuthConfig form section ──────────────────────────────────────────────────

function AuthConfigSection({
  form,
  onChange,
}: {
  form: ConnectorFormData;
  onChange: (partial: Partial<ConnectorFormData>) => void;
}) {
  switch (form.authType) {
    case 'api_key':
      return (
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-700">Header Name</label>
            <input
              type="text"
              value={form.apiKeyHeaderName}
              onChange={(e) => onChange({ apiKeyHeaderName: e.target.value })}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
              placeholder="X-API-Key"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-700">API Key Value</label>
            <input
              type="password"
              value={form.apiKeyValue}
              onChange={(e) => onChange({ apiKeyValue: e.target.value })}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
              placeholder="your-api-key"
            />
          </div>
        </div>
      );
    case 'bearer':
      return (
        <div>
          <label className="mb-1 block text-xs font-medium text-gray-700">Bearer Token</label>
          <input
            type="password"
            value={form.bearerToken}
            onChange={(e) => onChange({ bearerToken: e.target.value })}
            className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            placeholder="your-token"
          />
        </div>
      );
    case 'basic':
      return (
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-700">Username</label>
            <input
              type="text"
              value={form.basicUsername}
              onChange={(e) => onChange({ basicUsername: e.target.value })}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-700">Password</label>
            <input
              type="password"
              value={form.basicPassword}
              onChange={(e) => onChange({ basicPassword: e.target.value })}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
            />
          </div>
        </div>
      );
    default:
      return <p className="text-xs text-gray-400 italic">No authentication required.</p>;
  }
}

// ─── Main component ───────────────────────────────────────────────────────────

export default function ConnectorsPage() {
  const { showToast } = useToastContext();

  const [connectors, setConnectors] = useState<ApiConnector[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [editingPid, setEditingPid] = useState<string | undefined>();
  const [form, setForm] = useState<ConnectorFormData>(defaultForm);
  const [saving, setSaving] = useState(false);

  const [testStatus, setTestStatus] = useState<
    Record<string, { success: boolean; durationMs: number } | null>
  >({});
  const [testing, setTesting] = useState<Record<string, boolean>>({});

  const [expandedPid, setExpandedPid] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    fetchConnectors()
      .then(setConnectors)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleEdit = (connector: ApiConnector) => {
    setEditingPid(connector.pid);
    setForm({
      ...defaultForm,
      name: connector.name,
      baseUrl: connector.baseUrl,
      authType: connector.authType ?? 'none',
      defaultHeaders: connector.defaultHeaders ?? '',
      timeoutMs: connector.timeoutMs ?? 30000,
      enabled: connector.enabled,
    });
    setShowForm(true);
  };

  const handleNew = () => {
    setEditingPid(undefined);
    setForm(defaultForm);
    setShowForm(true);
  };

  const handleSave = async () => {
    if (!form.name.trim() || !form.baseUrl.trim()) {
      showToast('Name and Base URL are required', 'error');
      return;
    }
    setSaving(true);
    try {
      const saved = await saveConnector(form, editingPid);
      if (editingPid) {
        setConnectors((prev) => prev.map((c) => (c.pid === editingPid ? saved : c)));
        showToast('Connector updated', 'success');
      } else {
        setConnectors((prev) => [saved, ...prev]);
        showToast('Connector created', 'success');
      }
      setShowForm(false);
    } catch (e: any) {
      showToast(e.message ?? 'Failed to save connector', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (pid: string, name: string) => {
    if (!confirm(`Delete connector "${name}"?`)) return;
    try {
      await deleteConnector(pid);
      setConnectors((prev) => prev.filter((c) => c.pid !== pid));
      showToast('Connector deleted', 'success');
    } catch (e: any) {
      showToast(e.message ?? 'Failed to delete', 'error');
    }
  };

  const handleTest = async (pid: string) => {
    setTesting((prev) => ({ ...prev, [pid]: true }));
    setTestStatus((prev) => ({ ...prev, [pid]: null }));
    try {
      const result = await testConnector(pid);
      setTestStatus((prev) => ({ ...prev, [pid]: result }));
    } catch {
      setTestStatus((prev) => ({ ...prev, [pid]: { success: false, durationMs: 0 } }));
    } finally {
      setTesting((prev) => ({ ...prev, [pid]: false }));
    }
  };

  const patchForm = (partial: Partial<ConnectorFormData>) =>
    setForm((prev) => ({ ...prev, ...partial }));

  const AUTH_TYPES: { value: AuthType; label: string }[] = [
    { value: 'none', label: 'None' },
    { value: 'api_key', label: 'API Key' },
    { value: 'bearer', label: 'Bearer Token' },
    { value: 'basic', label: 'Basic Auth' },
  ];

  return (
    <div className="mx-auto max-w-5xl space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">API Connectors</h1>
          <p className="mt-1 text-sm text-gray-500">
            Connect external REST APIs as data sources for NamedQueries and Commands.
          </p>
        </div>
        <button
          onClick={handleNew}
          className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm text-white transition-colors hover:bg-indigo-700"
        >
          <PlusIcon className="h-4 w-4" />
          New Connector
        </button>
      </div>

      {/* Create / Edit form */}
      {showForm && (
        <div className="space-y-4 rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="text-base font-semibold text-gray-800">
            {editingPid ? 'Edit Connector' : 'New Connector'}
          </h2>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-700">Name *</label>
              <input
                type="text"
                value={form.name}
                onChange={(e) => patchForm({ name: e.target.value })}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 focus:outline-none"
                placeholder="e.g. Shopify Products API"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-700">Base URL *</label>
              <input
                type="url"
                value={form.baseUrl}
                onChange={(e) => patchForm({ baseUrl: e.target.value })}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 focus:outline-none"
                placeholder="https://api.example.com/v1"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-700">Auth Type</label>
              <select
                value={form.authType}
                onChange={(e) => patchForm({ authType: e.target.value as AuthType })}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 focus:outline-none"
              >
                {AUTH_TYPES.map((a) => (
                  <option key={a.value} value={a.value}>
                    {a.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-700">Timeout (ms)</label>
              <input
                type="number"
                value={form.timeoutMs}
                onChange={(e) => patchForm({ timeoutMs: Number(e.target.value) })}
                className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
                min={1000}
                max={120000}
                step={1000}
              />
            </div>
          </div>

          {/* Dynamic auth config */}
          <AuthConfigSection form={form} onChange={patchForm} />

          <div>
            <label className="mb-1 block text-xs font-medium text-gray-700">
              Default Headers (JSON, optional)
            </label>
            <input
              type="text"
              value={form.defaultHeaders}
              onChange={(e) => patchForm({ defaultHeaders: e.target.value })}
              className="w-full rounded border border-gray-300 px-3 py-2 font-mono text-sm"
              placeholder='{"Accept": "application/json"}'
            />
          </div>

          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="connector-enabled"
              checked={form.enabled}
              onChange={(e) => patchForm({ enabled: e.target.checked })}
              className="rounded"
            />
            <label htmlFor="connector-enabled" className="text-sm text-gray-700">
              Enabled
            </label>
          </div>

          <div className="flex gap-3 pt-2">
            <button
              onClick={handleSave}
              disabled={saving}
              className="rounded-lg bg-indigo-600 px-4 py-2 text-sm text-white transition-colors hover:bg-indigo-700 disabled:opacity-50"
            >
              {saving ? 'Saving…' : editingPid ? 'Update' : 'Create'}
            </button>
            <button
              onClick={() => setShowForm(false)}
              className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 hover:text-gray-800"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {/* List */}
      {loading ? (
        <p className="text-sm text-gray-400">Loading connectors…</p>
      ) : error ? (
        <p className="text-sm text-red-500">{error}</p>
      ) : connectors.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-200 p-10 text-center text-sm text-gray-400">
          No connectors yet. Create one to connect an external REST API.
        </div>
      ) : (
        <div className="space-y-2">
          {connectors.map((connector) => {
            const ts = testStatus[connector.pid];
            const isTesting = testing[connector.pid];
            const isExpanded = expandedPid === connector.pid;

            return (
              <div
                key={connector.pid}
                className="overflow-hidden rounded-xl border border-gray-200 bg-white"
              >
                {/* Row header */}
                <div className="flex items-center gap-4 px-5 py-4">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-gray-800">{connector.name}</span>
                      <span
                        className={`rounded-full px-2 py-0.5 text-xs ${
                          connector.enabled
                            ? 'bg-green-100 text-green-700'
                            : 'bg-gray-100 text-gray-500'
                        }`}
                      >
                        {connector.enabled ? 'Active' : 'Disabled'}
                      </span>
                      <span className="rounded-full bg-blue-50 px-2 py-0.5 text-xs text-blue-600">
                        REST
                      </span>
                      {connector.authType && connector.authType !== 'none' && (
                        <span className="rounded-full bg-purple-50 px-2 py-0.5 text-xs text-purple-600">
                          {connector.authType}
                        </span>
                      )}
                    </div>
                    <p className="mt-0.5 truncate font-mono text-xs text-gray-400">
                      {connector.baseUrl}
                    </p>
                  </div>

                  {/* Test result */}
                  {ts !== undefined && ts !== null && (
                    <div
                      className={`flex items-center gap-1.5 text-xs ${
                        ts.success ? 'text-green-600' : 'text-red-500'
                      }`}
                    >
                      {ts.success ? (
                        <CheckCircleIcon className="h-4 w-4" />
                      ) : (
                        <XCircleIcon className="h-4 w-4" />
                      )}
                      {ts.success ? `${ts.durationMs}ms` : 'Failed'}
                    </div>
                  )}

                  {/* Actions */}
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => handleTest(connector.pid)}
                      disabled={isTesting}
                      className="flex items-center gap-1.5 rounded border border-gray-200 px-2.5 py-1.5 text-xs text-gray-500 transition-colors hover:text-indigo-600"
                    >
                      {isTesting ? (
                        <ArrowPathIcon className="h-3.5 w-3.5 animate-spin" />
                      ) : (
                        <SignalIcon className="h-3.5 w-3.5" />
                      )}
                      {isTesting ? 'Testing…' : 'Test'}
                    </button>
                    <button
                      onClick={() => handleEdit(connector)}
                      className="text-gray-400 transition-colors hover:text-indigo-500"
                      title="Edit"
                    >
                      <PencilIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => handleDelete(connector.pid, connector.name)}
                      className="text-gray-400 transition-colors hover:text-red-500"
                      title="Delete"
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => setExpandedPid(isExpanded ? null : connector.pid)}
                      className="text-gray-400 transition-colors hover:text-gray-600"
                    >
                      {isExpanded ? (
                        <ChevronUpIcon className="h-4 w-4" />
                      ) : (
                        <ChevronDownIcon className="h-4 w-4" />
                      )}
                    </button>
                  </div>
                </div>

                {/* Expanded details */}
                {isExpanded && (
                  <div className="space-y-1 border-t border-gray-100 bg-gray-50 px-5 py-3 text-xs text-gray-500">
                    <div>
                      <span className="font-medium text-gray-700">PID:</span> {connector.pid}
                    </div>
                    <div>
                      <span className="font-medium text-gray-700">Timeout:</span>{' '}
                      {connector.timeoutMs}ms
                    </div>
                    <div>
                      <span className="font-medium text-gray-700">Created:</span>{' '}
                      {new Date(connector.createdAt).toLocaleString()}
                    </div>
                    <div className="pt-2 text-gray-400">
                      Use this connector in a NamedQuery by setting{' '}
                      <code className="rounded border bg-white px-1 font-mono">connectorPid</code>{' '}
                      and{' '}
                      <code className="rounded border bg-white px-1 font-mono">
                        connectorEndpointCode
                      </code>{' '}
                      in the query definition.
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
