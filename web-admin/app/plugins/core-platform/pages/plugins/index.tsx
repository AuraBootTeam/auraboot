/**
 * Unified Plugin Management page (/plugins).
 *
 * Consolidates the previously separate /marketplace (remote discovery) and
 * /system/plugins (locally installed + import history) pages into a single
 * three-tab experience:
 *
 *   - discovery : browse remote marketplace plugins (requires plugin_management
 *                 permission; gracefully hidden on OSS installs without
 *                 marketplace API connectivity)
 *   - installed : enable/disable/uninstall local plugins, upload new packages
 *   - history   : import audit trail
 *
 * Tab selection is persisted in the `?tab=` query param so refreshes land on
 * the same tab.
 */

import { useState, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { useAuth } from '~/contexts/AuthContext';
import {
  PuzzlePieceIcon,
  Squares2X2Icon,
  DocumentTextIcon,
  CubeTransparentIcon,
} from '@heroicons/react/24/outline';
import { PuzzlePieceIcon as PuzzlePieceSolidIcon } from '@heroicons/react/24/solid';
import DiscoveryTab from './components/DiscoveryTab';
import InstalledTab from './components/InstalledTab';
import HistoryTab from './components/HistoryTab';
import SolutionsTab from './components/SolutionsTab';

type TabKey = 'discovery' | 'solutions' | 'installed' | 'history';

const DISCOVERY_PERMISSION = 'plugin_management';
const MANAGE_PERMISSION = 'plugin_management';

export default function PluginsPage() {
  const { t } = useI18n();
  const { hasPermission } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();

  const canDiscover = hasPermission(DISCOVERY_PERMISSION);
  const canManage = hasPermission(MANAGE_PERMISSION);

  const availableTabs = useMemo(() => {
    const tabs: TabKey[] = [];
    if (canDiscover) tabs.push('discovery', 'solutions');
    if (canManage) tabs.push('installed', 'history');
    return tabs;
  }, [canDiscover, canManage]);

  const requestedTab = searchParams.get('tab') as TabKey | null;
  const activeTab: TabKey =
    requestedTab && availableTabs.includes(requestedTab)
      ? requestedTab
      : (availableTabs[0] ?? 'installed');

  const [installedCount, setInstalledCount] = useState(0);
  const [historyCount, setHistoryCount] = useState(0);
  const [historyRefresh, setHistoryRefresh] = useState(0);

  const setTab = (tab: TabKey) => {
    const next = new URLSearchParams(searchParams);
    next.set('tab', tab);
    setSearchParams(next, { replace: true });
  };

  const tabMeta: Record<TabKey, { label: string; icon: typeof Squares2X2Icon; count?: number }> = {
    discovery: {
      label: t('plugin.tab.discovery'),
      icon: Squares2X2Icon,
    },
    solutions: {
      label: t('plugin.tab.solutions'),
      icon: CubeTransparentIcon,
    },
    installed: {
      label: t('plugin.tab.installed'),
      icon: PuzzlePieceIcon,
      count: installedCount,
    },
    history: {
      label: t('plugin.tab.history'),
      icon: DocumentTextIcon,
      count: historyCount,
    },
  };

  if (availableTabs.length === 0) {
    return (
      <div className="mx-auto max-w-7xl p-6">
        <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 p-12 text-center text-sm text-gray-600">
          {t('plugin.permission.denied')}
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-7xl p-6" data-testid="plugins-page">
      {/* Header */}
      <div className="mb-6 flex items-center gap-3">
        <PuzzlePieceSolidIcon className="h-8 w-8 text-indigo-600" />
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{t('plugin.title')}</h1>
          <p className="text-sm text-gray-500">{t('plugin.description')}</p>
        </div>
      </div>

      {/* Tabs */}
      <div className="mb-6 border-b border-gray-200">
        <nav
          className="-mb-px flex"
          data-testid="plugins-tabs"
          role="tablist"
          aria-label={t('plugin.title')}
        >
          {availableTabs.map((key) => {
            const meta = tabMeta[key];
            const Icon = meta.icon;
            const active = activeTab === key;
            return (
              <button
                key={key}
                onClick={() => setTab(key)}
                data-testid={`plugins-tab-${key}`}
                role="tab"
                aria-selected={active}
                aria-controls={`plugins-tabpanel-${key}`}
                id={`plugins-tab-${key}`}
                type="button"
                className={`border-b-2 px-6 py-3 text-sm font-medium ${
                  active
                    ? 'border-indigo-500 text-indigo-600'
                    : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
                }`}
              >
                <Icon className="mr-2 inline h-4 w-4" />
                {meta.label}
                {typeof meta.count === 'number' ? ` (${meta.count})` : null}
              </button>
            );
          })}
        </nav>
      </div>

      {/* Content */}
      {activeTab === 'discovery' && <DiscoveryTab />}
      {activeTab === 'solutions' && <SolutionsTab />}
      {activeTab === 'installed' && (
        <InstalledTab
          onCountChange={setInstalledCount}
          onImportSuccess={() => setHistoryRefresh((v) => v + 1)}
        />
      )}
      {activeTab === 'history' && (
        <HistoryTab onCountChange={setHistoryCount} refreshToken={historyRefresh} />
      )}
    </div>
  );
}
