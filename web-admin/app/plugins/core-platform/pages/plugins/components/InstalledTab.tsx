/**
 * Installed tab — manage locally installed plugins.
 *
 * Enable / disable / uninstall / view detail + upload new plugin (JSON or ZIP)
 * with preview-then-execute two-step import flow. Extracted from the legacy
 * /system/plugins page.
 */

import { useState, useEffect, useCallback } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import {
  PuzzlePieceIcon,
  ArrowUpTrayIcon,
  PlayIcon,
  StopIcon,
  TrashIcon,
  EyeIcon,
  ArrowPathIcon,
  CheckCircleIcon,
  XCircleIcon,
  ExclamationTriangleIcon,
  FolderIcon,
} from '@heroicons/react/24/outline';

type CompatibilityStatus = 'compatible' | 'warn_older' | 'warn_newer' | 'incompatible';

export interface PluginRecord {
  pid: string;
  pluginId: string;
  namespace: string;
  version: string;
  displayName: string;
  description?: string;
  author?: string;
  status: 'installed' | 'enabled' | 'disabled' | 'failed';
  installedAt: string;
  updatedAt: string;
  minPlatformVersion?: string;
  maxPlatformVersion?: string;
  compatibilityStatus?: CompatibilityStatus;
  compatibilityMessage?: string;
}

interface ImportPreviewResult {
  importId: string;
  pluginId: string;
  namespace: string;
  version: string;
  displayName: string;
  valid: boolean;
  isUpgrade: boolean;
  previousVersion?: string;
  errors: string[];
  warnings: string[];
  actionCounts: Record<string, Record<string, number>>;
}

interface Props {
  onCountChange?: (count: number) => void;
  onImportSuccess?: () => void;
}

