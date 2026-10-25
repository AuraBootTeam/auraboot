import { useState, useCallback, useMemo, useEffect } from 'react';
import { resolveTextFallback } from './detailFieldDisplay';
import {
  resolveVisibleDetailTabs,
  resolveVisibleDetailTabsFromBlocks,
  resolveVisibleTopLevelDetailBlocks,
  resolveActiveDetailTab,
} from './detailLayoutValues';
import type { useDetailLayout } from './useDetailLayout';
import type { useDetailRuntime } from './useDetailRuntime';
import type { useDetailPageState } from './useDetailPageState';

type UseDetailTabsArgs = Pick<ReturnType<typeof useDetailLayout>, 'allBlocks'> &
  Pick<ReturnType<typeof useDetailRuntime>, 'evaluateVisibleWhen' | 't'> &
  Pick<
    ReturnType<typeof useDetailPageState>,
    'hasPermission' | 'recordPid' | 'schema' | 'location' | 'routerNavigate'
  >;

export function useDetailTabs({
  allBlocks,
  evaluateVisibleWhen,
  hasPermission,
  recordPid,
  schema,
  location,
  routerNavigate,
  t,
}: UseDetailTabsArgs) {
  // A detail page may expose multiple phase-specific tab groups. Compose every
  // visible group instead of silently dropping all but the first one.
  const allTabs = resolveVisibleDetailTabsFromBlocks(allBlocks, evaluateVisibleWhen, hasPermission);
  const tabs = resolveVisibleDetailTabs(allTabs, recordPid, schema);
  const topLevelDetailBlocks = resolveVisibleTopLevelDetailBlocks(allBlocks, evaluateVisibleWhen);
  const [activeTab, setActiveTab] = useState(0);

  const tabHashKeys = useMemo(
    () =>
      tabs.map((tab, index) => {
        const rawKey =
          typeof tab.key === 'string' && tab.key.trim().length > 0
            ? tab.key.trim()
            : `tab-${index + 1}`;
        return decodeURIComponent(rawKey);
      }),
    [tabs],
  );

  const resolveTabIndexFromHash = useCallback(
    (hash: string): number => {
      if (!hash) return -1;
      const decodedHash = decodeURIComponent(hash.replace(/^#/, '').trim());
      if (!decodedHash) return -1;
      return tabHashKeys.findIndex((key) => key === decodedHash);
    },
    [tabHashKeys],
  );

  useEffect(() => {
    if (tabs.length === 0) return;
    const matchedIndex = resolveTabIndexFromHash(location.hash);
    if (matchedIndex >= 0) {
      setActiveTab(matchedIndex);
      return;
    }
    setActiveTab((current) => (current < tabs.length ? current : 0));
  }, [location.hash, resolveTabIndexFromHash, tabs.length]);

  const handleTabChange = useCallback(
    (index: number) => {
      setActiveTab(index);
      const nextTabKey = tabHashKeys[index];
      if (!nextTabKey) return;
      const nextHash = `#${encodeURIComponent(nextTabKey)}`;
      if (location.hash === nextHash) return;
      routerNavigate(
        {
          pathname: location.pathname,
          search: location.search,
          hash: nextHash,
        },
        { preventScrollReset: true },
      );
    },
    [location.hash, location.pathname, location.search, routerNavigate, tabHashKeys],
  );
  const activeDetailTab = resolveActiveDetailTab(tabs, activeTab);
  const actionFailedTitle = resolveTextFallback(t, 'common.actionFailed', 'Action failed');
  const closeLabel = resolveTextFallback(t, 'common.close', 'Close');
  return {
    tabs,
    topLevelDetailBlocks,
    activeTab,
    handleTabChange,
    activeDetailTab,
    actionFailedTitle,
    closeLabel,
  };
}
