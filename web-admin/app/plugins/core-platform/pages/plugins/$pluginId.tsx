import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  ArrowLeftIcon,
  ArrowDownTrayIcon,
  ArrowUpCircleIcon,
  CheckCircleIcon,
  ClockIcon,
  TagIcon,
  GlobeAltIcon,
} from '@heroicons/react/24/outline';
import InstallDialog from './components/InstallDialog';
import UpgradeDialog from './components/UpgradeDialog';
import ReviewSection from '~/ui/ReviewSection';

interface PluginDetail {
  pid: string;
  pluginId: string;
  namespace: string;
  displayName: string;
  displayNameZh?: string;
  displayNameEn?: string;
  summary: string;
  description?: string;
  author: string;
  homepage?: string;
  iconUrl?: string;
  pluginType: string;
  categoryCode: string;
  categoryName: string;
  tags: string[];
  status: string;
  featured: boolean;
  installCount: number;
  latestVersion: string;
  totalVersions: number;
  minPlatformVersion?: string;
  licenseMode: string;
  createdAt: string;
  publishedAt: string;
  installed: boolean;
  installedVersion?: string;
  versions: VersionInfo[];
  readmeMarkdown?: string;
  screenshots?: string[];
}

interface PlanInfo {
  pid: string;
  planCode: string;
  displayNameZh?: string;
  displayNameEn?: string;
  isDefault?: boolean;
  trialDays?: number;
  billingType?: string;
}

interface FeatureInfo {
  pid: string;
  featureKey: string;
  displayNameZh?: string;
  displayNameEn?: string;
}

interface VersionInfo {
  pid: string;
  version: string;
  changelog?: string;
  changelogZh?: string;
  dependencies: string[];
  minPlatformVersion?: string;
  dslVersion: number;
  status: string;
  installCount: number;
  createdAt: string;
  publishedAt?: string;
}