export default function InstalledTab({ onCountChange, onImportSuccess }: Props) {
  const { showErrorToast } = useToastContext();
  const { t } = useI18n();

  const [plugins, setPlugins] = useState<PluginRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);

  const [showUploadModal, setShowUploadModal] = useState(false);
  const [showPreviewModal, setShowPreviewModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [previewResult, setPreviewResult] = useState<ImportPreviewResult | null>(null);
  const [selectedPlugin, setSelectedPlugin] = useState<PluginRecord | null>(null);

  const fetchPlugins = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetch('/api/plugins');
      if (response.ok) {
        const result = await response.json();
        const data = result.data ?? result;
        const list = Array.isArray(data) ? data : [];
        setPlugins(list);
        onCountChange?.(list.length);
      }
    } finally {
      setLoading(false);
    }
  }, [onCountChange]);

  useEffect(() => {
    fetchPlugins();
  }, [fetchPlugins]);

  const handleFileUpload = async (file: File) => {
    setUploading(true);
    try {
      const formData = new FormData();
      formData.append('file', file);
      const response = await fetch('/api/plugins/import/upload', {
        method: 'post',
        body: formData,
      });
      if (response.ok) {
        const apiResponse = await response.json();
        const result: ImportPreviewResult = apiResponse.data ?? apiResponse;
        setPreviewResult(result);
        setShowUploadModal(false);
        setShowPreviewModal(true);
      } else {
        const error = await response.json();
        showErrorToast(`Upload failed: ${error.desc || error.message || 'Unknown error'}`);
      }
    } finally {
      setUploading(false);
    }
  };

  const executeImport = async () => {
    if (!previewResult) return;
    setUploading(true);
    try {
      const response = await fetch(`/api/plugins/import/${previewResult.importId}/execute`, {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          conflictStrategy: 'overwrite',
          autoDeployProcesses: false,
          autoPublishPages: false,
        }),
      });
      if (response.ok) {
        setShowPreviewModal(false);
        setPreviewResult(null);
        fetchPlugins();
        onImportSuccess?.();
      } else {
        const error = await response.json();
        showErrorToast(`Import failed: ${error.desc || error.message || 'Unknown error'}`);
      }
    } finally {
      setUploading(false);
    }
  };

  const startPlugin = async (pluginPid: string) => {
    const response = await fetch(`/api/plugins/${pluginPid}/enable`, { method: 'post' });
    if (response.ok) fetchPlugins();
    else showErrorToast('Failed to enable plugin');
  };

  const stopPlugin = async (pluginPid: string) => {
    const response = await fetch(`/api/plugins/${pluginPid}/disable`, { method: 'post' });
    if (response.ok) fetchPlugins();
    else showErrorToast('Failed to disable plugin');
  };

  const uninstallPlugin = async (pluginPid: string) => {
    if (!confirm('Are you sure you want to uninstall this plugin?')) return;
    const response = await fetch(`/api/plugins/${pluginPid}/uninstall`, {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ removeData: false }),
    });
    if (response.ok) {
      fetchPlugins();
      onImportSuccess?.();
    } else {
      showErrorToast('Failed to uninstall plugin');
    }
  };

  const CompatibilityBadge = ({
    status,
    minVersion,
    message,
  }: {
    status?: CompatibilityStatus;
    minVersion?: string;
    message?: string;
  }) => {
    if (!status || status === 'compatible') {
      return minVersion ? (
        <span
          className="inline-flex items-center gap-1 rounded-full bg-green-50 px-2 py-0.5 text-xs text-green-600"
          title="Compatible with this platform version"
        >
          <CheckCircleIcon className="h-3 w-3" />
          Compatible
        </span>
      ) : null;
    }
    const configs: Record<
      CompatibilityStatus,
      { bg: string; text: string; label: string; icon: typeof CheckCircleIcon }
    > = {
      compatible: { bg: 'bg-green-50', text: 'text-green-600', label: 'Compatible', icon: CheckCircleIcon },
      warn_newer: { bg: 'bg-amber-50', text: 'text-amber-600', label: '⚠ Version mismatch', icon: ExclamationTriangleIcon },
      warn_older: { bg: 'bg-amber-50', text: 'text-amber-600', label: '⚠ Platform too old', icon: ExclamationTriangleIcon },
      incompatible: { bg: 'bg-red-50', text: 'text-red-600', label: '✗ Incompatible', icon: XCircleIcon },
    };
    const c = configs[status] ?? configs.compatible;
    const Icon = c.icon;
    return (
      <span
        className={`inline-flex cursor-help items-center gap-1 rounded-full px-2 py-0.5 text-xs ${c.bg} ${c.text}`}
        title={message ?? status}
      >
        <Icon className="h-3 w-3" />
        {c.label}
      </span>
    );
  };

  const StatusBadge = ({ status }: { status: string }) => {
    const config: Record<
      string,
      { bg: string; text: string; icon: typeof CheckCircleIcon; labelKey: string }
    > = {
      installed: { bg: 'bg-blue-100', text: 'text-blue-800', icon: CheckCircleIcon, labelKey: 'plugin.status.installed' },
      enabled: { bg: 'bg-green-100', text: 'text-green-800', icon: CheckCircleIcon, labelKey: 'plugin.status.enabled' },
      disabled: { bg: 'bg-gray-100', text: 'text-gray-800', icon: StopIcon, labelKey: 'plugin.status.disabled' },
      failed: { bg: 'bg-red-100', text: 'text-red-800', icon: XCircleIcon, labelKey: 'plugin.status.failed' },
    };
    const c = config[status] || config.installed;
    const Icon = c.icon;
    return (
      <span className={`inline-flex items-center gap-1 rounded-full px-2 py-1 text-xs font-medium ${c.bg} ${c.text}`}>
        <Icon className="h-3 w-3" />
        {t(c.labelKey)}
      </span>
    );
  };

  return (
    <div>
      {/* Action bar */}
      <div className="mb-4 flex justify-end gap-2">
        <button
          onClick={() => fetchPlugins()}
          className="flex items-center gap-2 rounded-md border border-gray-300 bg-white px-4 py-2 text-gray-700 hover:bg-gray-50"
        >
          <ArrowPathIcon className="h-4 w-4" />
          {t('plugin.action.refresh')}
        </button>
        <button
          onClick={() => setShowUploadModal(true)}
          className="flex items-center gap-2 rounded-md bg-indigo-600 px-4 py-2 text-white hover:bg-indigo-700"
        >
          <ArrowUpTrayIcon className="h-4 w-4" />
          {t('plugin.action.upload')}
        </button>
      </div>

      {loading ? (
        <div className="py-12 text-center">
          <ArrowPathIcon className="mx-auto h-8 w-8 animate-spin text-gray-400" />
          <p className="mt-2 text-gray-500">{t('plugin.loading')}</p>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg bg-white shadow">
          {plugins.length === 0 ? (
            <div className="py-12 text-center">
              <FolderIcon className="mx-auto h-12 w-12 text-gray-300" />
              <p className="mt-2 text-gray-500">{t('plugin.empty.installed')}</p>
              <button
                onClick={() => setShowUploadModal(true)}
                className="mt-4 text-indigo-600 hover:text-indigo-800"
              >
                {t('plugin.empty.uploadFirst')}
              </button>
            </div>
          ) : (
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.plugin')}</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.version')}</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.namespace')}</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.status')}</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.installedAt')}</th>
                  <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase">{t('plugin.column.actions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200 bg-white">
                {plugins.map((plugin) => (
                  <tr key={plugin.pid} className="hover:bg-gray-50">
                    <td className="px-6 py-4">
                      <div className="flex items-center">
                        <PuzzlePieceIcon className="mr-3 h-5 w-5 text-indigo-500" />
                        <div>
                          <div className="text-sm font-medium text-gray-900">{plugin.displayName}</div>
                          <div className="text-xs text-gray-500">{plugin.pluginId}</div>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500">
                      <div className="flex flex-col gap-1">
                        <span>{plugin.version}</span>
                        <CompatibilityBadge
                          status={plugin.compatibilityStatus}
                          minVersion={plugin.minPlatformVersion}
                          message={plugin.compatibilityMessage}
                        />
                      </div>
                    </td>
                    <td className="px-6 py-4 font-mono text-sm text-gray-500">{plugin.namespace}</td>
                    <td className="px-6 py-4">
                      <StatusBadge status={plugin.status} />
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500">
                      {new Date(plugin.installedAt).toLocaleString()}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex justify-end gap-2">
                        <button
                          onClick={() => {
                            setSelectedPlugin(plugin);
                            setShowDetailModal(true);
                          }}
                          className="p-1 text-gray-400 hover:text-gray-600"
                          title={t('plugin.action.viewDetail')}
                        >
                          <EyeIcon className="h-5 w-5" />
                        </button>
                        {plugin.status === 'disabled' || plugin.status === 'installed' ? (
                          <button
                            onClick={() => startPlugin(plugin.pid)}
                            className="p-1 text-green-400 hover:text-green-600"
                            title={t('plugin.action.enable')}
                          >
                            <PlayIcon className="h-5 w-5" />
                          </button>
                        ) : plugin.status === 'enabled' ? (
                          <button
                            onClick={() => stopPlugin(plugin.pid)}
                            className="p-1 text-yellow-400 hover:text-yellow-600"
                            title={t('plugin.action.disable')}
                          >
                            <StopIcon className="h-5 w-5" />
                          </button>
                        ) : null}
                        {plugin.status !== 'enabled' && (
                          <button
                            onClick={() => uninstallPlugin(plugin.pid)}
                            className="p-1 text-red-400 hover:text-red-600"
                            title={t('plugin.action.uninstall')}
                          >
                            <TrashIcon className="h-5 w-5" />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {/* Upload Modal */}
      {showUploadModal && (
        <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
          <div className="mx-4 w-full max-w-md rounded-lg bg-white shadow-xl">
            <div className="border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-medium text-gray-900">{t('plugin.upload.title')}</h3>
            </div>
            <div className="p-6">
              <div className="rounded-lg border-2 border-dashed border-gray-300 p-8 text-center">
                <ArrowUpTrayIcon className="mx-auto h-12 w-12 text-gray-400" />
                <p className="mt-2 text-sm text-gray-600">{t('plugin.upload.dragHint')}</p>
                <p className="mt-1 text-xs text-gray-500">{t('plugin.upload.formatHint')}</p>
                <input
                  type="file"
                  accept=".json,.zip"
                  className="hidden"
                  id="plugin-file"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) handleFileUpload(file);
                  }}
                />
                <label
                  htmlFor="plugin-file"
                  className="mt-4 inline-block cursor-pointer rounded-md bg-indigo-600 px-4 py-2 text-white hover:bg-indigo-700"
                >
                  {uploading ? t('plugin.upload.uploading') : t('plugin.upload.selectFile')}
                </label>
              </div>
            </div>
            <div className="flex justify-end border-t border-gray-200 px-6 py-4">
              <button
                onClick={() => setShowUploadModal(false)}
                className="px-4 py-2 text-gray-700 hover:text-gray-900"
              >
                {t('plugin.action.cancel')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Preview Modal */}
      {showPreviewModal && previewResult && (
        <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
          <div className="mx-4 flex max-h-[80vh] w-full max-w-2xl flex-col overflow-hidden rounded-lg bg-white shadow-xl">
            <div className="border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-medium text-gray-900">{t('plugin.preview.title')}</h3>
            </div>
            <div className="flex-1 overflow-y-auto p-6">
              <div className="mb-6">
                <h4 className="mb-2 text-sm font-medium text-gray-700">{t('plugin.preview.info')}</h4>
                <div className="space-y-2 rounded-lg bg-gray-50 p-4">
                  <div className="flex justify-between">
                    <span className="text-gray-500">{t('plugin.field.name')}</span>
                    <span className="font-medium">{previewResult.displayName}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-500">ID</span>
                    <span className="font-mono text-sm">{previewResult.pluginId}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-500">{t('plugin.column.version')}</span>
                    <span>{previewResult.version}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-500">{t('plugin.column.namespace')}</span>
                    <span className="font-mono text-sm">{previewResult.namespace}</span>
                  </div>
                  {previewResult.isUpgrade && (
                    <div className="flex justify-between">
                      <span className="text-gray-500">{t('plugin.preview.upgradeFrom')}</span>
                      <span>{previewResult.previousVersion}</span>
                    </div>
                  )}
                </div>
              </div>

              {!previewResult.valid && previewResult.errors.length > 0 && (
                <div className="mb-6">
                  <h4 className="mb-2 flex items-center gap-1 text-sm font-medium text-red-700">
                    <XCircleIcon className="h-4 w-4" />
                    {t('plugin.preview.validationErrors')}
                  </h4>
                  <ul className="space-y-1 rounded-lg bg-red-50 p-4 text-sm text-red-700">
                    {previewResult.errors.map((error, i) => (
                      <li key={i}>• {error}</li>
                    ))}
                  </ul>
                </div>
              )}

              {previewResult.warnings.length > 0 && (
                <div className="mb-6">
                  <h4 className="mb-2 flex items-center gap-1 text-sm font-medium text-yellow-700">
                    <ExclamationTriangleIcon className="h-4 w-4" />
                    {t('plugin.preview.warnings')}
                  </h4>
                  <ul className="space-y-1 rounded-lg bg-yellow-50 p-4 text-sm text-yellow-700">
                    {previewResult.warnings.map((warning, i) => (
                      <li key={i}>• {warning}</li>
                    ))}
                  </ul>
                </div>
              )}

              {Object.keys(previewResult.actionCounts).length > 0 && (
                <div>
                  <h4 className="mb-2 text-sm font-medium text-gray-700">{t('plugin.preview.actionsToPlan')}</h4>
                  <div className="grid grid-cols-2 gap-2 rounded-lg bg-gray-50 p-4 text-sm">
                    {Object.entries(previewResult.actionCounts).map(([type, actions]) => (
                      <div key={type} className="flex justify-between">
                        <span className="text-gray-500">{type}</span>
                        <span className="font-medium">
                          {Object.entries(actions).map(([action, count]) => (
                            <span key={action} className="ml-2">
                              {action}: {count}
                            </span>
                          ))}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
            <div className="flex justify-end gap-2 border-t border-gray-200 px-6 py-4">
              <button
                onClick={() => {
                  setShowPreviewModal(false);
                  setPreviewResult(null);
                }}
                className="px-4 py-2 text-gray-700 hover:text-gray-900"
              >
                {t('plugin.action.cancel')}
              </button>
              <button
                onClick={executeImport}
                disabled={!previewResult.valid || uploading}
                className="rounded-md bg-indigo-600 px-4 py-2 text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:bg-gray-300"
              >
                {uploading
                  ? t('plugin.import.importing')
                  : previewResult.isUpgrade
                    ? t('plugin.import.confirmUpgrade')
                    : t('plugin.import.confirmInstall')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Detail Modal */}
      {showDetailModal && selectedPlugin && (
        <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
          <div className="mx-4 w-full max-w-lg rounded-lg bg-white shadow-xl">
            <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-medium text-gray-900">{t('plugin.detail.title')}</h3>
              <button
                onClick={() => {
                  setShowDetailModal(false);
                  setSelectedPlugin(null);
                }}
                className="text-gray-400 hover:text-gray-600"
              >
                <XCircleIcon className="h-5 w-5" />
              </button>
            </div>
            <div className="space-y-4 p-6">
              <div>
                <label className="text-sm font-medium text-gray-500">{t('plugin.field.name')}</label>
                <p className="text-gray-900">{selectedPlugin.displayName}</p>
              </div>
              <div>
                <label className="text-sm font-medium text-gray-500">Plugin ID</label>
                <p className="font-mono text-sm text-gray-900">{selectedPlugin.pluginId}</p>
              </div>
              <div>
                <label className="text-sm font-medium text-gray-500">{t('plugin.column.namespace')}</label>
                <p className="font-mono text-sm text-gray-900">{selectedPlugin.namespace}</p>
              </div>
              <div>
                <label className="text-sm font-medium text-gray-500">{t('plugin.column.version')}</label>
                <div className="flex items-center gap-2">
                  <p className="text-gray-900">{selectedPlugin.version}</p>
                  <CompatibilityBadge
                    status={selectedPlugin.compatibilityStatus}
                    minVersion={selectedPlugin.minPlatformVersion}
                    message={selectedPlugin.compatibilityMessage}
                  />
                </div>
                {selectedPlugin.minPlatformVersion && (
                  <p className="mt-0.5 text-xs text-gray-400">
                    Requires platform ≥ {selectedPlugin.minPlatformVersion}
                    {selectedPlugin.maxPlatformVersion && ` (tested up to ${selectedPlugin.maxPlatformVersion})`}
                  </p>
                )}
              </div>
              <div>
                <label className="text-sm font-medium text-gray-500">{t('plugin.column.status')}</label>
                <p>
                  <StatusBadge status={selectedPlugin.status} />
                </p>
              </div>
              {selectedPlugin.description && (
                <div>
                  <label className="text-sm font-medium text-gray-500">{t('plugin.field.description')}</label>
                  <p className="text-gray-900">{selectedPlugin.description}</p>
                </div>
              )}
              {selectedPlugin.author && (
                <div>
                  <label className="text-sm font-medium text-gray-500">{t('plugin.field.author')}</label>
                  <p className="text-gray-900">{selectedPlugin.author}</p>
                </div>
              )}
              <div>
                <label className="text-sm font-medium text-gray-500">{t('plugin.column.installedAt')}</label>
                <p className="text-gray-900">{new Date(selectedPlugin.installedAt).toLocaleString()}</p>
              </div>
              <div>
                <label className="text-sm font-medium text-gray-500">{t('plugin.field.updatedAt')}</label>
                <p className="text-gray-900">{new Date(selectedPlugin.updatedAt).toLocaleString()}</p>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
