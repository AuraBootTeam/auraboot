import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { useEntitlement } from '~/contexts/EntitlementContext';
import {
  ShieldCheckIcon,
  ClockIcon,
  ExclamationTriangleIcon,
  CheckCircleIcon,
  XCircleIcon,
  KeyIcon,
} from '@heroicons/react/24/outline';

interface EntitlementRecord {
  pluginId: string;
  status: string;
  planCode: string;
  planDisplayName?: string;
  features: string[];
  expiresAt?: string;
  graceUntil?: string;
  source?: string;
  warning?: { code: string; message?: string; severity: string };
}

const STATUS_CONFIG: Record<
  string,
  { label: string; labelZh: string; color: string; icon: typeof CheckCircleIcon }
> = {
  active: {
    label: 'Active',
    labelZh: '已激活',
    color: 'bg-green-100 text-green-700',
    icon: CheckCircleIcon,
  },
  trial: { label: 'Trial', labelZh: '试用中', color: 'bg-blue-100 text-blue-700', icon: ClockIcon },
  grace: {
    label: 'Grace Period',
    labelZh: '宽限期',
    color: 'bg-amber-100 text-amber-700',
    icon: ExclamationTriangleIcon,
  },
  expired: {
    label: 'Expired',
    labelZh: '已过期',
    color: 'bg-red-100 text-red-700',
    icon: XCircleIcon,
  },
  disabled: {
    label: 'Disabled',
    labelZh: '已禁用',
    color: 'bg-gray-100 text-gray-600',
    icon: XCircleIcon,
  },
};

