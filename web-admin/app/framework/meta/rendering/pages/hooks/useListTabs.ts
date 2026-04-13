/**
 * useListTabs — tab filter state management for list pages
 *
 * Manages the tabs block's active tab and generates
 * the corresponding filter condition for data loading.
 */

import { useState, useCallback, useMemo } from 'react';
import type { UnifiedSchema, ListTabConfig } from '~/framework/meta/schemas/types';

export interface TabFilter {
  fieldName: string;
  operator: string;
  value: string;
}

interface UseListTabsOptions {
  schema: UnifiedSchema | null;
}

interface UseListTabsResult {
  /** Currently active tab key */
  activeTab: string;
  /** Set active tab */
  setActiveTab: (key: string) => void;
  /** Tab configurations from schema */
  tabs: ListTabConfig[];
  /** Whether schema has tabs */
  hasTabs: boolean;
  /** Current tab filter condition (null for "all" tab) */
  getTabFilter: () => TabFilter | null;
}

export function useListTabs({ schema }: UseListTabsOptions): UseListTabsResult {
  const [activeTab, setActiveTab] = useState('all');

  const tabs = useMemo<ListTabConfig[]>(() => {
    if (!schema?.blocks) return [];
    const tabsBlock = schema.blocks.find((block) => block.blockType === 'tabs');
    return (tabsBlock?.tabs as ListTabConfig[]) || [];
  }, [schema]);

  const hasTabs = tabs.length > 0;

  const getTabFilter = useCallback((): TabFilter | null => {
    if (!hasTabs) return null;
    const currentTab = tabs.find((tab) => tab.key === activeTab);
    if (!currentTab?.filter) return null;
    const { field, value, operator } = currentTab.filter;
    return {
      fieldName: field,
      operator: operator || 'EQ',
      value: String(value),
    };
  }, [tabs, activeTab, hasTabs]);

  return {
    activeTab,
    setActiveTab,
    tabs,
    hasTabs,
    getTabFilter,
  };
}
