import type { FieldConfig } from '~/framework/meta/schemas/types';
import { findListFilterTabsBlock } from './listSchemaPolicy';
import React, { useEffect, useMemo, useState } from 'react';
import { type ViewFilterConfig } from '~/framework/smart/types/savedView';
import { buildApiEndpoint, getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { type User, ResultHelper } from '~/utils/type';

import { applyApiTabCondition, applyApiUserFilters, tabFilterToQueryCondition, type ListQueryFilterCondition } from './listFilterConditions';
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
  getSubmittedFilters: () => Record<string, any>;
  chipFilters: ViewFilterConfig[];
  modelFieldMapRef: React.RefObject<Map<string, any>>;
  token: string | null | undefined;
  keywordRef: React.RefObject<string>;
  data: DynamicEntity[];
  schemaExtension: any;
  filterBlock?: { fields?: FieldConfig[] };
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
  getSubmittedFilters,
  chipFilters,
  modelFieldMapRef,
  token,
  keywordRef,
  data,
  schemaExtension,
  filterBlock,
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
        const api = schema.dataSource?.type === 'api' && Boolean(schema.dataSource.endpoint);
        const submitted = getSubmittedFilters();
        const params: Record<string, any> = api ? { page: 0, size: 1 } : { pageNum: 1, pageSize: 1 };
        if (keywordRef.current.trim()) params.keyword = keywordRef.current.trim();
        if (api) {
          applyApiUserFilters(params, submitted, filterBlock?.fields);
          applyApiTabCondition(params, condition);
        } else {
          const queryFilters = buildFiltersParam(condition, submitted, chipFilters, modelFieldMapRef.current);
          if (queryFilters) params.filters = queryFilters;
        }
        const result = await fetchResult<PaginationResult<DynamicEntity>>(
          api ? schema.dataSource.endpoint : `${buildApiEndpoint(schema.modelCode!)}/list`,
          {
            method: api ? (schema.dataSource.method || 'get') : 'get',
            token: token || undefined,
            params,
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
  }, [listTabsBlock, filterBlock, schema?.modelCode, schema?.dataSource, data, dataReady, getSubmittedFilters, chipFilters, token, user?.pid]);
  const workspace = schemaExtension.presentation === 'workspace';

  return { listPageTitle, listTabsBlock, workspaceTabCounts, workspaceTabCountError, workspace };
}
