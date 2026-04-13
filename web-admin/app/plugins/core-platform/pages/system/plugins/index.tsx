/**
 * Plugin Management Page
 *
 * Provides UI for managing plugins:
 * - View installed plugins
 * - Upload new plugins (JSON manifest or ZIP)
 * - Activate/Deactivate plugins
 * - View plugin details and resources
 * - Uninstall plugins
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
  DocumentTextIcon,
  FolderIcon,
} from '@heroicons/react/24/outline';
import { PuzzlePieceIcon as PuzzlePieceSolidIcon } from '@heroicons/react/24/solid';

// Types
type CompatibilityStatus = 'compatible' | 'warn_older' | 'warn_newer' | 'incompatible';

interface PluginRecord {
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

interface ImportHistory {
  importId: string;
  pluginId: string;
  namespace: string;
  version: string;
  status: 'success' | 'failed' | 'rolled_back';
  importType: 'install' | 'upgrade';
  sourceType: 'json' | 'zip';
  sourceName: string;
  startedAt: string;
  completedAt?: string;
  errorMessage?: string;
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

/**
 * Plugin Management Page Component
 */
export default function PluginManagement() {
  const { showErrorToast } = useToastContext();
  const { t } = useI18n();
  // State
  const [plugins, setPlugins] = useState<PluginRecord[]>([]);
  const [importHistory, setImportHistory] = useState<ImportHistory[]>([]);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [activeTab, setActiveTab] = useState<'installed' | 'history'>('installed');

  // Modal state
  const [showUploadModal, setShowUploadModal] = useState(false);
  const [showPreviewModal, setShowPreviewModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [previewResult, setPreviewResult] = useState<ImportPreviewResult | null>(null);
  const [selectedPlugin, setSelectedPlugin] = useState<PluginRecord | null>(null);

  // Fetch plugins from database (configuration plugins)
  const fetchPlugins = useCallback(async () => {
    setLoading(true);
    try {
      const response = await fetch('/api/plugins');
      if (response.ok) {
        const result = await response.json();
        // Handle ApiResponse format: { code, data, desc }
        const data = result.data ?? result;
        setPlugins(Array.isArray(data) ? data : []);
      }
    } catch (error) {
      console.error('Failed to fetch plugins:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  // Fetch import history
  const fetchHistory = useCallback(async () => {
    try {
      const response = await fetch('/api/plugins/import/history?limit=20');
      if (response.ok) {
        const result = await response.json();
        // Handle ApiResponse format: { code, data, desc }
        const data = result.data ?? result;
        setImportHistory(Array.isArray(data) ? data : []);
      }
    } catch (error) {
      console.error('Failed to fetch import history:', error);
    }
  }, []);

  // Initial load
  useEffect(() => {
    fetchPlugins();
    fetchHistory();
  }, [fetchPlugins, fetchHistory]);

  // Handle file upload
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
        // Handle ApiResponse format: { code, data, desc }
        const result: ImportPreviewResult = apiResponse.data ?? apiResponse;
        setPreviewResult(result);
        setShowUploadModal(false);
        setShowPreviewModal(true);
      } else {
        const error = await response.json();
        showErrorToast(`Upload failed: ${error.desc || error.message || 'Unknown error'}`);
      }
    } catch (error) {
      console.error('Upload error:', error);
      showErrorToast('Failed to upload file');
    } finally {
      setUploading(false);
    }
  };

  // Execute import
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
        fetchHistory();
      } else {
        const error = await response.json();
        showErrorToast(`Import failed: ${error.desc || error.message || 'Unknown error'}`);
      }
    } catch (error) {
      console.error('Import error:', error);
      showErrorToast('Failed to execute import');
    } finally {
      setUploading(false);
    }
  };

  // Enable plugin
  const startPlugin = async (pluginPid: string) => {
    try {
      const response = await fetch(`/api/plugins/${pluginPid}/enable`, {
        method: 'post',
      });
      if (response.ok) {
        fetchPlugins();
      } else {
        showErrorToast('Failed to enable plugin');
      }
    } catch (error) {
      console.error('Enable plugin error:', error);
    }
  };

  // Disable plugin
  const stopPlugin = async (pluginPid: string) => {
    try {
      const response = await fetch(`/api/plugins/${pluginPid}/disable`, {
        method: 'post',
      });
      if (response.ok) {
        fetchPlugins();
      } else {
        showErrorToast('Failed to disable plugin');
      }
    } catch (error) {
      console.error('Disable plugin error:', error);
    }
  };

  // Uninstall plugin
  const uninstallPlugin = async (pluginPid: string) => {
    if (!confirm('Are you sure you want to uninstall this plugin?')) return;

    try {
      const response = await fetch(`/api/plugins/${pluginPid}/uninstall`, {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ removeData: false }),
      });
      if (response.ok) {
        fetchPlugins();
        fetchHistory();
      } else {
        showErrorToast('Failed to uninstall plugin');
      }
    } catch (error) {
      console.error('Uninstall error:', error);
    }
  };

  // Compatibility badge — shows version compatibility status
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
        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs bg-green-50 text-green-600"
              title="Compatible with this platform version">
          <CheckCircleIcon className="w-3 h-3" />
          Compatible
        </span>
      ) : null;
    }
    const configs: Record<CompatibilityStatus, { bg: string; text: string; label: string; icon: typeof CheckCircleIcon }> = {
      compatible: { bg: 'bg-green-50', text: 'text-green-600', label: 'Compatible', icon: CheckCircleIcon },
      warn_newer: { bg: 'bg-amber-50', text: 'text-amber-600', label: '⚠ Version mismatch', icon: ExclamationTriangleIcon },
      warn_older: { bg: 'bg-amber-50', text: 'text-amber-600', label: '⚠ Platform too old', icon: ExclamationTriangleIcon },
      incompatible: { bg: 'bg-red-50', text: 'text-red-600', label: '✗ Incompatible', icon: XCircleIcon },
    };
    const c = configs[status];
    const Icon = c.icon;
    return (
      <span
        className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs ${c.bg} ${c.text} cursor-help`}
        title={message ?? status}
      >
        <Icon className="w-3 h-3" />
        {c.label}
      </span>
    );
  };

  // Status badge — aligned with backend PluginStatus enum: installed, enabled, disabled, failed
  const StatusBadge = ({ status }: { status: string }) => {
    const config: Record<string, { bg: string; text: string; icon: typeof CheckCircleIcon; labelKey: string }> = {
      installed: { bg: 'bg-blue-100', text: 'text-blue-800', icon: CheckCircleIcon, labelKey: 'plugin.status.installed' },
      enabled: { bg: 'bg-green-100', text: 'text-green-800', icon: CheckCircleIcon, labelKey: 'plugin.status.enabled' },
      disabled: { bg: 'bg-gray-100', text: 'text-gray-800', icon: StopIcon, labelKey: 'plugin.status.disabled' },
      failed: { bg: 'bg-red-100', text: 'text-red-800', icon: XCircleIcon, labelKey: 'plugin.status.failed' },
      // Import history statuses (failed is shared with plugin status above)
      success: { bg: 'bg-green-100', text: 'text-green-800', icon: CheckCircleIcon, labelKey: 'plugin.import.status.success' },
      rolled_back: { bg: 'bg-yellow-100', text: 'text-yellow-800', icon: ArrowPathIcon, labelKey: 'plugin.import.status.rolledBack' },
    };
    const c = config[status] || config.INSTALLED;
    const Icon = c.icon;

    return (
      <span className={`inline-flex items-center gap-1 px-2 py-1 rounded-full text-xs font-medium ${c.bg} ${c.text}`}>
        <Icon className="w-3 h-3" />
        {t(c.labelKey)}
      </span>
    );
  };

  return (
    <div className="p-6 max-w-7xl mx-auto">
      {/* Header */}
      <div className="flex justify-between items-center mb-6">
        <div className="flex items-center gap-3">
          <PuzzlePieceSolidIcon className="w-8 h-8 text-indigo-600" />
          <div>
            <h1 className="text-2xl font-bold text-gray-900">{t('plugin.title')}</h1>
            <p className="text-sm text-gray-500">{t('plugin.description')}</p>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => { fetchPlugins(); fetchHistory(); }}
            className="px-4 py-2 border border-gray-300 text-gray-700 rounded-md hover:bg-gray-50 flex items-center gap-2"
          >
            <ArrowPathIcon className="w-4 h-4" />
            {t('plugin.action.refresh')}
          </button>
          <button
            onClick={() => setShowUploadModal(true)}
            className="px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700 flex items-center gap-2"
          >
            <ArrowUpTrayIcon className="w-4 h-4" />
            {t('plugin.action.upload')}
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="border-b border-gray-200 mb-6">
        <nav className="flex -mb-px">
          <button
            onClick={() => setActiveTab('installed')}
            className={`px-6 py-3 text-sm font-medium border-b-2 ${
              activeTab === 'installed'
                ? 'border-indigo-500 text-indigo-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            <PuzzlePieceIcon className="w-4 h-4 inline mr-2" />
            {t('plugin.tab.installed')} ({plugins.length})
          </button>
          <button
            onClick={() => setActiveTab('history')}
            className={`px-6 py-3 text-sm font-medium border-b-2 ${
              activeTab === 'history'
                ? 'border-indigo-500 text-indigo-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            <DocumentTextIcon className="w-4 h-4 inline mr-2" />
            {t('plugin.tab.history')} ({importHistory.length})
          </button>
        </nav>
      </div>

      {/* Content */}
      {loading ? (
        <div className="text-center py-12">
          <ArrowPathIcon className="w-8 h-8 animate-spin text-gray-400 mx-auto" />
          <p className="mt-2 text-gray-500">{t('plugin.loading')}</p>
        </div>
      ) : activeTab === 'installed' ? (
        /* Installed Plugins */
        <div className="bg-white rounded-lg shadow overflow-hidden">
          {plugins.length === 0 ? (
            <div className="text-center py-12">
              <FolderIcon className="w-12 h-12 text-gray-300 mx-auto" />
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
              <tbody className="bg-white divide-y divide-gray-200">
                {plugins.map((plugin) => (
                  <tr key={plugin.pid} className="hover:bg-gray-50">
                    <td className="px-6 py-4">
                      <div className="flex items-center">
                        <PuzzlePieceIcon className="w-5 h-5 text-indigo-500 mr-3" />
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
                    <td className="px-6 py-4 text-sm text-gray-500 font-mono">{plugin.namespace}</td>
                    <td className="px-6 py-4">
                      <StatusBadge status={plugin.status} />
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500">
                      {new Date(plugin.installedAt).toLocaleString()}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex justify-end gap-2">
                        <button
                          onClick={() => { setSelectedPlugin(plugin); setShowDetailModal(true); }}
                          className="p-1 text-gray-400 hover:text-gray-600"
                          title={t('plugin.action.viewDetail')}
                        >
                          <EyeIcon className="w-5 h-5" />
                        </button>
                        {(plugin.status === 'disabled' || plugin.status === 'installed') ? (
                          <button
                            onClick={() => startPlugin(plugin.pid)}
                            className="p-1 text-green-400 hover:text-green-600"
                            title={t('plugin.action.enable')}
                          >
                            <PlayIcon className="w-5 h-5" />
                          </button>
                        ) : plugin.status === 'enabled' ? (
                          <button
                            onClick={() => stopPlugin(plugin.pid)}
                            className="p-1 text-yellow-400 hover:text-yellow-600"
                            title={t('plugin.action.disable')}
                          >
                            <StopIcon className="w-5 h-5" />
                          </button>
                        ) : null}
                        {plugin.status !== 'enabled' && (
                          <button
                            onClick={() => uninstallPlugin(plugin.pid)}
                            className="p-1 text-red-400 hover:text-red-600"
                            title={t('plugin.action.uninstall')}
                          >
                            <TrashIcon className="w-5 h-5" />
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
      ) : (
        /* Import History */
        <div className="bg-white rounded-lg shadow overflow-hidden">
          {importHistory.length === 0 ? (
            <div className="text-center py-12">
              <DocumentTextIcon className="w-12 h-12 text-gray-300 mx-auto" />
              <p className="mt-2 text-gray-500">{t('plugin.empty.history')}</p>
            </div>
          ) : (
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.plugin')}</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.version')}</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.import.column.type')}</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.status')}</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.import.column.source')}</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.import.column.time')}</th>
                </tr>
              </thead>
              <tbody className="bg-white divide-y divide-gray-200">
                {importHistory.map((record) => (
                  <tr key={record.importId} className="hover:bg-gray-50">
                    <td className="px-6 py-4">
                      <div className="text-sm font-medium text-gray-900">{record.pluginId}</div>
                      <div className="text-xs text-gray-500">{record.namespace}</div>
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500">{record.version}</td>
                    <td className="px-6 py-4 text-sm text-gray-500">
                      {record.importType === 'install' ? t('plugin.import.type.install') : t('plugin.import.type.upgrade')}
                    </td>
                    <td className="px-6 py-4">
                      <StatusBadge status={record.status} />
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500">
                      {record.sourceType} - {record.sourceName}
                    </td>
                    <td className="px-6 py-4 text-sm text-gray-500">
                      {new Date(record.startedAt).toLocaleString()}
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
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-xl max-w-md w-full mx-4">
            <div className="px-6 py-4 border-b border-gray-200">
              <h3 className="text-lg font-medium text-gray-900">{t('plugin.upload.title')}</h3>
            </div>
            <div className="p-6">
              <div className="border-2 border-dashed border-gray-300 rounded-lg p-8 text-center">
                <ArrowUpTrayIcon className="w-12 h-12 text-gray-400 mx-auto" />
                <p className="mt-2 text-sm text-gray-600">
                  {t('plugin.upload.dragHint')}
                </p>
                <p className="mt-1 text-xs text-gray-500">
                  {t('plugin.upload.formatHint')}
                </p>
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
                  className="mt-4 inline-block px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700 cursor-pointer"
                >
                  {uploading ? t('plugin.upload.uploading') : t('plugin.upload.selectFile')}
                </label>
              </div>
            </div>
            <div className="px-6 py-4 border-t border-gray-200 flex justify-end">
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
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 max-h-[80vh] overflow-hidden flex flex-col">
            <div className="px-6 py-4 border-b border-gray-200">
              <h3 className="text-lg font-medium text-gray-900">{t('plugin.preview.title')}</h3>
            </div>
            <div className="p-6 overflow-y-auto flex-1">
              {/* Plugin Info */}
              <div className="mb-6">
                <h4 className="text-sm font-medium text-gray-700 mb-2">{t('plugin.preview.info')}</h4>
                <div className="bg-gray-50 rounded-lg p-4 space-y-2">
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

              {/* Validation */}
              {!previewResult.valid && previewResult.errors.length > 0 && (
                <div className="mb-6">
                  <h4 className="text-sm font-medium text-red-700 mb-2 flex items-center gap-1">
                    <XCircleIcon className="w-4 h-4" />
                    {t('plugin.preview.validationErrors')}
                  </h4>
                  <ul className="bg-red-50 rounded-lg p-4 space-y-1 text-sm text-red-700">
                    {previewResult.errors.map((error, i) => (
                      <li key={i}>• {error}</li>
                    ))}
                  </ul>
                </div>
              )}

              {previewResult.warnings.length > 0 && (
                <div className="mb-6">
                  <h4 className="text-sm font-medium text-yellow-700 mb-2 flex items-center gap-1">
                    <ExclamationTriangleIcon className="w-4 h-4" />
                    {t('plugin.preview.warnings')}
                  </h4>
                  <ul className="bg-yellow-50 rounded-lg p-4 space-y-1 text-sm text-yellow-700">
                    {previewResult.warnings.map((warning, i) => (
                      <li key={i}>• {warning}</li>
                    ))}
                  </ul>
                </div>
              )}

              {/* Action Counts */}
              {Object.keys(previewResult.actionCounts).length > 0 && (
                <div>
                  <h4 className="text-sm font-medium text-gray-700 mb-2">{t('plugin.preview.actionsToPlan')}</h4>
                  <div className="bg-gray-50 rounded-lg p-4 grid grid-cols-2 gap-2 text-sm">
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
            <div className="px-6 py-4 border-t border-gray-200 flex justify-end gap-2">
              <button
                onClick={() => { setShowPreviewModal(false); setPreviewResult(null); }}
                className="px-4 py-2 text-gray-700 hover:text-gray-900"
              >
                {t('plugin.action.cancel')}
              </button>
              <button
                onClick={executeImport}
                disabled={!previewResult.valid || uploading}
                className="px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700 disabled:bg-gray-300 disabled:cursor-not-allowed"
              >
                {uploading ? t('plugin.import.importing') : previewResult.isUpgrade ? t('plugin.import.confirmUpgrade') : t('plugin.import.confirmInstall')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Detail Modal */}
      {showDetailModal && selectedPlugin && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-xl max-w-lg w-full mx-4">
            <div className="px-6 py-4 border-b border-gray-200 flex justify-between items-center">
              <h3 className="text-lg font-medium text-gray-900">{t('plugin.detail.title')}</h3>
              <button
                onClick={() => { setShowDetailModal(false); setSelectedPlugin(null); }}
                className="text-gray-400 hover:text-gray-600"
              >
                <XCircleIcon className="w-5 h-5" />
              </button>
            </div>
            <div className="p-6 space-y-4">
              <div>
                <label className="text-sm font-medium text-gray-500">{t('plugin.field.name')}</label>
                <p className="text-gray-900">{selectedPlugin.displayName}</p>
              </div>
              <div>
                <label className="text-sm font-medium text-gray-500">Plugin ID</label>
                <p className="text-gray-900 font-mono text-sm">{selectedPlugin.pluginId}</p>
              </div>
              <div>
                <label className="text-sm font-medium text-gray-500">{t('plugin.column.namespace')}</label>
                <p className="text-gray-900 font-mono text-sm">{selectedPlugin.namespace}</p>
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
                  <p className="text-xs text-gray-400 mt-0.5">
                    Requires platform ≥ {selectedPlugin.minPlatformVersion}
                    {selectedPlugin.maxPlatformVersion && ` (tested up to ${selectedPlugin.maxPlatformVersion})`}
                  </p>
                )}
              </div>
              <div>
                <label className="text-sm font-medium text-gray-500">{t('plugin.column.status')}</label>
                <p><StatusBadge status={selectedPlugin.status} /></p>
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