export default function MarketplaceDetailPage() {
  const { pluginId } = useParams();
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { locale } = useI18n();

  const [detail, setDetail] = useState<PluginDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [showInstall, setShowInstall] = useState(false);
  const [showUpgrade, setShowUpgrade] = useState(false);
  const [plans, setPlans] = useState<PlanInfo[]>([]);
  const [features, setFeatures] = useState<FeatureInfo[]>([]);
  const [activatingTrial, setActivatingTrial] = useState(false);

  useEffect(() => {
    if (!pluginId) return;
    (async () => {
      try {
        const res = await fetch(`/api/marketplace/plugins/${encodeURIComponent(pluginId)}`);
        if (res.ok) {
          const json = await res.json();
          setDetail(json.data ?? json);
        } else {
          showErrorToast('Failed to load plugin detail');
        }
      } catch (e) {
        showErrorToast('Failed to load plugin detail');
      } finally {
        setLoading(false);
      }
    })();
    // Fetch plans and features
    (async () => {
      try {
        const [plansRes, featRes] = await Promise.all([
          fetch(`/api/admin/entitlements/plans?pluginId=${encodeURIComponent(pluginId)}`),
          fetch(`/api/admin/entitlements/features?pluginId=${encodeURIComponent(pluginId)}`),
        ]);
        if (plansRes.ok) {
          const pData = await plansRes.json();
          setPlans(pData.data ?? []);
        }
        if (featRes.ok) {
          const fData = await featRes.json();
          setFeatures(fData.data ?? []);
        }
      } catch {
        /* plans/features are optional */
      }
    })();
  }, [pluginId]);

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-indigo-600" />
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="flex min-h-screen items-center justify-center text-gray-500">
        Plugin not found
      </div>
    );
  }

  const handleActivateTrial = async () => {
    if (!detail) return;
    setActivatingTrial(true);
    try {
      const resp = await fetch(
        `/api/entitlements/${encodeURIComponent(detail.pluginId)}/activate`,
        {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({}),
        },
      );
      const data = await resp.json();
      if (resp.ok && data.code === '0') {
        showSuccessToast(locale === 'zh-CN' ? '试用已激活' : 'Trial activated');
        window.location.reload();
      } else {
        showErrorToast(data.message || (locale === 'zh-CN' ? '激活失败' : 'Activation failed'));
      }
    } catch {
      showErrorToast(locale === 'zh-CN' ? '激活失败' : 'Activation failed');
    } finally {
      setActivatingTrial(false);
    }
  };

  const displayName =
    locale === 'zh-CN'
      ? detail.displayNameZh || detail.displayName
      : detail.displayNameEn || detail.displayName;

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Top bar */}
      <div className="border-b border-gray-200 bg-white px-6 py-4">
        <div className="mx-auto max-w-5xl">
          <button
            onClick={() => navigate('/plugins?tab=discovery')}
            className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700"
          >
            <ArrowLeftIcon className="h-4 w-4" />
            {locale === 'zh-CN' ? '返回市场' : 'Back to Marketplace'}
          </button>
        </div>
      </div>

      <div className="mx-auto max-w-5xl px-6 py-8">
        {/* Plugin Header */}
        <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
          <div className="flex items-start gap-6">
            {/* Icon */}
            <div className="flex h-20 w-20 flex-shrink-0 items-center justify-center rounded-xl bg-indigo-100">
              <span className="text-3xl font-bold text-indigo-600">
                {(displayName || detail.pluginId).charAt(0).toUpperCase()}
              </span>
            </div>

            {/* Info */}
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-3">
                <h1 className="text-2xl font-bold text-gray-900">
                  {displayName || detail.pluginId}
                </h1>
                {detail.featured && (
                  <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700">
                    Featured
                  </span>
                )}
              </div>
              <p className="mt-1 text-sm text-gray-500">{detail.pluginId}</p>
              <p className="mt-2 text-gray-700">{detail.summary}</p>

              <div className="mt-4 flex items-center gap-4 text-sm text-gray-500">
                <span className="flex items-center gap-1">
                  <TagIcon className="h-4 w-4" />v{detail.latestVersion}
                </span>
                <span className="flex items-center gap-1">
                  <ArrowDownTrayIcon className="h-4 w-4" />
                  {detail.installCount} {locale === 'zh-CN' ? '次安装' : 'installs'}
                </span>
                {detail.author && (
                  <span>
                    {locale === 'zh-CN' ? '作者:' : 'By'} {detail.author}
                  </span>
                )}
                {detail.categoryName && (
                  <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
                    {detail.categoryName}
                  </span>
                )}
                {detail.homepage && (
                  <a
                    href={detail.homepage}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="flex items-center gap-1 text-indigo-600 hover:underline"
                  >
                    <GlobeAltIcon className="h-4 w-4" />
                    Homepage
                  </a>
                )}
              </div>
            </div>

            {/* Install / Upgrade Button */}
            <div className="flex flex-shrink-0 flex-col items-end gap-2">
              {detail.installed ? (
                <>
                  <div className="flex items-center gap-2 rounded-lg border border-green-200 bg-green-50 px-4 py-2 text-green-700">
                    <CheckCircleIcon className="h-5 w-5" />
                    <span className="text-sm font-medium">
                      {locale === 'zh-CN' ? '已安装' : 'Installed'} v{detail.installedVersion}
                    </span>
                  </div>
                  {detail.installedVersion && detail.installedVersion !== detail.latestVersion && (
                    <button
                      onClick={() => setShowUpgrade(true)}
                      className="flex items-center gap-2 rounded-lg bg-orange-500 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-orange-600"
                    >
                      <ArrowUpCircleIcon className="h-4 w-4" />
                      {locale === 'zh-CN'
                        ? `升级 v${detail.installedVersion} → v${detail.latestVersion}`
                        : `Upgrade v${detail.installedVersion} → v${detail.latestVersion}`}
                    </button>
                  )}
                </>
              ) : (
                <div className="flex flex-col items-end gap-2">
                  <button
                    onClick={() => setShowInstall(true)}
                    className="flex items-center gap-2 rounded-lg bg-indigo-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-indigo-700"
                  >
                    <ArrowDownTrayIcon className="h-4 w-4" />
                    {locale === 'zh-CN' ? '安装' : 'Install'}
                  </button>
                  {detail.licenseMode === 'platform' && (
                    <button
                      onClick={handleActivateTrial}
                      disabled={activatingTrial}
                      className="flex items-center gap-2 rounded-lg bg-emerald-600 px-6 py-2 text-sm font-medium text-white transition-colors hover:bg-emerald-700 disabled:opacity-50"
                    >
                      <ClockIcon className="h-4 w-4" />
                      {activatingTrial
                        ? locale === 'zh-CN'
                          ? '激活中...'
                          : 'Activating...'
                        : locale === 'zh-CN'
                          ? '开始试用'
                          : 'Start Trial'}
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Screenshots Gallery */}
        {detail.screenshots && detail.screenshots.length > 0 && (
          <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
            <h2 className="mb-3 text-lg font-semibold text-gray-900">
              {locale === 'zh-CN' ? '截图' : 'Screenshots'}
            </h2>
            <div className="flex gap-3 overflow-x-auto pb-3">
              {detail.screenshots.map((url: string, i: number) => (
                <img
                  key={i}
                  src={url}
                  alt={`Screenshot ${i + 1}`}
                  className="h-48 cursor-pointer rounded-lg border border-gray-200 transition-shadow hover:shadow-lg"
                  onClick={() => window.open(url, '_blank')}
                />
              ))}
            </div>
          </div>
        )}

        {/* Description */}
        {(detail.readmeMarkdown || detail.description) && (
          <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
            <h2 className="mb-3 text-lg font-semibold text-gray-900">
              {locale === 'zh-CN' ? '详细描述' : 'Description'}
            </h2>
            {detail.readmeMarkdown ? (
              <div className="prose prose-sm max-w-none">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{detail.readmeMarkdown}</ReactMarkdown>
              </div>
            ) : (
              <p className="text-gray-700">{detail.description || detail.summary}</p>
            )}
          </div>
        )}

        {/* Plans & Pricing */}
        {plans.length > 0 && (
          <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
            <h2 className="mb-4 text-lg font-semibold text-gray-900">
              {locale === 'zh-CN' ? '计划与定价' : 'Plans & Pricing'}
            </h2>
            <div
              className={`grid gap-4 ${plans.length >= 3 ? 'grid-cols-3' : plans.length === 2 ? 'grid-cols-2' : 'max-w-sm grid-cols-1'}`}
            >
              {plans.map((plan) => {
                const planName =
                  locale === 'zh-CN'
                    ? plan.displayNameZh || plan.planCode
                    : plan.displayNameEn || plan.planCode;
                return (
                  <div
                    key={plan.pid}
                    className={`rounded-lg border p-5 ${plan.isDefault ? 'border-indigo-300 bg-indigo-50/30' : 'border-gray-200'}`}
                  >
                    <div className="mb-2 flex items-center gap-2">
                      <h3 className="text-base font-semibold text-gray-900">{planName}</h3>
                      {plan.isDefault && (
                        <span className="rounded bg-indigo-100 px-1.5 py-0.5 text-xs text-indigo-700">
                          {locale === 'zh-CN' ? '默认' : 'Default'}
                        </span>
                      )}
                    </div>
                    <div className="mb-3 text-sm text-gray-500">
                      {plan.billingType === 'free'
                        ? locale === 'zh-CN'
                          ? '免费'
                          : 'Free'
                        : plan.billingType === 'subscription'
                          ? locale === 'zh-CN'
                            ? '订阅'
                            : 'Subscription'
                          : locale === 'zh-CN'
                            ? '一次性'
                            : 'One-time'}
                      {plan.trialDays && plan.trialDays > 0 && (
                        <span className="ml-2 text-indigo-600">
                          · {plan.trialDays} {locale === 'zh-CN' ? '天试用' : 'day trial'}
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
            {features.length > 0 && (
              <div className="mt-4 border-t border-gray-100 pt-4">
                <h3 className="mb-2 text-sm font-medium text-gray-700">
                  {locale === 'zh-CN' ? '功能特性' : 'Features'}
                </h3>
                <div className="flex flex-wrap gap-2">
                  {features.map((feat) => (
                    <span
                      key={feat.pid}
                      className="inline-flex items-center rounded border border-gray-200 bg-gray-50 px-2.5 py-1 text-sm text-gray-700"
                    >
                      {locale === 'zh-CN'
                        ? feat.displayNameZh || feat.featureKey
                        : feat.displayNameEn || feat.featureKey}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Tags */}
        {detail.tags && detail.tags.length > 0 && (
          <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
            <h2 className="mb-3 text-lg font-semibold text-gray-900">Tags</h2>
            <div className="flex flex-wrap gap-2">
              {detail.tags.map((tag) => (
                <span
                  key={tag}
                  className="rounded-full bg-gray-100 px-3 py-1 text-sm text-gray-700"
                >
                  {tag}
                </span>
              ))}
            </div>
          </div>
        )}

        {/* Versions */}
        <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
          <h2 className="mb-4 text-lg font-semibold text-gray-900">
            {locale === 'zh-CN' ? '版本历史' : 'Version History'} ({detail.totalVersions})
          </h2>
          <div className="space-y-4">
            {detail.versions.map((ver, idx) => (
              <div key={ver.pid} className={`${idx > 0 ? 'border-t border-gray-100 pt-4' : ''}`}>
                <div className="flex items-center gap-3">
                  <span className="font-mono text-sm font-medium text-gray-900">
                    v{ver.version}
                  </span>
                  <span
                    className={`rounded px-2 py-0.5 text-xs font-medium ${
                      ver.status === 'published'
                        ? 'bg-green-100 text-green-700'
                        : ver.status === 'deprecated'
                          ? 'bg-red-100 text-red-700'
                          : 'bg-gray-100 text-gray-600'
                    }`}
                  >
                    {ver.status}
                  </span>
                  {ver.publishedAt && (
                    <span className="flex items-center gap-1 text-xs text-gray-400">
                      <ClockIcon className="h-3 w-3" />
                      {new Date(ver.publishedAt).toLocaleDateString()}
                    </span>
                  )}
                </div>
                {(ver.changelog || ver.changelogZh) && (
                  <p className="mt-1 text-sm text-gray-600">
                    {locale === 'zh-CN' ? ver.changelogZh || ver.changelog : ver.changelog}
                  </p>
                )}
                {ver.dependencies && ver.dependencies.length > 0 && (
                  <div className="mt-2 flex items-center gap-2">
                    <span className="text-xs text-gray-400">
                      {locale === 'zh-CN' ? '依赖:' : 'Dependencies:'}
                    </span>
                    {ver.dependencies.map((dep) => (
                      <span
                        key={dep}
                        className="rounded bg-blue-50 px-2 py-0.5 text-xs text-blue-600"
                      >
                        {dep}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Reviews */}
        <ReviewSection targetType="marketplace_plugin" targetId={detail.pid} />
      </div>

      {/* Install Dialog */}
      {showInstall && detail && (
        <InstallDialog
          plugin={
            {
              pid: detail.pid,
              pluginId: detail.pluginId,
              displayName: displayName || detail.pluginId,
              latestVersion: detail.latestVersion,
              installed: detail.installed,
              installedVersion: detail.installedVersion,
            } as any
          }
          locale={locale}
          onClose={() => setShowInstall(false)}
          onSuccess={() => {
            setShowInstall(false);
            window.location.reload();
          }}
        />
      )}

      {/* Upgrade Dialog */}
      {showUpgrade && detail && detail.installedVersion && (
        <UpgradeDialog
          plugin={{
            pid: detail.pid,
            pluginId: detail.pluginId,
            displayName: displayName || detail.pluginId,
            installedVersion: detail.installedVersion,
            latestVersion: detail.latestVersion,
            changelog: detail.versions[0]?.changelog,
            changelogZh: detail.versions[0]?.changelogZh,
          }}
          locale={locale}
          onClose={() => setShowUpgrade(false)}
          onSuccess={() => {
            setShowUpgrade(false);
            window.location.reload();
          }}
        />
      )}
    </div>
  );
}
