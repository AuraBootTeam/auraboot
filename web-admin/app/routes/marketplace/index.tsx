import { useState, useEffect, useCallback } from 'react';
import { useNavigate, Link } from 'react-router';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import {
  MagnifyingGlassIcon,
  FunnelIcon,
  Squares2X2Icon,
  ArrowUpCircleIcon,
  XMarkIcon,
  CubeTransparentIcon,
} from '@heroicons/react/24/outline';
import PluginCard from './components/PluginCard';
import InstallDialog from './components/InstallDialog';
import UpgradeDialog from './components/UpgradeDialog';

interface MarketplacePlugin {
  pid: string;
  pluginId: string;
  namespace: string;
  displayName: string;
  summary: string;
  author: string;
  iconUrl?: string;
  pluginType: string;
  categoryCode: string;
  categoryName: string;
  tags: string[];
  status: string;
  featured: boolean;
  installCount: number;
  latestVersion: string;
  licenseMode: string;
  publishedAt: string;
  installed: boolean;
  installedVersion?: string;
}

interface Category {
  pid: string;
  code: string;
  displayNameZh: string;
  displayNameEn: string;
  icon: string;
  pluginCount: number;
}

export default function MarketplacePage() {
  const navigate = useNavigate();
  const { showErrorToast } = useToastContext();
  const { t, locale } = useI18n();

  const [plugins, setPlugins] = useState<MarketplacePlugin[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('');
  const [sort, setSort] = useState('popular');
  const [installTarget, setInstallTarget] = useState<MarketplacePlugin | null>(null);
  const [upgradeTarget, setUpgradeTarget] = useState<MarketplacePlugin | null>(null);
  const [upgradablePlugins, setUpgradablePlugins] = useState<MarketplacePlugin[]>([]);
  const [showUpgradeBanner, setShowUpgradeBanner] = useState(true);

  const fetchPlugins = useCallback(async () => {
    try {
      const params = new URLSearchParams();
      if (keyword) params.set('keyword', keyword);
      if (selectedCategory) params.set('category', selectedCategory);
      params.set('sort', sort);
      const res = await fetch(`/api/marketplace/plugins?${params.toString()}`);
      if (res.ok) {
        const json = await res.json();
        setPlugins(json.data ?? json ?? []);
      }
    } catch (e) {
      showErrorToast('Failed to load plugins');
    } finally {
      setLoading(false);
    }
  }, [keyword, selectedCategory, sort]);

  const fetchCategories = useCallback(async () => {
    try {
      const res = await fetch('/api/marketplace/categories');
      if (res.ok) {
        const json = await res.json();
        setCategories(json.data ?? json ?? []);
      }
    } catch (e) {
      // ignore
    }
  }, []);

  const fetchUpgradable = useCallback(async () => {
    try {
      const res = await fetch('/api/marketplace/upgrades');
      if (res.ok) {
        const json = await res.json();
        setUpgradablePlugins(json.data ?? []);
      }
    } catch (e) {
      // ignore
    }
  }, []);

  useEffect(() => {
    fetchCategories();
    fetchUpgradable();
  }, []);

  useEffect(() => {
    setLoading(true);
    fetchPlugins();
  }, [fetchPlugins]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    fetchPlugins();
  };

  const handleInstallSuccess = () => {
    setInstallTarget(null);
    fetchPlugins();
  };

  const handleUpgradeSuccess = () => {
    setUpgradeTarget(null);
    fetchPlugins();
    fetchUpgradable();
  };

  const getCategoryLabel = (cat: Category) => {
    return locale === 'zh-CN' ? cat.displayNameZh : cat.displayNameEn;
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white px-6 py-5">
        <div className="mx-auto max-w-7xl">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="flex items-center gap-2 text-2xl font-bold text-gray-900">
                <Squares2X2Icon className="h-7 w-7 text-indigo-600" />
                {locale === 'zh-CN' ? '插件市场' : 'Plugin Marketplace'}
              </h1>
              <p className="mt-1 text-sm text-gray-500">
                {locale === 'zh-CN'
                  ? '发现、安装和管理插件以扩展平台能力'
                  : 'Discover, install, and manage plugins to extend your platform'}
              </p>
            </div>
            <div className="flex items-center gap-3">
              <Link
                to="/marketplace/solutions"
                className="flex items-center gap-1.5 rounded-lg bg-indigo-50 px-4 py-2 text-sm font-medium text-indigo-700 transition-colors hover:bg-indigo-100"
              >
                <CubeTransparentIcon className="h-4 w-4" />
                {locale === 'zh-CN' ? '行业解决方案' : 'Industry Solutions'}
              </Link>
            </div>
            {/* Search */}
            <form onSubmit={handleSearch} className="flex items-center gap-2">
              <div className="relative">
                <MagnifyingGlassIcon className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400" />
                <input
                  type="text"
                  placeholder={locale === 'zh-CN' ? '搜索插件...' : 'Search plugins...'}
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  className="w-72 rounded-lg border border-gray-300 py-2 pr-4 pl-9 text-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500"
                />
              </div>
            </form>
          </div>
        </div>
      </div>

      {/* Upgrades Banner */}
      {showUpgradeBanner && upgradablePlugins.length > 0 && (
        <div
          className="border-b border-orange-200 bg-orange-50 px-6 py-3"
          data-testid="upgrade-banner"
        >
          <div className="mx-auto flex max-w-7xl items-center justify-between">
            <div className="flex items-center gap-2 text-orange-700">
              <ArrowUpCircleIcon className="h-5 w-5 flex-shrink-0" />
              <span className="text-sm font-medium">
                {locale === 'zh-CN'
                  ? `${upgradablePlugins.length} 个插件有可用更新`
                  : `${upgradablePlugins.length} plugin update${upgradablePlugins.length > 1 ? 's' : ''} available`}
              </span>
            </div>
            <button
              onClick={() => setShowUpgradeBanner(false)}
              className="text-orange-500 hover:text-orange-700"
            >
              <XMarkIcon className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}

      <div className="mx-auto max-w-7xl px-6 py-6">
        <div className="flex gap-6">
          {/* Category Sidebar */}
          <div className="w-48 flex-shrink-0">
            <h3 className="mb-3 text-xs font-semibold tracking-wider text-gray-500 uppercase">
              {locale === 'zh-CN' ? '分类' : 'Categories'}
            </h3>
            <nav className="space-y-1" data-testid="marketplace-categories">
              <button
                onClick={() => setSelectedCategory('')}
                className={`w-full rounded-md px-3 py-2 text-left text-sm ${
                  !selectedCategory
                    ? 'bg-indigo-50 font-medium text-indigo-700'
                    : 'text-gray-700 hover:bg-gray-100'
                }`}
              >
                {locale === 'zh-CN' ? '全部' : 'All'} ({plugins.length})
              </button>
              {categories.map((cat) => (
                <button
                  key={cat.code}
                  onClick={() => setSelectedCategory(cat.code)}
                  className={`w-full rounded-md px-3 py-2 text-left text-sm ${
                    selectedCategory === cat.code
                      ? 'bg-indigo-50 font-medium text-indigo-700'
                      : 'text-gray-700 hover:bg-gray-100'
                  }`}
                >
                  {getCategoryLabel(cat)}
                </button>
              ))}
            </nav>
          </div>

          {/* Main Content */}
          <div className="flex-1">
            {/* Sort Bar */}
            <div className="mb-4 flex items-center justify-between">
              <div className="flex items-center gap-1">
                <FunnelIcon className="h-4 w-4 text-gray-400" />
                <span className="mr-2 text-sm text-gray-500">
                  {locale === 'zh-CN' ? '排序:' : 'Sort:'}
                </span>
                {[
                  { key: 'popular', label: locale === 'zh-CN' ? '最热' : 'Popular' },
                  { key: 'newest', label: locale === 'zh-CN' ? '最新' : 'Newest' },
                  { key: 'name', label: locale === 'zh-CN' ? '名称' : 'Name' },
                ].map((opt) => (
                  <button
                    key={opt.key}
                    onClick={() => setSort(opt.key)}
                    className={`rounded-full px-3 py-1 text-xs font-medium ${
                      sort === opt.key
                        ? 'bg-indigo-100 text-indigo-700'
                        : 'text-gray-500 hover:bg-gray-100'
                    }`}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
              <span className="text-sm text-gray-400">
                {plugins.length} {locale === 'zh-CN' ? '个插件' : 'plugins'}
              </span>
            </div>

            {/* Plugin Grid */}
            {loading ? (
              <div className="flex items-center justify-center py-20">
                <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-indigo-600" />
              </div>
            ) : plugins.length === 0 ? (
              <div className="py-20 text-center text-gray-500">
                {locale === 'zh-CN' ? '暂无插件' : 'No plugins found'}
              </div>
            ) : (
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
                {plugins.map((plugin) => (
                  <PluginCard
                    key={plugin.pid}
                    plugin={plugin}
                    locale={locale}
                    onViewDetail={() =>
                      navigate(`/marketplace/${encodeURIComponent(plugin.pluginId)}`)
                    }
                    onInstall={() => setInstallTarget(plugin)}
                    onUpgrade={() => setUpgradeTarget(plugin)}
                  />
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Install Dialog */}
      {installTarget && (
        <InstallDialog
          plugin={installTarget}
          locale={locale}
          onClose={() => setInstallTarget(null)}
          onSuccess={handleInstallSuccess}
        />
      )}

      {/* Upgrade Dialog */}
      {upgradeTarget && upgradeTarget.installedVersion && (
        <UpgradeDialog
          plugin={{
            pid: upgradeTarget.pid,
            pluginId: upgradeTarget.pluginId,
            displayName: upgradeTarget.displayName,
            installedVersion: upgradeTarget.installedVersion,
            latestVersion: upgradeTarget.latestVersion,
          }}
          locale={locale}
          onClose={() => setUpgradeTarget(null)}
          onSuccess={handleUpgradeSuccess}
        />
      )}
    </div>
  );
}
