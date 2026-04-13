import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  ArrowLeftIcon,
  ArrowDownTrayIcon,
  CheckCircleIcon,
  XCircleIcon,
  PuzzlePieceIcon,
  CubeTransparentIcon,
  StarIcon,
} from '@heroicons/react/24/outline';
import ReviewSection from '~/ui/ReviewSection';

interface PluginInfo {
  pluginId: string;
  displayName: string;
  summary?: string;
  iconUrl?: string;
  installed: boolean;
  availableInMarketplace: boolean;
}

interface SolutionDetail {
  pid: string;
  code: string;
  name: string;
  nameZh?: string;
  nameEn?: string;
  description: string;
  descriptionZh?: string;
  descriptionEn?: string;
  industry: string;
  pluginCodes: string[];
  plugins: PluginInfo[];
  iconUrl?: string;
  coverImageUrl?: string;
  screenshots: string[];
  readmeMarkdown?: string;
  priceType: string;
  price: number;
  status: string;
  installCount: number;
  averageRating: number;
  reviewCount: number;
  featured: boolean;
  tags: string[];
  createdAt: string;
  publishedAt: string;
  installed: boolean;
}

interface InstallStatus {
  pluginCode: string;
  status: string;
  message: string;
}

interface InstallResult {
  success: boolean;
  solutionCode: string;
  totalPlugins: number;
  installedPlugins: number;
  skippedPlugins: number;
  failedPlugins: number;
  pluginResults: InstallStatus[];
}

