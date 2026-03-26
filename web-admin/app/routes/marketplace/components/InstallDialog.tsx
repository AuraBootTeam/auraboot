import { useState } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import {
  XMarkIcon,
  ArrowDownTrayIcon,
  ExclamationTriangleIcon,
  CheckCircleIcon,
} from '@heroicons/react/24/outline';

interface InstallDialogProps {
  plugin: {
    pid: string;
    pluginId: string;
    displayName: string;
    latestVersion: string;
    installed: boolean;
    installedVersion?: string;
  };
  locale: string;
  onClose: () => void;
  onSuccess: () => void;
}

export default function InstallDialog({ plugin, locale, onClose, onSuccess }: InstallDialogProps) {
  const { showErrorToast } = useToastContext();
  const [installing, setInstalling] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [conflictStrategy, setConflictStrategy] = useState('overwrite');

  const handleInstall = async () => {
    setInstalling(true);
    try {
      const res = await fetch(
        `/api/marketplace/plugins/${encodeURIComponent(plugin.pluginId)}/install`,
        {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            conflictStrategy,
            autoPublishModels: true,
            autoPublishFields: true,
            autoPublishCommands: true,
            autoPublishPages: true,
          }),
        },
      );
      const json = await res.json();
      const data = json.data ?? json;
      if (json.code === '0') {
        setResult(data);
      } else {
        showErrorToast(data.errorMessage || 'Installation failed');
        setInstalling(false);
      }
    } catch (e) {
      showErrorToast('Installation failed');
      setInstalling(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={onClose}
    >
      <div
        className="mx-4 w-full max-w-md rounded-xl bg-white shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-900">
            {result
              ? locale === 'zh-CN'
                ? '安装完成'
                : 'Installation Complete'
              : locale === 'zh-CN'
                ? '安装插件'
                : 'Install Plugin'}
          </h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        {/* Body */}
        <div className="px-6 py-4">
          {result ? (
            <div className="py-4 text-center">
              <CheckCircleIcon className="mx-auto mb-3 h-16 w-16 text-green-500" />
              <p className="text-lg font-medium text-gray-900">
                {plugin.displayName} v{plugin.latestVersion}
              </p>
              <p className="mt-1 text-sm text-gray-500">
                {locale === 'zh-CN' ? '安装成功!' : 'Successfully installed!'}
              </p>
              {result.resourceCounts && (
                <div className="mt-4 rounded-lg bg-gray-50 p-3 text-left text-xs text-gray-600">
                  {Object.entries(result.resourceCounts).map(([key, val]) => (
                    <div key={key} className="flex justify-between py-0.5">
                      <span>{key}</span>
                      <span className="font-mono">{val as number}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <>
              <div className="mb-4 flex items-center gap-3">
                <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-indigo-100">
                  <span className="text-lg font-bold text-indigo-600">
                    {plugin.displayName.charAt(0).toUpperCase()}
                  </span>
                </div>
                <div>
                  <p className="font-medium text-gray-900">{plugin.displayName}</p>
                  <p className="text-sm text-gray-500">v{plugin.latestVersion}</p>
                </div>
              </div>

              <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-3">
                <div className="flex items-start gap-2">
                  <ExclamationTriangleIcon className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-500" />
                  <p className="text-sm text-amber-700">
                    {locale === 'zh-CN'
                      ? '安装将创建模型、字段、命令等资源。请确认安装。'
                      : 'Installation will create models, fields, commands, and other resources. Please confirm.'}
                  </p>
                </div>
              </div>

              <div className="mb-4">
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {locale === 'zh-CN' ? '冲突策略' : 'Conflict Strategy'}
                </label>
                <select
                  value={conflictStrategy}
                  onChange={(e) => setConflictStrategy(e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                >
                  <option value="overwrite">
                    {locale === 'zh-CN' ? '覆盖已有资源' : 'Overwrite existing'}
                  </option>
                  <option value="merge">{locale === 'zh-CN' ? '合并' : 'Merge'}</option>
                  <option value="error">
                    {locale === 'zh-CN' ? '冲突时报错' : 'Error on conflict'}
                  </option>
                </select>
              </div>
            </>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
          {result ? (
            <button
              onClick={onSuccess}
              className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
            >
              {locale === 'zh-CN' ? '完成' : 'Done'}
            </button>
          ) : (
            <>
              <button
                onClick={onClose}
                className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
              >
                {locale === 'zh-CN' ? '取消' : 'Cancel'}
              </button>
              <button
                onClick={handleInstall}
                disabled={installing}
                className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
              >
                {installing ? (
                  <>
                    <div className="h-4 w-4 animate-spin rounded-full border-b-2 border-white" />
                    {locale === 'zh-CN' ? '安装中...' : 'Installing...'}
                  </>
                ) : (
                  <>
                    <ArrowDownTrayIcon className="h-4 w-4" />
                    {locale === 'zh-CN' ? '确认安装' : 'Confirm Install'}
                  </>
                )}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
