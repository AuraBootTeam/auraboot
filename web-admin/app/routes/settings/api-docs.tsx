/**
 * API Documentation Page — GAP-019
 *
 * Provides quick access to the OpenAPI/Swagger UI and API key management.
 * Opens Swagger UI in a new tab to avoid iframe sandbox restrictions.
 */

import { useState, useEffect } from 'react';
import {
  DocumentTextIcon,
  ArrowTopRightOnSquareIcon,
  KeyIcon,
  PlusIcon,
  ClipboardDocumentIcon,
  TrashIcon,
  CheckCircleIcon,
  ExclamationTriangleIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';

// ─── Types ────────────────────────────────────────────────────────────────────

interface ApiKey {
  pid: string;
  name: string;
  keyPrefix: string;
  permissions: string[];
  expiresAt?: string;
  lastUsedAt?: string;
  enabled: boolean;
  createdAt: string;
}

interface CreateApiKeyResponse {
  pid: string;
  name: string;
  keyPrefix: string;
  fullKey: string; // only returned once on creation
  permissions: string[];
  expiresAt?: string;
  enabled: boolean;
  createdAt: string;
}

// ─── API helpers ──────────────────────────────────────────────────────────────

async function fetchApiKeys(): Promise<ApiKey[]> {
  const res = await fetch('/api/auth/api-keys', {
    headers: { Authorization: `Bearer ${localStorage.getItem('jwt')}` },
  });
  if (!res.ok) throw new Error(`Failed to fetch API keys: ${res.status}`);
  const json = await res.json();
  return json.data ?? [];
}

async function createApiKey(name: string, permissions: string[]): Promise<CreateApiKeyResponse> {
  const res = await fetch('/api/auth/api-keys', {
    method: 'post',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${localStorage.getItem('jwt')}`,
    },
    body: JSON.stringify({ name, permissions }),
  });
  if (!res.ok) throw new Error(`Failed to create API key: ${res.status}`);
  const json = await res.json();
  return json.data;
}

async function deleteApiKey(pid: string): Promise<void> {
  const res = await fetch(`/api/auth/api-keys/${pid}`, {
    method: 'delete',
    headers: { Authorization: `Bearer ${localStorage.getItem('jwt')}` },
  });
  if (!res.ok) throw new Error(`Failed to delete API key: ${res.status}`);
}

// ─── API Groups ───────────────────────────────────────────────────────────────

const API_GROUPS = [
  { name: 'All APIs', group: 'all', description: 'Complete API reference for all endpoints' },
  { name: 'Authentication', group: 'auth', description: 'Login, register, current user, API keys' },
  {
    name: 'Meta (Models, Fields, Commands)',
    group: 'meta',
    description: 'Low-code DSL management endpoints',
  },
  { name: 'Dynamic Data', group: 'dynamic', description: 'Runtime CRUD for DSL-defined models' },
  { name: 'Plugins', group: 'plugins', description: 'Plugin import, management, and marketplace' },
  {
    name: 'API Connectors',
    group: 'connectors',
    description: 'External REST API connector management',
  },
  { name: 'BPM / Workflow', group: 'bpm', description: 'Business process management endpoints' },
];

// ─── Component ────────────────────────────────────────────────────────────────

export default function ApiDocsPage() {
  const { t } = useI18n();
  const { showToast } = useToastContext();

  const [apiKeys, setApiKeys] = useState<ApiKey[]>([]);
  const [loadingKeys, setLoadingKeys] = useState(false);
  const [keysError, setKeysError] = useState<string | null>(null);
  const [newlyCreatedKey, setNewlyCreatedKey] = useState<string | null>(null);
  const [newKeyName, setNewKeyName] = useState('');
  const [creating, setCreating] = useState(false);
  const [showCreateForm, setShowCreateForm] = useState(false);

  // Load API keys
  useEffect(() => {
    setLoadingKeys(true);
    fetchApiKeys()
      .then(setApiKeys)
      .catch((err) => setKeysError(err.message))
      .finally(() => setLoadingKeys(false));
  }, []);

  const handleOpenSwagger = (group?: string) => {
    const base = window.location.origin.replace('5173', '6443');
    const url =
      group && group !== 'all'
        ? `${base}/swagger-ui.html?configUrl=/v3/api-docs/swagger-config&urls.primaryName=${group}`
        : `${base}/swagger-ui.html`;
    window.open(url, '_blank', 'noopener,noreferrer');
  };

  const handleCreateKey = async () => {
    if (!newKeyName.trim()) return;
    setCreating(true);
    try {
      const result = await createApiKey(newKeyName.trim(), ['*']);
      setNewlyCreatedKey(result.fullKey);
      setApiKeys((prev) => [
        {
          pid: result.pid,
          name: result.name,
          keyPrefix: result.keyPrefix,
          permissions: result.permissions,
          expiresAt: result.expiresAt,
          enabled: result.enabled,
          createdAt: result.createdAt,
        },
        ...prev,
      ]);
      setNewKeyName('');
      setShowCreateForm(false);
      showToast('API key created successfully', 'success');
    } catch (err: any) {
      showToast(err.message ?? 'Failed to create API key', 'error');
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteKey = async (pid: string, name: string) => {
    if (!confirm(`Delete API key "${name}"? This cannot be undone.`)) return;
    try {
      await deleteApiKey(pid);
      setApiKeys((prev) => prev.filter((k) => k.pid !== pid));
      showToast('API key deleted', 'success');
    } catch (err: any) {
      showToast(err.message ?? 'Failed to delete API key', 'error');
    }
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text).then(() => showToast('Copied to clipboard', 'success'));
  };

  return (
    <div className="mx-auto max-w-5xl space-y-8 p-6">
      {/* Header */}
      <div>
        <h1 className="flex items-center gap-2 text-2xl font-semibold text-gray-900">
          <DocumentTextIcon className="h-7 w-7 text-indigo-500" />
          API Documentation
        </h1>
        <p className="mt-1 text-sm text-gray-500">
          Browse the interactive Swagger UI or manage API keys for programmatic access.
        </p>
      </div>

      {/* Quick Access */}
      <section>
        <h2 className="mb-3 text-base font-medium text-gray-700">API Reference</h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {API_GROUPS.map((g) => (
            <button
              key={g.group}
              onClick={() => handleOpenSwagger(g.group)}
              className="flex items-start gap-3 rounded-lg border border-gray-200 bg-white p-4 text-left shadow-sm transition-all hover:border-indigo-300 hover:shadow-md"
            >
              <DocumentTextIcon className="mt-0.5 h-5 w-5 shrink-0 text-indigo-400" />
              <div className="min-w-0">
                <div className="flex items-center gap-1 text-sm font-medium text-gray-800">
                  {g.name}
                  <ArrowTopRightOnSquareIcon className="h-3.5 w-3.5 text-gray-400" />
                </div>
                <p className="mt-0.5 line-clamp-2 text-xs text-gray-500">{g.description}</p>
              </div>
            </button>
          ))}
        </div>
        <p className="mt-3 text-xs text-gray-400">
          Opens Swagger UI in a new tab. Authenticate using: POST /api/auth/login → copy
          <code className="mx-1 rounded bg-gray-100 px-1 font-mono">data.jwt</code>→ click
          Authorize.
        </p>
      </section>

      {/* Newly created key warning */}
      {newlyCreatedKey && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 p-4">
          <div className="flex items-start gap-3">
            <ExclamationTriangleIcon className="mt-0.5 h-5 w-5 shrink-0 text-amber-500" />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-amber-800">
                Save your API key — it will not be shown again
              </p>
              <div className="mt-2 flex items-center gap-2">
                <code className="flex-1 rounded bg-amber-100 px-2 py-1 font-mono text-xs break-all">
                  {newlyCreatedKey}
                </code>
                <button
                  onClick={() => handleCopy(newlyCreatedKey)}
                  className="shrink-0 rounded p-1.5 hover:bg-amber-100"
                  title="Copy"
                >
                  <ClipboardDocumentIcon className="h-4 w-4 text-amber-600" />
                </button>
              </div>
            </div>
            <button
              onClick={() => setNewlyCreatedKey(null)}
              className="text-lg leading-none text-amber-400 hover:text-amber-600"
            >
              ×
            </button>
          </div>
        </div>
      )}

      {/* API Key Management */}
      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="flex items-center gap-2 text-base font-medium text-gray-700">
            <KeyIcon className="h-5 w-5 text-gray-500" />
            API Keys
          </h2>
          <button
            onClick={() => setShowCreateForm((v) => !v)}
            className="flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5 text-sm text-white transition-colors hover:bg-indigo-700"
          >
            <PlusIcon className="h-4 w-4" />
            New API Key
          </button>
        </div>

        {/* Create form */}
        {showCreateForm && (
          <div className="mb-4 flex items-end gap-3 rounded-lg border border-gray-200 bg-gray-50 p-4">
            <div className="flex-1">
              <label className="mb-1 block text-xs font-medium text-gray-700">Key Name</label>
              <input
                type="text"
                value={newKeyName}
                onChange={(e) => setNewKeyName(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleCreateKey()}
                placeholder="e.g. CI/CD Pipeline"
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-indigo-500 focus:outline-none"
              />
            </div>
            <button
              onClick={handleCreateKey}
              disabled={creating || !newKeyName.trim()}
              className="rounded-md bg-indigo-600 px-4 py-2 text-sm text-white transition-colors hover:bg-indigo-700 disabled:opacity-50"
            >
              {creating ? 'Creating…' : 'Create'}
            </button>
            <button
              onClick={() => setShowCreateForm(false)}
              className="px-3 py-2 text-sm text-gray-500 hover:text-gray-700"
            >
              Cancel
            </button>
          </div>
        )}

        {/* Keys list */}
        {loadingKeys ? (
          <p className="text-sm text-gray-400">Loading API keys…</p>
        ) : keysError ? (
          <p className="text-sm text-red-500">{keysError}</p>
        ) : apiKeys.length === 0 ? (
          <div className="rounded-lg border border-dashed border-gray-200 p-6 text-center text-sm text-gray-400">
            No API keys yet. Create one to access the API programmatically.
          </div>
        ) : (
          <div className="overflow-hidden rounded-lg border border-gray-200">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-xs tracking-wide text-gray-500 uppercase">
                <tr>
                  <th className="px-4 py-2 text-left">Name</th>
                  <th className="px-4 py-2 text-left">Key Prefix</th>
                  <th className="px-4 py-2 text-left">Status</th>
                  <th className="px-4 py-2 text-left">Expires</th>
                  <th className="px-4 py-2 text-left">Last Used</th>
                  <th className="px-4 py-2 text-left">Created</th>
                  <th className="px-4 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {apiKeys.map((key) => (
                  <tr key={key.pid} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium text-gray-800">{key.name}</td>
                    <td className="px-4 py-3 font-mono text-xs text-gray-500">{key.keyPrefix}…</td>
                    <td className="px-4 py-3">
                      {key.enabled ? (
                        <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-xs text-green-700">
                          <CheckCircleIcon className="h-3 w-3" /> Active
                        </span>
                      ) : (
                        <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-400">
                          Disabled
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-gray-500">
                      {key.expiresAt ? new Date(key.expiresAt).toLocaleDateString() : '—'}
                    </td>
                    <td className="px-4 py-3 text-gray-500">
                      {key.lastUsedAt ? new Date(key.lastUsedAt).toLocaleDateString() : 'Never'}
                    </td>
                    <td className="px-4 py-3 text-gray-500">
                      {new Date(key.createdAt).toLocaleDateString()}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => handleDeleteKey(key.pid, key.name)}
                        className="text-gray-400 transition-colors hover:text-red-500"
                        title="Delete"
                      >
                        <TrashIcon className="h-4 w-4" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="mt-2 text-xs text-gray-400">
          Use API keys in the{' '}
          <code className="rounded bg-gray-100 px-1 font-mono">
            Authorization: Bearer &lt;key&gt;
          </code>{' '}
          header.
        </p>
      </section>
    </div>
  );
}
