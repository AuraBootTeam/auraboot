import { useState, useEffect, useCallback } from 'react';
import {
  ServerStackIcon,
  PlusIcon,
  PencilIcon,
  TrashIcon,
  ArrowsRightLeftIcon,
  ArrowDownTrayIcon,
  ArrowUpTrayIcon,
  CheckCircleIcon,
  XCircleIcon,
  LockClosedIcon,
  LockOpenIcon,
  RocketLaunchIcon,
} from '@heroicons/react/24/outline';
import { Link } from 'react-router';
import { fetchResult } from '~/shared/services/http-client/HttpClient';
import { useToken as useAuthToken } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';

// ---------- Types ----------

interface EnvironmentData {
  pid: string;
  code: string;
  name: string;
  description?: string;
  apiBaseUrl?: string;
  dbConnectionInfo?: Record<string, any>;
  status: string;
  isDefault?: boolean;
  sortOrder?: number;
  createdAt?: string;
  updatedAt?: string;
  // env-layering #4 + #5: lock / promotion-chain audit
  parentPid?: string | null;
  isLocked?: boolean;
  lockedBy?: number | null;
  lockedAt?: string | null;
  lockedReason?: string | null;
}

interface DiffEntry {
  key: string;
  sourceValue: any;
  targetValue: any;
  changeType: 'added' | 'removed' | 'changed';
}

interface DiffResult {
  sourceCode: string;
  targetCode: string;
  differences: DiffEntry[];
}

// ---------- Component ----------