export default function SolutionDetailPage() {
  const { code } = useParams();
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { locale } = useI18n();

  const [detail, setDetail] = useState<SolutionDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [installing, setInstalling] = useState(false);
  const [installResult, setInstallResult] = useState<InstallResult | null>(null);
  const [uninstalling, setUninstalling] = useState(false);

  useEffect(() => {
    if (!code) return;
    (async () => {
      try {
        const res = await fetch(`/api/marketplace/solutions/${encodeURIComponent(code)}`);
        if (res.ok) {
          const json = await res.json();
          setDetail(json.data ?? json);
        } else {
          showErrorToast('Failed to load solution detail');
        }
      } catch {
        showErrorToast('Failed to load solution detail');
      } finally {
        setLoading(false);
      }
    })();
  }, [code]);

  const handleInstall = async () => {
    if (!detail) return;
    setInstalling(true);
    setInstallResult(null);
    try {
      const res = await fetch(
        `/api/marketplace/solutions/${encodeURIComponent(detail.code)}/install`,
        {
          method: 'post',
        },
      );
      const json = await res.json();
      const result = json.data ?? json;
      setInstallResult(result);
      if (result.success) {
        showSuccessToast(
          locale === 'zh-CN' ? '解决方案安装成功' : 'Solution installed successfully',
        );
        // Refresh detail
        const detailRes = await fetch(
          `/api/marketplace/solutions/${encodeURIComponent(detail.code)}`,
        );
        if (detailRes.ok) {
          const dJson = await detailRes.json();
          setDetail(dJson.data ?? dJson);
        }
      } else {
        showErrorToast(
          locale === 'zh-CN'
            ? `安装部分失败 (${result.failedPlugins} 个插件失败)`
            : `Partial install failure (${result.failedPlugins} plugin(s) failed)`,
        );
      }
    } catch {
      showErrorToast(locale === 'zh-CN' ? '安装失败' : 'Installation failed');
    } finally {
      setInstalling(false);
    }
  };

  const handleUninstall = async () => {
    if (!detail) return;
    setUninstalling(true);
    try {
      const res = await fetch(
        `/api/marketplace/solutions/${encodeURIComponent(detail.code)}/uninstall`,
        {
          method: 'post',
        },
      );
      if (res.ok) {
        showSuccessToast(locale === 'zh-CN' ? '已卸载' : 'Uninstalled');
        const detailRes = await fetch(
          `/api/marketplace/solutions/${encodeURIComponent(detail.code)}`,
        );
        if (detailRes.ok) {
          const dJson = await detailRes.json();
          setDetail(dJson.data ?? dJson);
        }
      }
    } catch {
      showErrorToast(locale === 'zh-CN' ? '卸载失败' : 'Uninstall failed');
    } finally {
      setUninstalling(false);
    }
  };

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
        Solution not found
      </div>
    );
  }

  const displayName =
    locale === 'zh-CN' ? detail.nameZh || detail.name : detail.nameEn || detail.name;
  const displayDesc =
    locale === 'zh-CN'
      ? detail.descriptionZh || detail.description
      : detail.descriptionEn || detail.description;

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Top bar */}
      <div className="border-b border-gray-200 bg-white px-6 py-4">
        <div className="mx-auto max-w-5xl">
          <button
            onClick={() => navigate('/marketplace/solutions')}
            className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700"
          >
            <ArrowLeftIcon className="h-4 w-4" />
            {locale === 'zh-CN' ? '返回解决方案' : 'Back to Solutions'}
          </button>
        </div>
      </div>

      <div className="mx-auto max-w-5xl px-6 py-8">
        {/* Solution Header */}
        <div className="mb-6 overflow-hidden rounded-lg border border-gray-200 bg-white">
          {/* Cover Banner */}
          <div className="relative h-40 bg-gradient-to-br from-indigo-600 to-purple-700">
            {detail.coverImageUrl && (
              <img src={detail.coverImageUrl} alt="" className="h-full w-full object-cover" />
            )}
            {detail.featured && (
              <div className="absolute top-3 right-3 flex items-center gap-1 rounded-full bg-amber-100 px-2.5 py-1 text-sm font-medium text-amber-700">
                <StarIcon className="h-4 w-4" />
                Featured
              </div>
            )}
          </div>

          <div className="p-6">
            <div className="flex items-start justify-between">
              <div className="flex-1">
                <div className="flex items-center gap-3">
                  <CubeTransparentIcon className="h-8 w-8 text-indigo-600" />
                  <h1 className="text-2xl font-bold text-gray-900">{displayName}</h1>
                </div>
                <p className="mt-2 text-gray-600">{displayDesc}</p>
                <div className="mt-4 flex items-center gap-4 text-sm text-gray-500">
                  <span className="inline-flex items-center gap-1 rounded bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700">
                    {detail.industry}
                  </span>
                  <span className="flex items-center gap-1">
                    <PuzzlePieceIcon className="h-4 w-4" />
                    {detail.pluginCodes.length} {locale === 'zh-CN' ? '个插件' : 'plugins'}
                  </span>
                  <span className="flex items-center gap-1">
                    <ArrowDownTrayIcon className="h-4 w-4" />
                    {detail.installCount} {locale === 'zh-CN' ? '次安装' : 'installs'}
                  </span>
                  <span
                    className={`font-medium ${detail.priceType === 'free' ? 'text-green-600' : 'text-orange-600'}`}
                  >
                    {detail.priceType === 'free'
                      ? locale === 'zh-CN'
                        ? '免费'
                        : 'Free'
                      : `$${detail.price}`}
                  </span>
                </div>
              </div>

              {/* Install / Uninstall */}
              <div className="ml-6 flex flex-shrink-0 flex-col items-end gap-2">
                {detail.installed ? (
                  <>
                    <div className="flex items-center gap-2 rounded-lg border border-green-200 bg-green-50 px-4 py-2 text-green-700">
                      <CheckCircleIcon className="h-5 w-5" />
                      <span className="text-sm font-medium">
                        {locale === 'zh-CN' ? '已安装' : 'Installed'}
                      </span>
                    </div>
                    <button
                      onClick={handleUninstall}
                      disabled={uninstalling}
                      className="rounded-lg border border-red-200 px-4 py-2 text-sm text-red-600 hover:bg-red-50 disabled:opacity-50"
                    >
                      {uninstalling
                        ? locale === 'zh-CN'
                          ? '卸载中...'
                          : 'Uninstalling...'
                        : locale === 'zh-CN'
                          ? '卸载'
                          : 'Uninstall'}
                    </button>
                  </>
                ) : (
                  <button
                    onClick={handleInstall}
                    disabled={installing}
                    className="flex items-center gap-2 rounded-lg bg-indigo-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-indigo-700 disabled:opacity-50"
                  >
                    <ArrowDownTrayIcon className="h-4 w-4" />
                    {installing
                      ? locale === 'zh-CN'
                        ? '安装中...'
                        : 'Installing...'
                      : locale === 'zh-CN'
                        ? '一键安装'
                        : 'Install All'}
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* Install Result */}
        {installResult && (
          <div
            className={`mb-6 rounded-lg border p-4 ${installResult.success ? 'border-green-200 bg-green-50' : 'border-orange-200 bg-orange-50'}`}
          >
            <h3 className="mb-2 font-medium">
              {installResult.success
                ? locale === 'zh-CN'
                  ? '安装完成'
                  : 'Installation Complete'
                : locale === 'zh-CN'
                  ? '安装结果'
                  : 'Installation Result'}
            </h3>
            <div className="mb-3 flex gap-4 text-sm">
              <span className="text-green-700">
                {locale === 'zh-CN' ? '已安装' : 'Installed'}: {installResult.installedPlugins}
              </span>
              <span className="text-gray-600">
                {locale === 'zh-CN' ? '已跳过' : 'Skipped'}: {installResult.skippedPlugins}
              </span>
              {installResult.failedPlugins > 0 && (
                <span className="text-red-600">
                  {locale === 'zh-CN' ? '失败' : 'Failed'}: {installResult.failedPlugins}
                </span>
              )}
            </div>
            <div className="space-y-1">
              {installResult.pluginResults.map((pr) => (
                <div key={pr.pluginCode} className="flex items-center gap-2 text-sm">
                  {pr.status === 'installed' && (
                    <CheckCircleIcon className="h-4 w-4 text-green-500" />
                  )}
                  {pr.status === 'skipped' && <CheckCircleIcon className="h-4 w-4 text-gray-400" />}
                  {pr.status === 'failed' && <XCircleIcon className="h-4 w-4 text-red-500" />}
                  <span className="font-mono text-xs">{pr.pluginCode}</span>
                  <span className="text-gray-500">{pr.message}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Included Plugins */}
        <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
          <h2 className="mb-4 flex items-center gap-2 text-lg font-semibold text-gray-900">
            <PuzzlePieceIcon className="h-5 w-5 text-indigo-600" />
            {locale === 'zh-CN' ? '包含的插件' : 'Included Plugins'} ({detail.plugins.length})
          </h2>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            {detail.plugins.map((plugin) => (
              <div
                key={plugin.pluginId}
                className="flex items-center gap-3 rounded-lg border border-gray-100 p-3 hover:bg-gray-50"
              >
                <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg bg-indigo-100">
                  <span className="text-sm font-bold text-indigo-600">
                    {(plugin.displayName || plugin.pluginId).charAt(0).toUpperCase()}
                  </span>
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-sm font-medium text-gray-900">
                      {plugin.displayName || plugin.pluginId}
                    </span>
                    {plugin.installed && (
                      <CheckCircleIcon className="h-4 w-4 flex-shrink-0 text-green-500" />
                    )}
                  </div>
                  {plugin.summary && (
                    <p className="truncate text-xs text-gray-500">{plugin.summary}</p>
                  )}
                </div>
                {plugin.availableInMarketplace && !plugin.installed && (
                  <span className="flex-shrink-0 text-xs text-indigo-600">
                    {locale === 'zh-CN' ? '待安装' : 'To install'}
                  </span>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Screenshots */}
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

        {/* Description / README */}
        {detail.readmeMarkdown && (
          <div className="mb-6 rounded-lg border border-gray-200 bg-white p-6">
            <h2 className="mb-3 text-lg font-semibold text-gray-900">
              {locale === 'zh-CN' ? '详细说明' : 'Documentation'}
            </h2>
            <div className="prose prose-sm max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{detail.readmeMarkdown}</ReactMarkdown>
            </div>
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

        {/* Reviews */}
        <ReviewSection targetType="marketplace_solution" targetId={detail.pid} />
      </div>
    </div>
  );
}
