/**
 * Entitlement Admin Management Page
 *
 * Platform admin page for managing entitlement plans, features,
 * tenant entitlements, audit logs, and license token issuance.
 */

import { useState, useEffect, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import {
  PlusIcon,
  TrashIcon,
  ShieldCheckIcon,
  ClipboardDocumentIcon,
  ArrowPathIcon,
  KeyIcon,
  CubeIcon,
  DocumentTextIcon,
  ClockIcon,
} from '@heroicons/react/24/outline';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface Plan {
  pid?: string;
  pluginId: string;
  planCode: string;
  displayName?: string;
  displayNameZh?: string;
  displayNameEn?: string;
  billingType: string;
  trialDays: number;
  isDefault: boolean;
}

interface Feature {
  pid?: string;
  pluginId: string;
  featureKey: string;
  displayName?: string;
  displayNameZh?: string;
  displayNameEn?: string;
}

interface Entitlement {
  pid?: string;
  pluginId: string;
  status: string;
  planCode?: string;
  source?: string;
  expiresAt?: string;
}

interface AuditEntry {
  timestamp: string;
  pluginId: string;
  action: string;
  oldStatus?: string;
  newStatus?: string;
  performedBy?: string;
}

// ---------------------------------------------------------------------------
// Status badge config
// ---------------------------------------------------------------------------

const STATUS_COLORS: Record<string, string> = {
  active: 'bg-green-100 text-green-700',
  trial: 'bg-blue-100 text-blue-700',
  grace: 'bg-amber-100 text-amber-700',
  expired: 'bg-red-100 text-red-700',
  disabled: 'bg-gray-100 text-gray-600',
  REVOKED: 'bg-red-100 text-red-700',
};

// ---------------------------------------------------------------------------
// Tab definitions
// ---------------------------------------------------------------------------

type TabKey = 'plans' | 'features' | 'entitlements' | 'audit' | 'issue-token';

interface TabDef {
  key: TabKey;
  labelZh: string;
  labelEn: string;
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
}

const TABS: TabDef[] = [
  { key: 'plans', labelZh: '计划', labelEn: 'Plans', icon: DocumentTextIcon },
  { key: 'features', labelZh: '功能', labelEn: 'Features', icon: CubeIcon },
  { key: 'entitlements', labelZh: '授权', labelEn: 'Entitlements', icon: ShieldCheckIcon },
  { key: 'audit', labelZh: '审计日志', labelEn: 'Audit Log', icon: ClockIcon },
  { key: 'issue-token', labelZh: '签发令牌', labelEn: 'Issue Token', icon: KeyIcon },
];

// ---------------------------------------------------------------------------
// Shared styles
// ---------------------------------------------------------------------------

const inputClass =
  'w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700 text-gray-900 dark:text-white text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500';
const btnPrimary =
  'flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-medium hover:bg-indigo-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed';
const btnSecondary =
  'px-4 py-2 text-sm text-gray-600 dark:text-gray-300 bg-gray-100 dark:bg-gray-700 rounded-lg hover:bg-gray-200 dark:hover:bg-gray-600 transition-colors';
const btnDanger =
  'p-1.5 text-gray-400 hover:text-red-600 dark:hover:text-red-400 rounded transition-colors';
const thClass =
  'px-4 py-3 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wider';
const tdClass = 'px-4 py-3 text-sm text-gray-900 dark:text-gray-100 whitespace-nowrap';

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function EntitlementAdminPage() {
  const { locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const isZh = locale === 'zh-CN';

  const [activeTab, setActiveTab] = useState<TabKey>('plans');

  // -- Plans state --
  const [plans, setPlans] = useState<Plan[]>([]);
  const [plansLoading, setPlansLoading] = useState(false);
  const [planFilter, setPlanFilter] = useState('');
  const [showPlanForm, setShowPlanForm] = useState(false);
  const [planForm, setPlanForm] = useState<Plan>({
    pluginId: '',
    planCode: '',
    displayNameZh: '',
    displayNameEn: '',
    billingType: 'free',
    trialDays: 0,
    isDefault: false,
  });

  // -- Features state --
  const [features, setFeatures] = useState<Feature[]>([]);
  const [featuresLoading, setFeaturesLoading] = useState(false);
  const [featureFilter, setFeatureFilter] = useState('');
  const [showFeatureForm, setShowFeatureForm] = useState(false);
  const [featureForm, setFeatureForm] = useState<Feature>({
    pluginId: '',
    featureKey: '',
    displayNameZh: '',
    displayNameEn: '',
  });

  // -- Entitlements state --
  const [entitlements, setEntitlements] = useState<Entitlement[]>([]);
  const [entLoading, setEntLoading] = useState(false);
  const [entTenantId, setEntTenantId] = useState('1');
  const [showGrantForm, setShowGrantForm] = useState(false);
  const [grantForm, setGrantForm] = useState({
    tenantId: '1',
    pluginId: '',
    planCode: '',
    expiresAt: '',
  });

  // -- Audit state --
  const [auditLog, setAuditLog] = useState<AuditEntry[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditTenantId, setAuditTenantId] = useState('1');

  // -- Issue Token state --
  const [tokenForm, setTokenForm] = useState({
    tenantId: '1',
    pluginId: '',
    planCode: '',
    features: '',
    expiresAt: '',
  });
  const [generatedToken, setGeneratedToken] = useState('');
  const [issuing, setIssuing] = useState(false);

  // ---------------------------------------------------------------------------
  // API helpers
  // ---------------------------------------------------------------------------

  const apiFetch = useCallback(
    async (url: string, options?: RequestInit) => {
      try {
        const resp = await fetch(url, options);
        const data = await resp.json();
        return { ok: resp.ok, data };
      } catch (err: any) {
        showErrorToast(err.message || 'Request failed');
        return { ok: false, data: null };
      }
    },
    [showErrorToast],
  );

  // ---------------------------------------------------------------------------
  // Plans
  // ---------------------------------------------------------------------------

  const loadPlans = useCallback(async () => {
    setPlansLoading(true);
    const qs = planFilter ? `?pluginId=${encodeURIComponent(planFilter)}` : '';
    const { ok, data } = await apiFetch(`/api/admin/entitlements/plans${qs}`);
    if (ok && data) {
      setPlans(Array.isArray(data.data) ? data.data : Array.isArray(data) ? data : []);
    }
    setPlansLoading(false);
  }, [apiFetch, planFilter]);

  const createPlan = async () => {
    if (!planForm.pluginId || !planForm.planCode) return;
    const { ok, data } = await apiFetch('/api/admin/entitlements/plans', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(planForm),
    });
    if (ok && data) {
      showSuccessToast(isZh ? '计划已创建' : 'Plan created');
      setShowPlanForm(false);
      setPlanForm({
        pluginId: '',
        planCode: '',
        displayNameZh: '',
        displayNameEn: '',
        billingType: 'free',
        trialDays: 0,
        isDefault: false,
      });
      loadPlans();
    } else {
      showErrorToast(data?.message || data?.desc || (isZh ? '创建失败' : 'Create failed'));
    }
  };

  // ---------------------------------------------------------------------------
  // Features
  // ---------------------------------------------------------------------------

  const loadFeatures = useCallback(async () => {
    setFeaturesLoading(true);
    const qs = featureFilter ? `?pluginId=${encodeURIComponent(featureFilter)}` : '';
    const { ok, data } = await apiFetch(`/api/admin/entitlements/features${qs}`);
    if (ok && data) {
      setFeatures(Array.isArray(data.data) ? data.data : Array.isArray(data) ? data : []);
    }
    setFeaturesLoading(false);
  }, [apiFetch, featureFilter]);

  const createFeature = async () => {
    if (!featureForm.pluginId || !featureForm.featureKey) return;
    const { ok, data } = await apiFetch('/api/admin/entitlements/features', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(featureForm),
    });
    if (ok && data) {
      showSuccessToast(isZh ? '功能已创建' : 'Feature created');
      setShowFeatureForm(false);
      setFeatureForm({ pluginId: '', featureKey: '', displayNameZh: '', displayNameEn: '' });
      loadFeatures();
    } else {
      showErrorToast(data?.message || data?.desc || (isZh ? '创建失败' : 'Create failed'));
    }
  };

  // ---------------------------------------------------------------------------
  // Entitlements
  // ---------------------------------------------------------------------------

  const loadEntitlements = useCallback(async () => {
    setEntLoading(true);
    const { ok, data } = await apiFetch(
      `/api/admin/entitlements?tenantId=${encodeURIComponent(entTenantId)}`,
    );
    if (ok && data) {
      setEntitlements(Array.isArray(data.data) ? data.data : Array.isArray(data) ? data : []);
    }
    setEntLoading(false);
  }, [apiFetch, entTenantId]);

  const grantEntitlement = async () => {
    if (!grantForm.pluginId) return;
    const { ok, data } = await apiFetch('/api/admin/entitlements/grant', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        tenantId: Number(grantForm.tenantId),
        pluginId: grantForm.pluginId,
        planCode: grantForm.planCode || undefined,
        expiresAt: grantForm.expiresAt || undefined,
      }),
    });
    if (ok && data) {
      showSuccessToast(isZh ? '授权已授予' : 'Entitlement granted');
      setShowGrantForm(false);
      setGrantForm({ tenantId: entTenantId, pluginId: '', planCode: '', expiresAt: '' });
      loadEntitlements();
    } else {
      showErrorToast(data?.message || data?.desc || (isZh ? '授予失败' : 'Grant failed'));
    }
  };

  const revokeEntitlement = async (pid: string) => {
    if (
      !window.confirm(
        isZh ? '确定要撤销该授权吗?' : 'Are you sure you want to revoke this entitlement?',
      )
    )
      return;
    const { ok, data } = await apiFetch(`/api/admin/entitlements/${pid}`, {
      method: 'delete',
    });
    if (ok) {
      showSuccessToast(isZh ? '授权已撤销' : 'Entitlement revoked');
      loadEntitlements();
    } else {
      showErrorToast(data?.message || data?.desc || (isZh ? '撤销失败' : 'Revoke failed'));
    }
  };

  // ---------------------------------------------------------------------------
  // Audit Log
  // ---------------------------------------------------------------------------

  const loadAuditLog = useCallback(async () => {
    setAuditLoading(true);
    const { ok, data } = await apiFetch(
      `/api/admin/entitlements/audit-log?tenantId=${encodeURIComponent(auditTenantId)}&limit=50`,
    );
    if (ok && data) {
      setAuditLog(Array.isArray(data.data) ? data.data : Array.isArray(data) ? data : []);
    }
    setAuditLoading(false);
  }, [apiFetch, auditTenantId]);

  // ---------------------------------------------------------------------------
  // Issue Token
  // ---------------------------------------------------------------------------

  const issueToken = async () => {
    if (!tokenForm.pluginId) return;
    setIssuing(true);
    const payload: Record<string, unknown> = {
      tenantId: Number(tokenForm.tenantId),
      pluginId: tokenForm.pluginId,
      planCode: tokenForm.planCode || undefined,
      expiresAt: tokenForm.expiresAt || undefined,
    };
    if (tokenForm.features.trim()) {
      payload.features = tokenForm.features
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
    }
    const { ok, data } = await apiFetch('/api/admin/entitlements/license/issue-token', {
      method: 'post',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (ok && data) {
      const token =
        data.data?.token ||
        data.token ||
        (typeof data.data === 'string' ? data.data : JSON.stringify(data.data));
      setGeneratedToken(token);
      showSuccessToast(isZh ? '令牌已签发' : 'Token issued');
    } else {
      showErrorToast(data?.message || data?.desc || (isZh ? '签发失败' : 'Issue failed'));
    }
    setIssuing(false);
  };

  // ---------------------------------------------------------------------------
  // Auto-load on tab switch
  // ---------------------------------------------------------------------------

  useEffect(() => {
    if (activeTab === 'plans') loadPlans();
    else if (activeTab === 'features') loadFeatures();
    else if (activeTab === 'entitlements') loadEntitlements();
    else if (activeTab === 'audit') loadAuditLog();
  }, [activeTab, loadPlans, loadFeatures, loadEntitlements, loadAuditLog]);

  // ---------------------------------------------------------------------------
  // Format helpers
  // ---------------------------------------------------------------------------

  const formatDate = (dateStr?: string) => {
    if (!dateStr) return '--';
    return new Date(dateStr).toLocaleString(locale, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      {/* Header */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900 dark:text-white">
          {isZh ? '授权管理 (Admin)' : 'Entitlement Admin'}
        </h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {isZh
            ? '管理计划、功能、租户授权和许可证令牌'
            : 'Manage plans, features, tenant entitlements, and license tokens'}
        </p>
      </div>

      {/* Tabs */}
      <div className="mb-6 border-b border-gray-200 dark:border-gray-700">
        <nav className="-mb-px flex gap-0">
          {TABS.map((tab) => {
            const active = activeTab === tab.key;
            const Icon = tab.icon;
            return (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={`flex items-center gap-1.5 border-b-2 px-4 py-2.5 text-sm font-medium transition-colors ${
                  active
                    ? 'border-indigo-600 text-indigo-600 dark:border-indigo-400 dark:text-indigo-400'
                    : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
                }`}
              >
                <Icon className="h-4 w-4" />
                {isZh ? tab.labelZh : tab.labelEn}
              </button>
            );
          })}
        </nav>
      </div>

      {/* ================================================================= */}
      {/* Tab: Plans */}
      {/* ================================================================= */}
      {activeTab === 'plans' && (
        <div>
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <input
                type="text"
                value={planFilter}
                onChange={(e) => setPlanFilter(e.target.value)}
                placeholder={isZh ? '按 Plugin ID 过滤...' : 'Filter by Plugin ID...'}
                className={`${inputClass} w-64`}
              />
              <button onClick={loadPlans} className={btnSecondary}>
                <ArrowPathIcon className="mr-1 inline h-4 w-4" />
                {isZh ? '刷新' : 'Refresh'}
              </button>
            </div>
            <button onClick={() => setShowPlanForm(!showPlanForm)} className={btnPrimary}>
              <PlusIcon className="h-4 w-4" />
              {isZh ? '创建计划' : 'Create Plan'}
            </button>
          </div>

          {/* Inline create form */}
          {showPlanForm && (
            <div className="mb-4 rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
              <h3 className="mb-4 text-sm font-semibold text-gray-900 dark:text-white">
                {isZh ? '新建计划' : 'New Plan'}
              </h3>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    Plugin ID *
                  </label>
                  <input
                    type="text"
                    value={planForm.pluginId}
                    onChange={(e) => setPlanForm({ ...planForm, pluginId: e.target.value })}
                    className={inputClass}
                    placeholder="e.g. crm"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    Plan Code *
                  </label>
                  <input
                    type="text"
                    value={planForm.planCode}
                    onChange={(e) => setPlanForm({ ...planForm, planCode: e.target.value })}
                    className={inputClass}
                    placeholder="e.g. professional"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    {isZh ? '中文名称' : 'Display Name (Zh)'}
                  </label>
                  <input
                    type="text"
                    value={planForm.displayNameZh || ''}
                    onChange={(e) => setPlanForm({ ...planForm, displayNameZh: e.target.value })}
                    className={inputClass}
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    {isZh ? '英文名称' : 'Display Name (En)'}
                  </label>
                  <input
                    type="text"
                    value={planForm.displayNameEn || ''}
                    onChange={(e) => setPlanForm({ ...planForm, displayNameEn: e.target.value })}
                    className={inputClass}
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    {isZh ? '计费类型' : 'Billing Type'}
                  </label>
                  <select
                    value={planForm.billingType}
                    onChange={(e) => setPlanForm({ ...planForm, billingType: e.target.value })}
                    className={inputClass}
                  >
                    <option value="free">FREE</option>
                    <option value="one_time">ONE_TIME</option>
                    <option value="monthly">MONTHLY</option>
                    <option value="yearly">YEARLY</option>
                  </select>
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    {isZh ? '试用天数' : 'Trial Days'}
                  </label>
                  <input
                    type="number"
                    min={0}
                    value={planForm.trialDays}
                    onChange={(e) =>
                      setPlanForm({ ...planForm, trialDays: Number(e.target.value) })
                    }
                    className={inputClass}
                  />
                </div>
                <div className="col-span-2 flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={planForm.isDefault}
                    onChange={(e) => setPlanForm({ ...planForm, isDefault: e.target.checked })}
                    className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
                    id="plan-default"
                  />
                  <label
                    htmlFor="plan-default"
                    className="text-sm text-gray-700 dark:text-gray-300"
                  >
                    {isZh ? '默认计划' : 'Default Plan'}
                  </label>
                </div>
              </div>
              <div className="mt-4 flex justify-end gap-3">
                <button onClick={() => setShowPlanForm(false)} className={btnSecondary}>
                  {isZh ? '取消' : 'Cancel'}
                </button>
                <button
                  onClick={createPlan}
                  disabled={!planForm.pluginId || !planForm.planCode}
                  className={btnPrimary}
                >
                  {isZh ? '创建' : 'Create'}
                </button>
              </div>
            </div>
          )}

          {/* Plans table */}
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
            {plansLoading ? (
              <div className="p-8 text-center text-gray-400">
                {isZh ? '加载中...' : 'Loading...'}
              </div>
            ) : plans.length === 0 ? (
              <div className="p-8 text-center text-gray-400">
                {isZh ? '暂无计划' : 'No plans found'}
              </div>
            ) : (
              <table className="w-full">
                <thead className="bg-gray-50 dark:bg-gray-900/50">
                  <tr>
                    <th className={thClass}>Plugin ID</th>
                    <th className={thClass}>Plan Code</th>
                    <th className={thClass}>{isZh ? '显示名称' : 'Display Name'}</th>
                    <th className={thClass}>{isZh ? '计费类型' : 'Billing Type'}</th>
                    <th className={thClass}>{isZh ? '试用天数' : 'Trial Days'}</th>
                    <th className={thClass}>{isZh ? '默认' : 'Default'}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                  {plans.map((p, i) => (
                    <tr key={p.pid || i} className="hover:bg-gray-50 dark:hover:bg-gray-700/30">
                      <td className={tdClass}>{p.pluginId}</td>
                      <td className={`${tdClass} font-mono`}>{p.planCode}</td>
                      <td className={tdClass}>
                        {p.displayName || p.displayNameZh || p.displayNameEn || '--'}
                      </td>
                      <td className={tdClass}>
                        <span className="rounded bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-700 dark:bg-gray-700 dark:text-gray-300">
                          {p.billingType}
                        </span>
                      </td>
                      <td className={tdClass}>{p.trialDays}</td>
                      <td className={tdClass}>
                        {p.isDefault && (
                          <span className="rounded bg-indigo-100 px-2 py-0.5 text-xs font-medium text-indigo-700">
                            {isZh ? '是' : 'Yes'}
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}

      {/* ================================================================= */}
      {/* Tab: Features */}
      {/* ================================================================= */}
      {activeTab === 'features' && (
        <div>
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <input
                type="text"
                value={featureFilter}
                onChange={(e) => setFeatureFilter(e.target.value)}
                placeholder={isZh ? '按 Plugin ID 过滤...' : 'Filter by Plugin ID...'}
                className={`${inputClass} w-64`}
              />
              <button onClick={loadFeatures} className={btnSecondary}>
                <ArrowPathIcon className="mr-1 inline h-4 w-4" />
                {isZh ? '刷新' : 'Refresh'}
              </button>
            </div>
            <button onClick={() => setShowFeatureForm(!showFeatureForm)} className={btnPrimary}>
              <PlusIcon className="h-4 w-4" />
              {isZh ? '创建功能' : 'Create Feature'}
            </button>
          </div>

          {/* Inline create form */}
          {showFeatureForm && (
            <div className="mb-4 rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
              <h3 className="mb-4 text-sm font-semibold text-gray-900 dark:text-white">
                {isZh ? '新建功能' : 'New Feature'}
              </h3>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    Plugin ID *
                  </label>
                  <input
                    type="text"
                    value={featureForm.pluginId}
                    onChange={(e) => setFeatureForm({ ...featureForm, pluginId: e.target.value })}
                    className={inputClass}
                    placeholder="e.g. crm"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    Feature Key *
                  </label>
                  <input
                    type="text"
                    value={featureForm.featureKey}
                    onChange={(e) => setFeatureForm({ ...featureForm, featureKey: e.target.value })}
                    className={inputClass}
                    placeholder="e.g. ai_scoring"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    {isZh ? '中文名称' : 'Display Name (Zh)'}
                  </label>
                  <input
                    type="text"
                    value={featureForm.displayNameZh || ''}
                    onChange={(e) =>
                      setFeatureForm({ ...featureForm, displayNameZh: e.target.value })
                    }
                    className={inputClass}
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    {isZh ? '英文名称' : 'Display Name (En)'}
                  </label>
                  <input
                    type="text"
                    value={featureForm.displayNameEn || ''}
                    onChange={(e) =>
                      setFeatureForm({ ...featureForm, displayNameEn: e.target.value })
                    }
                    className={inputClass}
                  />
                </div>
              </div>
              <div className="mt-4 flex justify-end gap-3">
                <button onClick={() => setShowFeatureForm(false)} className={btnSecondary}>
                  {isZh ? '取消' : 'Cancel'}
                </button>
                <button
                  onClick={createFeature}
                  disabled={!featureForm.pluginId || !featureForm.featureKey}
                  className={btnPrimary}
                >
                  {isZh ? '创建' : 'Create'}
                </button>
              </div>
            </div>
          )}

          {/* Features table */}
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
            {featuresLoading ? (
              <div className="p-8 text-center text-gray-400">
                {isZh ? '加载中...' : 'Loading...'}
              </div>
            ) : features.length === 0 ? (
              <div className="p-8 text-center text-gray-400">
                {isZh ? '暂无功能' : 'No features found'}
              </div>
            ) : (
              <table className="w-full">
                <thead className="bg-gray-50 dark:bg-gray-900/50">
                  <tr>
                    <th className={thClass}>Plugin ID</th>
                    <th className={thClass}>Feature Key</th>
                    <th className={thClass}>{isZh ? '显示名称' : 'Display Name'}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                  {features.map((f, i) => (
                    <tr key={f.pid || i} className="hover:bg-gray-50 dark:hover:bg-gray-700/30">
                      <td className={tdClass}>{f.pluginId}</td>
                      <td className={`${tdClass} font-mono`}>{f.featureKey}</td>
                      <td className={tdClass}>
                        {f.displayName || f.displayNameZh || f.displayNameEn || '--'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}

      {/* ================================================================= */}
      {/* Tab: Entitlements */}
      {/* ================================================================= */}
      {activeTab === 'entitlements' && (
        <div>
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <label className="text-sm text-gray-600 dark:text-gray-400">Tenant ID:</label>
              <input
                type="text"
                value={entTenantId}
                onChange={(e) => setEntTenantId(e.target.value)}
                className={`${inputClass} w-24`}
              />
              <button onClick={loadEntitlements} className={btnSecondary}>
                <ArrowPathIcon className="mr-1 inline h-4 w-4" />
                {isZh ? '刷新' : 'Refresh'}
              </button>
            </div>
            <button
              onClick={() => {
                setGrantForm({ ...grantForm, tenantId: entTenantId });
                setShowGrantForm(!showGrantForm);
              }}
              className={btnPrimary}
            >
              <PlusIcon className="h-4 w-4" />
              {isZh ? '授予' : 'Grant'}
            </button>
          </div>

          {/* Grant form */}
          {showGrantForm && (
            <div className="mb-4 rounded-lg border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
              <h3 className="mb-4 text-sm font-semibold text-gray-900 dark:text-white">
                {isZh ? '授予授权' : 'Grant Entitlement'}
              </h3>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    Tenant ID
                  </label>
                  <input
                    type="text"
                    value={grantForm.tenantId}
                    onChange={(e) => setGrantForm({ ...grantForm, tenantId: e.target.value })}
                    className={inputClass}
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    Plugin ID *
                  </label>
                  <input
                    type="text"
                    value={grantForm.pluginId}
                    onChange={(e) => setGrantForm({ ...grantForm, pluginId: e.target.value })}
                    className={inputClass}
                    placeholder="e.g. crm"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    Plan Code
                  </label>
                  <input
                    type="text"
                    value={grantForm.planCode}
                    onChange={(e) => setGrantForm({ ...grantForm, planCode: e.target.value })}
                    className={inputClass}
                    placeholder="e.g. professional"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                    {isZh ? '过期时间' : 'Expires At'}
                  </label>
                  <input
                    type="date"
                    value={grantForm.expiresAt}
                    onChange={(e) => setGrantForm({ ...grantForm, expiresAt: e.target.value })}
                    className={inputClass}
                  />
                </div>
              </div>
              <div className="mt-4 flex justify-end gap-3">
                <button onClick={() => setShowGrantForm(false)} className={btnSecondary}>
                  {isZh ? '取消' : 'Cancel'}
                </button>
                <button
                  onClick={grantEntitlement}
                  disabled={!grantForm.pluginId}
                  className={btnPrimary}
                >
                  {isZh ? '授予' : 'Grant'}
                </button>
              </div>
            </div>
          )}

          {/* Entitlements table */}
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
            {entLoading ? (
              <div className="p-8 text-center text-gray-400">
                {isZh ? '加载中...' : 'Loading...'}
              </div>
            ) : entitlements.length === 0 ? (
              <div className="p-8 text-center text-gray-400">
                {isZh ? '暂无授权记录' : 'No entitlements found'}
              </div>
            ) : (
              <table className="w-full">
                <thead className="bg-gray-50 dark:bg-gray-900/50">
                  <tr>
                    <th className={thClass}>Plugin ID</th>
                    <th className={thClass}>{isZh ? '状态' : 'Status'}</th>
                    <th className={thClass}>{isZh ? '计划' : 'Plan'}</th>
                    <th className={thClass}>{isZh ? '来源' : 'Source'}</th>
                    <th className={thClass}>{isZh ? '过期时间' : 'Expires At'}</th>
                    <th className={thClass}>{isZh ? '操作' : 'Actions'}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                  {entitlements.map((ent, i) => {
                    const statusColor = STATUS_COLORS[ent.status] || 'bg-gray-100 text-gray-600';
                    return (
                      <tr key={ent.pid || i} className="hover:bg-gray-50 dark:hover:bg-gray-700/30">
                        <td className={tdClass}>{ent.pluginId}</td>
                        <td className={tdClass}>
                          <span
                            className={`rounded-full px-2 py-0.5 text-xs font-medium ${statusColor}`}
                          >
                            {ent.status}
                          </span>
                        </td>
                        <td className={tdClass}>{ent.planCode || '--'}</td>
                        <td className={tdClass}>{ent.source || '--'}</td>
                        <td className={tdClass}>{formatDate(ent.expiresAt)}</td>
                        <td className={tdClass}>
                          {ent.pid && (
                            <button
                              onClick={() => revokeEntitlement(ent.pid!)}
                              className={btnDanger}
                              title={isZh ? '撤销' : 'Revoke'}
                            >
                              <TrashIcon className="h-4 w-4" />
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}

      {/* ================================================================= */}
      {/* Tab: Audit Log */}
      {/* ================================================================= */}
      {activeTab === 'audit' && (
        <div>
          <div className="mb-4 flex items-center gap-3">
            <label className="text-sm text-gray-600 dark:text-gray-400">Tenant ID:</label>
            <input
              type="text"
              value={auditTenantId}
              onChange={(e) => setAuditTenantId(e.target.value)}
              className={`${inputClass} w-24`}
            />
            <button onClick={loadAuditLog} className={btnSecondary}>
              <ArrowPathIcon className="mr-1 inline h-4 w-4" />
              {isZh ? '刷新' : 'Refresh'}
            </button>
          </div>

          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
            {auditLoading ? (
              <div className="p-8 text-center text-gray-400">
                {isZh ? '加载中...' : 'Loading...'}
              </div>
            ) : auditLog.length === 0 ? (
              <div className="p-8 text-center text-gray-400">
                {isZh ? '暂无审计日志' : 'No audit log entries'}
              </div>
            ) : (
              <table className="w-full">
                <thead className="bg-gray-50 dark:bg-gray-900/50">
                  <tr>
                    <th className={thClass}>{isZh ? '时间' : 'Timestamp'}</th>
                    <th className={thClass}>Plugin ID</th>
                    <th className={thClass}>{isZh ? '操作' : 'Action'}</th>
                    <th className={thClass}>{isZh ? '状态变更' : 'Status Change'}</th>
                    <th className={thClass}>{isZh ? '执行人' : 'Performed By'}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                  {auditLog.map((entry, i) => (
                    <tr key={i} className="hover:bg-gray-50 dark:hover:bg-gray-700/30">
                      <td className={`${tdClass} text-xs text-gray-500`}>
                        {formatDate(entry.timestamp)}
                      </td>
                      <td className={tdClass}>{entry.pluginId}</td>
                      <td className={tdClass}>
                        <span className="rounded bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-700 dark:bg-gray-700 dark:text-gray-300">
                          {entry.action}
                        </span>
                      </td>
                      <td className={tdClass}>
                        {entry.oldStatus || entry.newStatus ? (
                          <span className="text-xs">
                            <span
                              className={`rounded px-1.5 py-0.5 ${STATUS_COLORS[entry.oldStatus || ''] || 'bg-gray-100 text-gray-600'}`}
                            >
                              {entry.oldStatus || '--'}
                            </span>
                            <span className="mx-1.5 text-gray-400">&rarr;</span>
                            <span
                              className={`rounded px-1.5 py-0.5 ${STATUS_COLORS[entry.newStatus || ''] || 'bg-gray-100 text-gray-600'}`}
                            >
                              {entry.newStatus || '--'}
                            </span>
                          </span>
                        ) : (
                          '--'
                        )}
                      </td>
                      <td className={tdClass}>{entry.performedBy || '--'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}

      {/* ================================================================= */}
      {/* Tab: Issue Token */}
      {/* ================================================================= */}
      {activeTab === 'issue-token' && (
        <div>
          <div className="rounded-lg border border-gray-200 bg-white p-6 dark:border-gray-700 dark:bg-gray-800">
            <h3 className="mb-4 text-sm font-semibold text-gray-900 dark:text-white">
              {isZh ? '签发离线授权令牌' : 'Issue Offline License Token'}
            </h3>
            <p className="mb-5 text-xs text-gray-500 dark:text-gray-400">
              {isZh
                ? '生成一个包含授权信息的 JWT 令牌，租户可以使用此令牌离线激活插件。'
                : 'Generate a JWT token containing entitlement information. Tenants can use this token to activate plugins offline.'}
            </p>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  Tenant ID *
                </label>
                <input
                  type="number"
                  min={1}
                  value={tokenForm.tenantId}
                  onChange={(e) => setTokenForm({ ...tokenForm, tenantId: e.target.value })}
                  className={inputClass}
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  Plugin ID *
                </label>
                <input
                  type="text"
                  value={tokenForm.pluginId}
                  onChange={(e) => setTokenForm({ ...tokenForm, pluginId: e.target.value })}
                  className={inputClass}
                  placeholder="e.g. crm"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  Plan Code
                </label>
                <input
                  type="text"
                  value={tokenForm.planCode}
                  onChange={(e) => setTokenForm({ ...tokenForm, planCode: e.target.value })}
                  className={inputClass}
                  placeholder="e.g. professional"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  {isZh ? '功能列表 (逗号分隔)' : 'Features (comma-separated)'}
                </label>
                <input
                  type="text"
                  value={tokenForm.features}
                  onChange={(e) => setTokenForm({ ...tokenForm, features: e.target.value })}
                  className={inputClass}
                  placeholder="e.g. ai_scoring, bulk_import"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                  {isZh ? '过期时间' : 'Expires At'}
                </label>
                <input
                  type="date"
                  value={tokenForm.expiresAt}
                  onChange={(e) => setTokenForm({ ...tokenForm, expiresAt: e.target.value })}
                  className={inputClass}
                />
              </div>
            </div>
            <div className="mt-5 flex justify-end">
              <button
                onClick={issueToken}
                disabled={issuing || !tokenForm.pluginId}
                className={btnPrimary}
              >
                <KeyIcon className="h-4 w-4" />
                {issuing ? (isZh ? '签发中...' : 'Issuing...') : isZh ? '签发令牌' : 'Issue Token'}
              </button>
            </div>
          </div>

          {/* Generated token display */}
          {generatedToken && (
            <div className="mt-4 rounded-lg border border-gray-200 bg-white p-6 dark:border-gray-700 dark:bg-gray-800">
              <div className="mb-3 flex items-center justify-between">
                <h3 className="text-sm font-semibold text-gray-900 dark:text-white">
                  {isZh ? '已签发的令牌' : 'Generated Token'}
                </h3>
                <button
                  onClick={() => {
                    navigator.clipboard.writeText(generatedToken);
                    showSuccessToast(isZh ? '已复制到剪贴板' : 'Copied to clipboard');
                  }}
                  className={btnSecondary}
                >
                  <ClipboardDocumentIcon className="mr-1 inline h-4 w-4" />
                  {isZh ? '复制' : 'Copy'}
                </button>
              </div>
              <textarea
                readOnly
                value={generatedToken}
                className="w-full resize-none rounded-lg border border-gray-300 bg-gray-50 px-3 py-2 font-mono text-xs text-gray-800 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-900 dark:text-gray-200"
                rows={6}
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