export default function LicensesPage() {
  const { locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { enabled, entitlements, refresh } = useEntitlement();
  const isZh = locale === 'zh-CN';

  const [tokenInput, setTokenInput] = useState('');
  const [importing, setImporting] = useState(false);
  const [showImport, setShowImport] = useState(false);

  const handleImportToken = async () => {
    if (!tokenInput.trim()) return;
    setImporting(true);
    try {
      const resp = await fetch('/api/entitlements/import-token', {
        method: 'post',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: tokenInput.trim() }),
      });
      const data = await resp.json();
      if (resp.ok && data.code === '0') {
        showSuccessToast(isZh ? '授权令牌导入成功' : 'License token imported successfully');
        setTokenInput('');
        setShowImport(false);
        refresh();
      } else {
        showErrorToast(data.message || (isZh ? '导入失败' : 'Import failed'));
      }
    } catch {
      showErrorToast(isZh ? '导入失败' : 'Import failed');
    } finally {
      setImporting(false);
    }
  };

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return '—';
    return new Date(dateStr).toLocaleDateString(locale, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  };

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">
            {isZh ? '授权管理' : 'License Management'}
          </h1>
          <p className="mt-1 text-sm text-gray-500">
            {isZh ? '查看和管理插件授权状态' : 'View and manage plugin license status'}
            {' · '}
            <Link to="/settings/billing" className="text-indigo-600 hover:text-indigo-700">
              {isZh ? '查看账单历史' : 'View billing history'} &rarr;
            </Link>
          </p>
        </div>
        <button
          onClick={() => setShowImport(!showImport)}
          className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-700"
        >
          <KeyIcon className="h-4 w-4" />
          {isZh ? '导入令牌' : 'Import Token'}
        </button>
      </div>

      {/* System Status */}
      <div
        className={`mb-6 rounded-lg border p-4 ${enabled ? 'border-green-200 bg-green-50' : 'border-gray-200 bg-gray-50'}`}
      >
        <div className="flex items-center gap-2">
          <ShieldCheckIcon className={`h-5 w-5 ${enabled ? 'text-green-600' : 'text-gray-400'}`} />
          <span className="text-sm font-medium">
            {enabled
              ? isZh
                ? '授权系统已启用'
                : 'Entitlement system enabled'
              : isZh
                ? '授权系统未启用 — 所有插件无限制访问'
                : 'Entitlement system disabled — all plugins have unrestricted access'}
          </span>
        </div>
      </div>

      {/* Token Import Panel */}
      {showImport && (
        <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
          <h3 className="mb-3 text-sm font-semibold text-gray-900">
            {isZh ? '导入离线授权令牌' : 'Import Offline License Token'}
          </h3>
          <p className="mb-3 text-xs text-gray-500">
            {isZh
              ? '粘贴由平台管理员签发的 JWT 授权令牌。令牌包含插件 ID、计划、功能列表和有效期信息。'
              : 'Paste the JWT license token issued by the platform administrator. The token contains plugin ID, plan, feature list, and expiration info.'}
          </p>
          <textarea
            value={tokenInput}
            onChange={(e) => setTokenInput(e.target.value)}
            placeholder={isZh ? '粘贴授权令牌 (JWT)...' : 'Paste license token (JWT)...'}
            className="w-full resize-none rounded-lg border border-gray-300 px-3 py-2 font-mono text-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500"
            rows={4}
          />
          <div className="mt-3 flex justify-end gap-2">
            <button
              onClick={() => {
                setShowImport(false);
                setTokenInput('');
              }}
              className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800"
            >
              {isZh ? '取消' : 'Cancel'}
            </button>
            <button
              onClick={handleImportToken}
              disabled={importing || !tokenInput.trim()}
              className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {importing ? (isZh ? '导入中...' : 'Importing...') : isZh ? '导入' : 'Import'}
            </button>
          </div>
        </div>
      )}

      {/* Entitlements List */}
      {!enabled ? (
        <div className="rounded-lg border border-gray-200 bg-white p-12 text-center">
          <ShieldCheckIcon className="mx-auto mb-4 h-12 w-12 text-gray-300" />
          <p className="text-gray-500">
            {isZh
              ? '授权系统当前未启用。启用后将在此显示各插件的授权状态。'
              : 'Entitlement system is currently disabled. Plugin license status will appear here when enabled.'}
          </p>
        </div>
      ) : entitlements.length === 0 ? (
        <div className="rounded-lg border border-gray-200 bg-white p-12 text-center">
          <CheckCircleIcon className="mx-auto mb-4 h-12 w-12 text-green-300" />
          <p className="text-gray-500">{isZh ? '暂无授权记录' : 'No entitlement records'}</p>
        </div>
      ) : (
        <div className="space-y-4">
          {entitlements.map((ent: EntitlementRecord) => {
            const cfg = STATUS_CONFIG[ent.status] || STATUS_CONFIG.DISABLED;
            const StatusIcon = cfg.icon;
            return (
              <div key={ent.pluginId} className="rounded-lg border border-gray-200 bg-white p-5">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3">
                      <h3 className="text-base font-semibold text-gray-900">{ent.pluginId}</h3>
                      <span
                        className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ${cfg.color}`}
                      >
                        <StatusIcon className="h-3.5 w-3.5" />
                        {isZh ? cfg.labelZh : cfg.label}
                      </span>
                    </div>
                    <div className="mt-2 flex items-center gap-4 text-sm text-gray-500">
                      {ent.planCode && (
                        <span>
                          {isZh ? '计划:' : 'Plan:'} {ent.planDisplayName || ent.planCode}
                        </span>
                      )}
                      {ent.source && (
                        <span>
                          {isZh ? '来源:' : 'Source:'} {ent.source}
                        </span>
                      )}
                      {ent.expiresAt && (
                        <span className="flex items-center gap-1">
                          <ClockIcon className="h-3.5 w-3.5" />
                          {isZh ? '到期:' : 'Expires:'} {formatDate(ent.expiresAt)}
                        </span>
                      )}
                      {ent.graceUntil && ent.status === 'grace' && (
                        <span className="text-amber-600">
                          {isZh ? '宽限至:' : 'Grace until:'} {formatDate(ent.graceUntil)}
                        </span>
                      )}
                    </div>
                    {ent.features && ent.features.length > 0 && (
                      <div className="mt-3 flex flex-wrap gap-1.5">
                        {ent.features.map((f) => (
                          <span
                            key={f}
                            className="rounded bg-indigo-50 px-2 py-0.5 text-xs text-indigo-600"
                          >
                            {f}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
                {ent.warning && (
                  <div
                    className={`mt-3 rounded px-3 py-2 text-sm ${
                      ent.warning.severity === 'error'
                        ? 'bg-red-50 text-red-700'
                        : 'bg-amber-50 text-amber-700'
                    }`}
                  >
                    <ExclamationTriangleIcon className="mr-1 inline h-4 w-4" />
                    {ent.warning.message}
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
