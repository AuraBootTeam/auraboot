import { useState } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import {
  XMarkIcon,
  ArrowUpCircleIcon,
  ExclamationTriangleIcon,
  CheckCircleIcon,
} from '@heroicons/react/24/outline';

interface UpgradeDialogProps {
  plugin: {
    pid: string;
    pluginId: string;
    displayName: string;
    installedVersion: string;
    latestVersion: string;
    changelog?: string;
    changelogZh?: string;
  };
  locale: string;
  onClose: () => void;
  onSuccess: () => void;
}

export default function UpgradeDialog({ plugin, locale, onClose, onSuccess }: UpgradeDialogProps) {
  const { showErrorToast } = useToastContext();
  const [upgrading, setUpgrading] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [preview, setPreview] = useState<any>(null);
  const [previewing, setPreviewing] = useState(false);

  const handlePreview = async () => {
    setPreviewing(true);
    try {
      const res = await fetch(
        `/api/marketplace/plugins/${encodeURIComponent(plugin.pluginId)}/upgrade/preview`,
        {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
        },
      );
      const json = await res.json();
      const data = json.data ?? json;
      setPreview(data);
    } catch (e) {
      showErrorToast(locale === 'zh-CN' ? '预览失败' : 'Preview failed');
    } finally {
      setPreviewing(false);
    }
  };

  const handleUpgrade = async () => {
    setUpgrading(true);
    try {
      const res = await fetch(
        `/api/marketplace/plugins/${encodeURIComponent(plugin.pluginId)}/upgrade`,
        {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
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
        showErrorToast(data.errorMessage || (locale === 'zh-CN' ? '升级失败' : 'Upgrade failed'));
        setUpgrading(false);
      }
    } catch (e) {
      showErrorToast(locale === 'zh-CN' ? '升级失败' : 'Upgrade failed');
      setUpgrading(false);
    }
  };

  const changelogText =
    locale === 'zh-CN' ? plugin.changelogZh || plugin.changelog : plugin.changelog;

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
                ? '升级完成'
                : 'Upgrade Complete'
              : locale === 'zh-CN'
                ? '升级插件'
                : 'Upgrade Plugin'}
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
                {locale === 'zh-CN' ? '升级成功!' : 'Successfully upgraded!'}
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
              {/* Version info */}
              <div className="mb-4 flex items-center gap-3">
                <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-orange-100">
                  <span className="text-lg font-bold text-orange-600">
                    {plugin.displayName.charAt(0).toUpperCase()}
                  </span>
                </div>
                <div>
                  <p className="font-medium text-gray-900">{plugin.displayName}</p>
                  <div className="mt-0.5 flex items-center gap-2 text-sm text-gray-500">
                    <span className="font-mono text-red-500 line-through">
                      v{plugin.installedVersion}
                    </span>
                    <span>→</span>
                    <span className="font-mono text-green-600">v{plugin.latestVersion}</span>
                  </div>
                </div>
              </div>

              {/* Changelog */}
              {changelogText && (
                <div className="mb-4 rounded-lg bg-gray-50 p-3">
                  <p className="mb-1 text-xs font-medium tracking-wide text-gray-500 uppercase">
                    {locale === 'zh-CN' ? '更新日志' : 'Changelog'}
                  </p>
                  <p className="text-sm whitespace-pre-wrap text-gray-700">{changelogText}</p>
                </div>
              )}

              {/* Warning */}
              <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-3">
                <div className="flex items-start gap-2">
                  <ExclamationTriangleIcon className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-500" />
                  <p className="text-sm text-amber-700">
                    {locale === 'zh-CN'
                      ? '升级将更新模型、字段、命令等资源。请确认升级。'
                      : 'Upgrade will update models, fields, commands, and other resources. Please confirm.'}
                  </p>
                </div>
              </div>

              {/* Preview result */}
              {preview && (
                <div className="mb-4 rounded-lg border border-blue-200 bg-blue-50 p-3">
                  <p className="mb-1 text-xs font-medium text-blue-600">
                    {locale === 'zh-CN' ? '预览结果' : 'Preview'}
                  </p>
                  <div className="space-y-0.5 text-xs text-blue-700">
                    {Object.entries(preview)
                      .filter(([k]) => k !== 'success')
                      .map(([key, val]) => (
                        <div key={key} className="flex justify-between">
                          <span>{key}</span>
                          <span className="font-mono">{String(val)}</span>
                        </div>
                      ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
          {result ? (
            <button
              onClick={onSuccess}
              className="rounded-lg bg-orange-600 px-4 py-2 text-sm font-medium text-white hover:bg-orange-700"
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
                onClick={handlePreview}
                disabled={previewing || upgrading}
                className="rounded-lg border border-blue-300 px-4 py-2 text-sm font-medium text-blue-700 hover:bg-blue-50 disabled:opacity-50"
              >
                {previewing
                  ? locale === 'zh-CN'
                    ? '预览中...'
                    : 'Previewing...'
                  : locale === 'zh-CN'
                    ? '预览变更'
                    : 'Preview Changes'}
              </button>
              <button
                onClick={handleUpgrade}
                disabled={upgrading}
                className="flex items-center gap-2 rounded-lg bg-orange-600 px-4 py-2 text-sm font-medium text-white hover:bg-orange-700 disabled:opacity-50"
              >
                {upgrading ? (
                  <>
                    <div className="h-4 w-4 animate-spin rounded-full border-b-2 border-white" />
                    {locale === 'zh-CN' ? '升级中...' : 'Upgrading...'}
                  </>
                ) : (
                  <>
                    <ArrowUpCircleIcon className="h-4 w-4" />
                    {locale === 'zh-CN' ? '确认升级' : 'Confirm Upgrade'}
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
