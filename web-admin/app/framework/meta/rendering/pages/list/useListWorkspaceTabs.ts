import { findListFilterTabsBlock } from './listSchemaPolicy';
import React, { useEffect, useMemo, useState } from 'react';
import { type ViewFilterConfig } from '~/framework/smart/types/savedView';
import { buildApiEndpoint, getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { type User, ResultHelper } from '~/utils/type';

import { tabFilterToQueryCondition, type ListQueryFilterCondition } from './listFilterConditions';
import type { PaginationResult } from './listPageStateUtils';
import type { DynamicEntity } from './listPageTypes';

interface ListWorkspaceTabsInput {
  t: (key: string) => string;
  locale: string;
  tableName: string;
  allBlocks: any;
  schema: any;
  dataReady: boolean;
  user: User | null;
  buildFiltersParam: (
    tabCondition: ListQueryFilterCondition | null,
    userFilters?: Record<string, any>,
    chipFiltersList?: ViewFilterConfig[],
    filterFieldMap?: Map<string, any>,
  ) => string | undefined;
  filters: Record<string, any>;
  chipFilters: ViewFilterConfig[];
  modelFieldMapRef: React.RefObject<Map<string, any>>;
  token: string | null | undefined;
  keywordRef: React.RefObject<string>;
  data: DynamicEntity[];
  schemaExtension: any;
}

/** Load workspace tab counts from the active list query. */
export function useListWorkspaceTabs({
  t,
  locale,
  tableName,
  allBlocks,
  schema,
  dataReady,
  user,
  buildFiltersParam,
  filters,
  chipFilters,
  modelFieldMapRef,
  token,
  keywordRef,
  data,
  schemaExtension,
}: ListWorkspaceTabsInput) {
  const listTabsBlock = useMemo(() => {
    const found = findListFilterTabsBlock(allBlocks);
    return found;
  }, [allBlocks]);

  const listPageTitle = (() => {
    // Import writes page titles into the tenant i18n bundle as
    // page.<pageKey>.title (en + zh-CN). Prefer that key so the title
    // follows the UI locale; schema.title/schema.name are stored as
    // plain zh strings and would otherwise pin the title to zh.
    const listPageKey = schema.pageKey || tableName;
    const keyedTitle = t(`page.${listPageKey}.title`);
    if (keyedTitle && keyedTitle !== `page.${listPageKey}.title`) {
      return keyedTitle;
    }
    if (schema.title) return getLocalizedText(schema.title, locale, t);
    if (schema.name && schema.name.trim()) {
      return getLocalizedText(schema.name, locale, t);
    }
    return tableName;
  })();
  const [workspaceTabCounts, setWorkspaceTabCounts] = useState<Record<string, number>>({});
  const [workspaceTabCountError, setWorkspaceTabCountError] = useState(false);
  useEffect(() => {
    if (!listTabsBlock?.showCounts || !schema?.modelCode || !dataReady) return;
    let current = true;
    setWorkspaceTabCounts({});
    setWorkspaceTabCountError(false);
    Promise.all(
      (listTabsBlock.tabs as any[]).map(async (tab) => {
        const condition = tabFilterToQueryCondition(tab.filter, user?.pid);
        const queryFilters = buildFiltersParam(
          condition,
          filters,
          chipFilters,
          modelFieldMapRef.current,
        );
        const result = await fetchResult<PaginationResult<DynamicEntity>>(
          `${buildApiEndpoint(schema.modelCode!)}/list`,
          {
            method: 'get',
            token: token || undefined,
            params: {
              pageNum: 1,
              pageSize: 1,
              ...(keywordRef.current.trim() ? { keyword: keywordRef.current.trim() } : {}),
              ...(queryFilters ? { filters: queryFilters } : {}),
            },
          },
        );
        if (!ResultHelper.isSuccess(result) || !result.data || Array.isArray(result.data))
          throw new Error('View count query failed.');
        const total = Number(result.data.total);
        if (!Number.isFinite(total)) throw new Error('View count is invalid.');
        return [tab.key, total] as const;
      }),
    )
      .then((entries) => {
        if (current) setWorkspaceTabCounts(Object.fromEntries(entries));
      })
      .catch(() => {
        if (current) setWorkspaceTabCountError(true);
      });
    return () => {
      current = false;
    };
  }, [listTabsBlock, schema?.modelCode, data, dataReady, filters, chipFilters, token, user?.pid]);
  const workspace = schemaExtension.presentation === 'workspace';

  return { listPageTitle, listTabsBlock, workspaceTabCounts, workspaceTabCountError, workspace };
}