export default function EnvironmentManagement() {
  const token = useAuthToken();
  const { t } = useI18n();

  const [environments, setEnvironments] = useState<EnvironmentData[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editingEnv, setEditingEnv] = useState<EnvironmentData | null>(null);
  const [showDiff, setShowDiff] = useState(false);
  const [diffResult, setDiffResult] = useState<DiffResult | null>(null);
  const [diffSource, setDiffSource] = useState('');
  const [diffTarget, setDiffTarget] = useState('');
  const [error, setError] = useState<string | null>(null);

  // Lock dialog state (env-layering #5 / #15)
  const [lockingEnv, setLockingEnv] = useState<EnvironmentData | null>(null);
  const [lockMode, setLockMode] = useState<'lock' | 'unlock'>('lock');
  const [lockReason, setLockReason] = useState('');
  const [lockSubmitting, setLockSubmitting] = useState(false);

  // Form state
  const [formCode, setFormCode] = useState('');
  const [formName, setFormName] = useState('');
  const [formDescription, setFormDescription] = useState('');
  const [formApiBaseUrl, setFormApiBaseUrl] = useState('');
  const [formDbHost, setFormDbHost] = useState('');
  const [formDbPort, setFormDbPort] = useState('');
  const [formDbName, setFormDbName] = useState('');
  const [formDbUser, setFormDbUser] = useState('');
  const [formIsDefault, setFormIsDefault] = useState(false);
  const [formSortOrder, setFormSortOrder] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  // Fetch environments
  const fetchEnvironments = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchResult<EnvironmentData[]>('/api/admin/environments', {
        method: 'get',
      });
      if (result.success && result.data) {
        setEnvironments(result.data);
      } else {
        setError('Failed to load environments');
      }
    } catch (err) {
      setError('Failed to load environments');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchEnvironments();
  }, [token, fetchEnvironments]);

  // Reset form
  const resetForm = () => {
    setFormCode('');
    setFormName('');
    setFormDescription('');
    setFormApiBaseUrl('');
    setFormDbHost('');
    setFormDbPort('');
    setFormDbName('');
    setFormDbUser('');
    setFormIsDefault(false);
    setFormSortOrder(0);
    setEditingEnv(null);
  };

  // Open create form
  const openCreateForm = () => {
    resetForm();
    setShowForm(true);
  };

  // Open edit form
  const openEditForm = (env: EnvironmentData) => {
    setEditingEnv(env);
    setFormCode(env.code);
    setFormName(env.name);
    setFormDescription(env.description || '');
    setFormApiBaseUrl(env.apiBaseUrl || '');
    setFormDbHost(env.dbConnectionInfo?.host || '');
    setFormDbPort(env.dbConnectionInfo?.port?.toString() || '');
    setFormDbName(env.dbConnectionInfo?.database || '');
    setFormDbUser(env.dbConnectionInfo?.username || '');
    setFormIsDefault(env.isDefault || false);
    setFormSortOrder(env.sortOrder || 0);
    setShowForm(true);
  };

  // Submit form
  const handleSubmit = async () => {
    if (!formCode || !formName) {
      setError('Code and Name are required');
      return;
    }

    setSubmitting(true);
    setError(null);

    const payload = {
      code: formCode,
      name: formName,
      description: formDescription || null,
      apiBaseUrl: formApiBaseUrl || null,
      dbConnectionInfo: formDbHost
        ? {
            host: formDbHost,
            port: formDbPort ? parseInt(formDbPort) : 5432,
            database: formDbName,
            username: formDbUser,
          }
        : null,
      isDefault: formIsDefault,
      sortOrder: formSortOrder,
    };

    try {
      const url = editingEnv
        ? `/api/admin/environments/${editingEnv.pid}`
        : '/api/admin/environments';
      const method = editingEnv ? 'put' : 'post';

      const result = await fetchResult<EnvironmentData>(url, {
        method,
        params: payload,
      });

      if (result.success) {
        setShowForm(false);
        resetForm();
        fetchEnvironments();
      } else {
        setError(typeof result.data === 'string' ? result.data : 'Save failed');
      }
    } catch (err) {
      setError('Save failed');
    } finally {
      setSubmitting(false);
    }
  };

  // Delete environment
  const handleDelete = async (env: EnvironmentData) => {
    if (!confirm(`Delete environment "${env.name}" (${env.code})?`)) return;

    try {
      const result = await fetchResult<void>(`/api/admin/environments/${env.pid}`, {
        method: 'delete',
      });
      if (result.success) {
        setEnvironments((current) => current.filter((item) => item.pid !== env.pid));
        fetchEnvironments();
      } else {
        setError('Delete failed');
      }
    } catch (err) {
      setError('Delete failed');
    }
  };

  // Export environment config
  const handleExport = async (code: string) => {
    try {
      const result = await fetchResult<any>(`/api/admin/environments/${code}/export`, {
        method: 'post',
        token: token ?? undefined,
      });
      if (result.success && result.data) {
        const blob = new Blob([JSON.stringify(result.data, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `env-${code}-config.json`;
        a.click();
        URL.revokeObjectURL(url);
      }
    } catch (err) {
      setError('Export failed');
    }
  };

  // Import environment config
  const handleImport = async (code: string) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json';
    input.onchange = async (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;

      try {
        const text = await file.text();
        const data = JSON.parse(text);
        const result = await fetchResult<EnvironmentData>(
          `/api/admin/environments/${code}/import`,
          {
            method: 'post',
            params: data,
            token: token ?? undefined,
          },
        );
        if (result.success) {
          fetchEnvironments();
        } else {
          setError('Import failed');
        }
      } catch (err) {
        setError('Import failed: invalid JSON file');
      }
    };
    input.click();
  };

  // Diff environments
  const handleDiff = async () => {
    if (!diffSource || !diffTarget || diffSource === diffTarget) {
      setError('Select two different environments to compare');
      return;
    }

    try {
      const result = await fetchResult<DiffResult>(
        `/api/admin/environments/diff?source=${encodeURIComponent(diffSource)}&target=${encodeURIComponent(diffTarget)}`,
        {
          method: 'get',
          token: token ?? undefined,
        },
      );
      if (result.success && result.data) {
        setDiffResult(result.data);
      } else {
        setError('Diff failed');
      }
    } catch (err) {
      setError('Diff failed');
    }
  };

  // Open lock/unlock dialog (env-layering #15)
  const openLockDialog = (env: EnvironmentData, mode: 'lock' | 'unlock') => {
    setLockingEnv(env);
    setLockMode(mode);
    setLockReason('');
  };

  const closeLockDialog = () => {
    setLockingEnv(null);
    setLockReason('');
  };

  const handleLockSubmit = async () => {
    if (!lockingEnv) return;
    if (!lockReason.trim()) {
      setError(`${lockMode === 'lock' ? 'Lock' : 'Unlock'} reason is required`);
      return;
    }

    setLockSubmitting(true);
    setError(null);
    try {
      const result = await fetchResult<EnvironmentData>(
        `/api/admin/environments/${lockingEnv.pid}/${lockMode}`,
        {
          method: 'post',
          params: { reason: lockReason.trim() },
          token: token ?? undefined,
        },
      );
      if (result.success) {
        closeLockDialog();
        fetchEnvironments();
      } else {
        setError(`${lockMode === 'lock' ? 'Lock' : 'Unlock'} failed`);
      }
    } catch (err) {
      setError(`${lockMode === 'lock' ? 'Lock' : 'Unlock'} failed`);
    } finally {
      setLockSubmitting(false);
    }
  };

  // ---------- Render ----------

  const statusBadge = (status: string) => {
    const isActive = status === 'active';
    return (
      <span
        className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
          isActive
            ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
            : 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400'
        }`}
      >
        {isActive ? <CheckCircleIcon className="h-3 w-3" /> : <XCircleIcon className="h-3 w-3" />}
        {status}
      </span>
    );
  };

  return (
    <div className="mx-auto max-w-6xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <ServerStackIcon className="h-8 w-8 text-indigo-600 dark:text-indigo-400" />
          <div>
            <h1 className="text-2xl font-bold text-gray-900 dark:text-white">
              Environment Management
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400">
              Manage deployment environments (dev, staging, prod)
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => setShowDiff(!showDiff)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700"
          >
            <ArrowsRightLeftIcon className="h-4 w-4" />
            Compare
          </button>
          <button
            onClick={openCreateForm}
            className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-2 text-sm font-medium text-white hover:bg-indigo-700"
          >
            <PlusIcon className="h-4 w-4" />
            New Environment
          </button>
        </div>
      </div>

      {/* Error banner */}
      {error && (
        <div className="mb-4 flex justify-between rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400">
          <span>{error}</span>
          <button onClick={() => setError(null)} className="font-medium hover:underline">
            Dismiss
          </button>
        </div>
      )}

      {/* Diff panel */}
      {showDiff && (
        <div className="mb-6 rounded-lg border border-gray-200 bg-white p-4 shadow-sm dark:border-gray-700 dark:bg-gray-800">
          <h3 className="mb-3 text-sm font-semibold text-gray-900 dark:text-white">
            Configuration Diff
          </h3>
          <div className="mb-4 flex items-end gap-3">
            <div className="flex-1">
              <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                Source
              </label>
              <select
                value={diffSource}
                onChange={(e) => setDiffSource(e.target.value)}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              >
                <option value="">Select...</option>
                {environments.map((env) => (
                  <option key={env.pid} value={env.code}>
                    {env.name} ({env.code})
                  </option>
                ))}
              </select>
            </div>
            <div className="flex-1">
              <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                Target
              </label>
              <select
                value={diffTarget}
                onChange={(e) => setDiffTarget(e.target.value)}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              >
                <option value="">Select...</option>
                {environments.map((env) => (
                  <option key={env.pid} value={env.code}>
                    {env.name} ({env.code})
                  </option>
                ))}
              </select>
            </div>
            <button
              onClick={handleDiff}
              className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
            >
              Compare
            </button>
          </div>

          {/* Diff results */}
          {diffResult && (
            <div className="overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 dark:bg-gray-900">
                  <tr>
                    <th className="px-4 py-2 text-left font-medium text-gray-600 dark:text-gray-400">
                      Key
                    </th>
                    <th className="px-4 py-2 text-left font-medium text-gray-600 dark:text-gray-400">
                      {diffResult.sourceCode}
                    </th>
                    <th className="px-4 py-2 text-left font-medium text-gray-600 dark:text-gray-400">
                      {diffResult.targetCode}
                    </th>
                    <th className="px-4 py-2 text-left font-medium text-gray-600 dark:text-gray-400">
                      Change
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
                  {diffResult.differences.length === 0 ? (
                    <tr>
                      <td
                        colSpan={4}
                        className="px-4 py-6 text-center text-gray-500 dark:text-gray-400"
                      >
                        No differences found
                      </td>
                    </tr>
                  ) : (
                    diffResult.differences.map((d, i) => (
                      <tr
                        key={i}
                        className={
                          d.changeType === 'added'
                            ? 'bg-green-50 dark:bg-green-900/10'
                            : d.changeType === 'removed'
                              ? 'bg-red-50 dark:bg-red-900/10'
                              : 'bg-yellow-50 dark:bg-yellow-900/10'
                        }
                      >
                        <td className="px-4 py-2 font-mono text-xs text-gray-800 dark:text-gray-200">
                          {d.key}
                        </td>
                        <td className="px-4 py-2 text-xs text-gray-700 dark:text-gray-300">
                          {d.sourceValue != null ? String(d.sourceValue) : '-'}
                        </td>
                        <td className="px-4 py-2 text-xs text-gray-700 dark:text-gray-300">
                          {d.targetValue != null ? String(d.targetValue) : '-'}
                        </td>
                        <td className="px-4 py-2">
                          <span
                            className={`rounded px-1.5 py-0.5 text-xs font-medium ${
                              d.changeType === 'added'
                                ? 'bg-green-200 text-green-800'
                                : d.changeType === 'removed'
                                  ? 'bg-red-200 text-red-800'
                                  : 'bg-yellow-200 text-yellow-800'
                            }`}
                          >
                            {d.changeType}
                          </span>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Environment list */}
      {loading ? (
        <div className="py-12 text-center text-gray-500 dark:text-gray-400">Loading...</div>
      ) : environments.length === 0 ? (
        <div data-testid="env-empty-state" className="rounded-lg border border-gray-200 bg-white py-12 text-center dark:border-gray-700 dark:bg-gray-800">
          <ServerStackIcon className="mx-auto mb-3 h-12 w-12 text-gray-300 dark:text-gray-600" />
          <p className="mb-4 text-gray-500 dark:text-gray-400">No environments configured yet</p>
          <button
            onClick={openCreateForm}
            className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
          >
            <PlusIcon className="h-4 w-4" />
            Create your first environment
          </button>
        </div>
      ) : (
        <div data-testid="env-list-grid" className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {environments.map((env) => (
            <div
              key={env.pid}
              className="rounded-lg border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-700 dark:bg-gray-800"
            >
              <div className="mb-3 flex items-start justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="text-lg font-semibold text-gray-900 dark:text-white">
                      {env.name}
                    </h3>
                    {env.isDefault && (
                      <span className="rounded bg-indigo-100 px-1.5 py-0.5 text-xs font-medium text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-400">
                        Default
                      </span>
                    )}
                    {env.isLocked && (
                      <span
                        className="inline-flex items-center gap-1 rounded bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-900/30 dark:text-amber-400"
                        title={env.lockedReason || 'Locked'}
                      >
                        <LockClosedIcon className="h-3 w-3" />
                        Locked
                      </span>
                    )}
                  </div>
                  <p className="font-mono text-sm text-gray-500 dark:text-gray-400">{env.code}</p>
                </div>
                {statusBadge(env.status)}
              </div>

              {env.description && (
                <p className="mb-3 text-sm text-gray-600 dark:text-gray-300">{env.description}</p>
              )}

              {env.apiBaseUrl && (
                <div className="mb-1 text-xs text-gray-500 dark:text-gray-400">
                  <span className="font-medium">API:</span>{' '}
                  <code className="rounded bg-gray-100 px-1 py-0.5 dark:bg-gray-700">
                    {env.apiBaseUrl}
                  </code>
                </div>
              )}

              {env.dbConnectionInfo?.host && (
                <div className="mb-3 text-xs text-gray-500 dark:text-gray-400">
                  <span className="font-medium">DB:</span>{' '}
                  <code className="rounded bg-gray-100 px-1 py-0.5 dark:bg-gray-700">
                    {env.dbConnectionInfo.host}:{env.dbConnectionInfo.port || 5432}/
                    {env.dbConnectionInfo.database}
                  </code>
                </div>
              )}

              {/* Actions */}
              <div className="mt-4 flex items-center gap-1 border-t border-gray-100 pt-3 dark:border-gray-700">
                <button
                  onClick={() => openEditForm(env)}
                  className="rounded p-1.5 text-gray-400 hover:text-indigo-600 dark:hover:text-indigo-400"
                  title="Edit"
                >
                  <PencilIcon className="h-4 w-4" />
                </button>
                <button
                  onClick={() => handleExport(env.code)}
                  className="rounded p-1.5 text-gray-400 hover:text-green-600 dark:hover:text-green-400"
                  title="Export config"
                >
                  <ArrowDownTrayIcon className="h-4 w-4" />
                </button>
                <button
                  onClick={() => handleImport(env.code)}
                  className="rounded p-1.5 text-gray-400 hover:text-blue-600 dark:hover:text-blue-400"
                  title="Import config"
                >
                  <ArrowUpTrayIcon className="h-4 w-4" />
                </button>
                {/* env-layering #5 / #15: lock toggle */}
                {env.isLocked ? (
                  <button
                    onClick={() => openLockDialog(env, 'unlock')}
                    className="rounded p-1.5 text-amber-500 hover:text-amber-700 dark:hover:text-amber-400"
                    title={`Unlock — locked by user ${env.lockedBy ?? '?'}: ${env.lockedReason ?? ''}`}
                  >
                    <LockOpenIcon className="h-4 w-4" />
                  </button>
                ) : (
                  <button
                    onClick={() => openLockDialog(env, 'lock')}
                    className="rounded p-1.5 text-gray-400 hover:text-amber-600 dark:hover:text-amber-400"
                    title="Lock environment (require four-eyes promotion to write)"
                  >
                    <LockClosedIcon className="h-4 w-4" />
                  </button>
                )}
                {/* env-layering #15: nav into the promotion UI scoped to this env */}
                <Link
                  to={`/admin/promotions?env=${encodeURIComponent(env.code)}`}
                  className="rounded p-1.5 text-gray-400 hover:text-indigo-600 dark:hover:text-indigo-400"
                  title="Promotions for this environment"
                >
                  <RocketLaunchIcon className="h-4 w-4" />
                </Link>
                <div className="flex-1" />
                <button
                  onClick={() => handleDelete(env)}
                  className="rounded p-1.5 text-gray-400 hover:text-red-600 dark:hover:text-red-400"
                  title="Delete"
                >
                  <TrashIcon className="h-4 w-4" />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Lock / Unlock reason modal (env-layering #15) */}
      {lockingEnv && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="mx-4 w-full max-w-md rounded-xl bg-white shadow-xl dark:bg-gray-800">
            <div className="p-6">
              <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold text-gray-900 dark:text-white">
                {lockMode === 'lock' ? (
                  <LockClosedIcon className="h-5 w-5 text-amber-600" />
                ) : (
                  <LockOpenIcon className="h-5 w-5 text-amber-600" />
                )}
                {lockMode === 'lock' ? 'Lock Environment' : 'Unlock Environment'}
              </h2>
              <p className="mb-4 text-sm text-gray-500 dark:text-gray-400">
                {lockMode === 'lock'
                  ? `Lock "${lockingEnv.name}" (${lockingEnv.code}). Direct writes will be rejected; only promotions can write to it.`
                  : `Unlock "${lockingEnv.name}" (${lockingEnv.code}). Direct writes will be allowed again.`}
              </p>
              {lockMode === 'unlock' && lockingEnv.lockedReason && (
                <div className="mb-4 rounded-lg bg-amber-50 p-3 text-xs text-amber-800 dark:bg-amber-900/20 dark:text-amber-400">
                  <span className="font-medium">Currently locked:</span> {lockingEnv.lockedReason}
                </div>
              )}
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                Reason (required)
              </label>
              <textarea
                value={lockReason}
                onChange={(e) => setLockReason(e.target.value)}
                rows={3}
                placeholder={lockMode === 'lock' ? 'e.g. cutover freeze for release 1.2' : 'e.g. release shipped, resuming dev'}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              />
              <div className="mt-6 flex justify-end gap-2">
                <button
                  onClick={closeLockDialog}
                  className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700"
                >
                  Cancel
                </button>
                <button
                  onClick={handleLockSubmit}
                  disabled={lockSubmitting || !lockReason.trim()}
                  className={`rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50 ${
                    lockMode === 'lock'
                      ? 'bg-amber-600 hover:bg-amber-700'
                      : 'bg-indigo-600 hover:bg-indigo-700'
                  }`}
                >
                  {lockSubmitting ? 'Saving...' : lockMode === 'lock' ? 'Lock' : 'Unlock'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Create/Edit Modal */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="mx-4 max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-xl bg-white shadow-xl dark:bg-gray-800">
            <div className="p-6">
              <h2 className="mb-4 text-lg font-semibold text-gray-900 dark:text-white">
                {editingEnv ? 'Edit Environment' : 'New Environment'}
              </h2>

              <div className="space-y-4">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Code *
                  </label>
                  <input
                    type="text"
                    value={formCode}
                    onChange={(e) => setFormCode(e.target.value)}
                    placeholder="e.g. dev, staging, prod"
                    disabled={!!editingEnv}
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 disabled:opacity-50 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  />
                </div>

                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Name *
                  </label>
                  <input
                    type="text"
                    value={formName}
                    onChange={(e) => setFormName(e.target.value)}
                    placeholder="e.g. Development, Staging, Production"
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  />
                </div>

                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Description
                  </label>
                  <textarea
                    value={formDescription}
                    onChange={(e) => setFormDescription(e.target.value)}
                    rows={2}
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  />
                </div>

                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    API Base URL
                  </label>
                  <input
                    type="text"
                    value={formApiBaseUrl}
                    onChange={(e) => setFormApiBaseUrl(e.target.value)}
                    placeholder="https://api.example.com"
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  />
                </div>

                {/* DB connection section */}
                <div className="rounded-lg border border-gray-200 p-3 dark:border-gray-700">
                  <h4 className="mb-3 text-xs font-semibold text-gray-500 uppercase dark:text-gray-400">
                    Database Connection
                  </h4>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="mb-1 block text-xs text-gray-600 dark:text-gray-400">
                        Host
                      </label>
                      <input
                        type="text"
                        value={formDbHost}
                        onChange={(e) => setFormDbHost(e.target.value)}
                        placeholder="localhost"
                        className="w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs text-gray-600 dark:text-gray-400">
                        Port
                      </label>
                      <input
                        type="text"
                        value={formDbPort}
                        onChange={(e) => setFormDbPort(e.target.value)}
                        placeholder="5432"
                        className="w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs text-gray-600 dark:text-gray-400">
                        Database
                      </label>
                      <input
                        type="text"
                        value={formDbName}
                        onChange={(e) => setFormDbName(e.target.value)}
                        placeholder="aura_boot"
                        className="w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                      />
                    </div>
                    <div>
                      <label className="mb-1 block text-xs text-gray-600 dark:text-gray-400">
                        Username
                      </label>
                      <input
                        type="text"
                        value={formDbUser}
                        onChange={(e) => setFormDbUser(e.target.value)}
                        placeholder="postgres"
                        className="w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                      />
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-4">
                  <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
                    <input
                      type="checkbox"
                      checked={formIsDefault}
                      onChange={(e) => setFormIsDefault(e.target.checked)}
                      className="rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
                    />
                    Default environment
                  </label>
                  <div className="flex items-center gap-2">
                    <label className="text-sm text-gray-700 dark:text-gray-300">Order:</label>
                    <input
                      type="number"
                      value={formSortOrder}
                      onChange={(e) => setFormSortOrder(parseInt(e.target.value) || 0)}
                      className="w-16 rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    />
                  </div>
                </div>
              </div>

              {/* Actions */}
              <div className="mt-6 flex justify-end gap-2">
                <button
                  onClick={() => {
                    setShowForm(false);
                    resetForm();
                  }}
                  className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700"
                >
                  Cancel
                </button>
                <button
                  onClick={handleSubmit}
                  disabled={submitting}
                  className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
                >
                  {submitting ? 'Saving...' : editingEnv ? 'Update' : 'Create'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
