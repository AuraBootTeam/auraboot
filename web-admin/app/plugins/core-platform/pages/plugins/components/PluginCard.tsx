import { ArrowDownTrayIcon, CheckCircleIcon, ArrowUpCircleIcon } from '@heroicons/react/24/outline';
import { StarIcon as StarSolidIcon } from '@heroicons/react/24/solid';

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
  featured: boolean;
  installCount: number;
  latestVersion: string;
  licenseMode: string;
  installed: boolean;
  installedVersion?: string;
  averageRating?: number;
  reviewCount?: number;
}

interface PluginCardProps {
  plugin: MarketplacePlugin;
  locale: string;
  onViewDetail: () => void;
  onInstall: () => void;
  onUpgrade?: () => void;
}

export default function PluginCard({
  plugin,
  locale,
  onViewDetail,
  onInstall,
  onUpgrade,
}: PluginCardProps) {
  const hasUpgrade =
    plugin.installed && plugin.installedVersion && plugin.installedVersion !== plugin.latestVersion;
  const requiresLicense = Boolean(plugin.licenseMode && plugin.licenseMode !== 'free');
  return (
    <div
      className="group cursor-pointer rounded-lg border border-gray-200 bg-white p-4 transition-shadow hover:shadow-md"
      onClick={onViewDetail}
    >
      <div className="flex items-start gap-3">
        {/* Icon */}
        <div className="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-lg bg-indigo-100">
          <span className="text-lg font-bold text-indigo-600">
            {(plugin.displayName || plugin.pluginId).charAt(0).toUpperCase()}
          </span>
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h3 className="truncate text-sm font-semibold text-gray-900 group-hover:text-indigo-600">
              {plugin.displayName || plugin.pluginId}
            </h3>
            {plugin.featured && <StarSolidIcon className="h-4 w-4 flex-shrink-0 text-amber-400" />}
            {hasUpgrade && (
              <span className="flex-shrink-0 rounded-full bg-orange-100 px-1.5 py-0.5 text-[10px] font-semibold text-orange-700">
                {locale === 'zh-CN' ? '可更新' : 'Update'}
              </span>
            )}
            {plugin.licenseMode && plugin.licenseMode !== 'free' && (
              <span className="rounded bg-purple-100 px-1.5 py-0.5 text-xs text-purple-700">
                {plugin.licenseMode === 'platform' ? 'License' : 'Vendor'}
              </span>
            )}
          </div>
          <p className="mt-0.5 line-clamp-2 text-xs text-gray-500">{plugin.summary}</p>
        </div>
      </div>

      <div className="mt-3 flex items-center justify-between border-t border-gray-100 pt-3">
        <div className="flex items-center gap-3 text-xs text-gray-400">
          <span className="font-mono">v{plugin.latestVersion}</span>
          <span className="flex items-center gap-0.5">
            <ArrowDownTrayIcon className="h-3 w-3" />
            {plugin.installCount}
          </span>
          {plugin.reviewCount && plugin.reviewCount > 0 && plugin.averageRating ? (
            <span className="flex items-center gap-0.5">
              <StarSolidIcon className="h-3 w-3 text-amber-400" />
              {plugin.averageRating.toFixed(1)}
              <span className="text-gray-300">({plugin.reviewCount})</span>
            </span>
          ) : null}
          {plugin.categoryName && (
            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-500">
              {plugin.categoryName}
            </span>
          )}
        </div>

        {plugin.installed ? (
          hasUpgrade ? (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onUpgrade?.();
              }}
              className="flex items-center gap-1 rounded bg-orange-500 px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-orange-600"
            >
              <ArrowUpCircleIcon className="h-3.5 w-3.5" />
              {locale === 'zh-CN' ? '升级' : 'Upgrade'}
            </button>
          ) : (
            <span
              className="flex items-center gap-1 text-xs font-medium text-green-600"
              onClick={(e) => e.stopPropagation()}
            >
              <CheckCircleIcon className="h-4 w-4" />
              {locale === 'zh-CN' ? '已安装' : 'Installed'}
            </span>
          )
        ) : (
          <button
            onClick={(e) => {
              e.stopPropagation();
              onInstall();
            }}
            data-testid={requiresLicense ? 'marketplace-card-paid-cta' : 'marketplace-card-install'}
            className="rounded bg-indigo-600 px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-indigo-700"
          >
            {requiresLicense
              ? locale === 'zh-CN'
                ? '购买'
                : 'Buy'
              : locale === 'zh-CN'
                ? '安装'
                : 'Install'}
          </button>
        )}
      </div>
    </div>
  );
}
