import { ResultHelper } from '~/utils/type';
import { useDebouncedCallback } from '~/hooks/useDebouncedValue';
import { useCallback, useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router';
import type { ButtonConfig, FieldConfig } from '~/framework/meta/schemas/types';
import {
  type SortConfig,
  type ViewFilterConfig,
  type ViewType,
} from '~/framework/smart/types/savedView';
import { type QuickFilterPresetKey } from './quickFilterPresets';
import { encodeSorts } from './useListUrlState';

export interface ListLoadDataParams {
  page?: number;
  size?: number;
  filters?: Record<string, any>;
  sorts?: SortConfig[];
  chipFilters?: ViewFilterConfig[];
  retryRequest?: ListApiReadRequest;
}

export interface ListApiReadRequest {
  endpoint: string;
  method: 'get' | 'post' | 'put' | 'delete' | 'patch' | 'options';
  queryParams: Record<string, any>;
}

export function useListApiReadRetry() {
  const failedRequestRef = useRef<ListApiReadRequest | null>(null);
  const clearFailure = useCallback(() => { failedRequestRef.current = null; }, []);
  const getFailedRequest = useCallback(() => (
    failedRequestRef.current ? structuredClone(failedRequestRef.current) : null
  ), []);
  const read = useCallback(async <T extends { code: string | number; data?: unknown },>(
    request: ListApiReadRequest,
    isLatest: () => boolean,
    fetch: (request: ListApiReadRequest) => Promise<T>,
  ): Promise<T> => {
    const sealedRequest = structuredClone(request);
    try {
      const result = await fetch(structuredClone(sealedRequest));
      if (isLatest()) {
        failedRequestRef.current = ResultHelper.isSuccess(result) && result.data
          ? null : sealedRequest;
      }
      return result;
    } catch (error) {
      if (isLatest()) failedRequestRef.current = sealedRequest;
      throw error;
    }
  }, []);
  return { read, getFailedRequest, clearFailure };
}

export function useListKeywordSearch(
  load: (params: ListLoadDataParams) => void,
  enabled: boolean,
  pageSize: number,
  initialFilters: Record<string, any>,
) {
  const appliedFiltersRef = useRef(structuredClone(initialFilters));
  const rememberSubmittedFilters = useCallback((filters: Record<string, any>) => {
    appliedFiltersRef.current = structuredClone(filters);
  }, []);
  const getSubmittedFilters = useCallback(() => structuredClone(appliedFiltersRef.current), []);
  const search = () => {
    if (!enabled) return;
    load({ page: 0, size: pageSize, filters: structuredClone(appliedFiltersRef.current) });
  };
  const debouncedSearch = useDebouncedCallback(search, 300);
  const commitSearch = () => {
    debouncedSearch.cancel();
    search();
  };
  return { debouncedSearch, commitSearch, rememberSubmittedFilters, getSubmittedFilters };
}

export interface BulkFieldCommandState {
  button: ButtonConfig;
  selectedIds: string[];
  selectedCount: number;
  actionLabel: string;
  field: FieldConfig;
  operationType: 'UPDATE' | 'DELETE';
}

export function clearTransientViewSearchParams(
  params: URLSearchParams,
  knownFieldNames?: ReadonlySet<string>,
): void {
  for (const key of Array.from(params.keys())) {
    if (
      key === 'sort' ||
      key === 'keyword' ||
      key === 'filters' ||
      key === 'preset' ||
      key === 'pageNum' ||
      key.startsWith('filter_') ||
      (knownFieldNames?.has(key) ?? false)
    ) {
      params.delete(key);
    }
  }
}

/**
 * Claim the next list-data request and return a guard for state commits.
 *
 * List pages can start several requests during mount while a user immediately
 * searches or changes a view. Network completion order is not intent order, so
 * only the most recently started request may update data, pagination, errors,
 * or loading state.
 */
export function beginLatestListRequest(sequenceRef: { current: number }): () => boolean {
  const requestSequence = ++sequenceRef.current;
  return () => requestSequence === sequenceRef.current;
}

export function useRestoreSavedViewFromUrl({
  urlViewPid,
  savedViews,
  viewsLoading,
  selectView,
  setActiveViewType,
}: {
  urlViewPid: string | null;
  savedViews: Array<{ pid: string; viewType?: string | null }>;
  viewsLoading: boolean;
  selectView: (pid: string) => void;
  setActiveViewType: (viewType: ViewType) => void;
}): void {
  useEffect(() => {
    if (!urlViewPid || savedViews.length === 0 || viewsLoading) {
      return;
    }

    const match = savedViews.find((v) => v.pid === urlViewPid);
    if (!match) {
      return;
    }

    selectView(urlViewPid);
    if (match.viewType && match.viewType !== 'table') {
      setActiveViewType(match.viewType as ViewType);
    }
  }, [urlViewPid, savedViews, viewsLoading, selectView, setActiveViewType]);
}

export type SearchParamsSetter = ReturnType<typeof useSearchParams>[1];

export { useSerializedSearchParamsUpdater } from './useSerializedSearchParamsUpdater';

export function applyLocalSortUpdate(
  previous: SortConfig[],
  update: SortConfig[] | ((previous: SortConfig[]) => SortConfig[]),
  pendingUrlSync: { current: string | null | undefined },
): SortConfig[] {
  const next = typeof update === 'function' ? update(previous) : update;
  pendingUrlSync.current = encodeSorts(next);
  return next;
}

export interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export const INVITE_LABELS = {
  title: { 'zh-CN': '成员邀请', en: 'Member Invite' },
  close: { 'zh-CN': '关闭', en: 'Close' },
  current: { 'zh-CN': '当前邀请码', en: 'Current Invite Code' },
  expires: { 'zh-CN': '有效期至', en: 'Expires at' },
  generate: { 'zh-CN': '生成邀请码', en: 'Generate Invite Code' },
  newCode: { 'zh-CN': '生成新邀请码', en: 'Generate New Invite Code' },
  revoke: { 'zh-CN': '撤销当前邀请码', en: 'Revoke Current Invite Code' },
  empty: {
    'zh-CN': '当前没有有效邀请码，可生成邀请码邀请成员加入企业。',
    en: 'No active invite code. Generate one to invite members into this tenant.',
  },
  policy: {
    'zh-CN': '生成新邀请码不会自动撤销已有邀请码；撤销仅影响当前显示的邀请码。',
    en: 'Generating a new code does not revoke existing codes. Revocation affects only the displayed code.',
  },
  loading: { 'zh-CN': '正在读取邀请码…', en: 'Loading invite code\u2026' },
  retry: { 'zh-CN': '重新读取', en: 'Retry Read' },
  readFailed: {
    'zh-CN': '邀请码读取失败，请重新读取后再操作。',
    en: 'Unable to load the invite code. Retry before making changes.',
  },
  generatedReadFailed: {
    'zh-CN': '邀请码已生成，但读取当前状态失败。请重新读取，避免重复生成。',
    en: 'The invite code was generated, but its current state could not be read. Retry the read to avoid generating another code.',
  },
  revokedReadFailed: {
    'zh-CN': '当前邀请码已撤销，但读取剩余邀请码失败。请重新读取。',
    en: 'The displayed code was revoked, but remaining codes could not be read. Retry the read.',
  },
  generated: { 'zh-CN': '邀请码已生成', en: 'Invite code generated' },
  revoked: { 'zh-CN': '当前邀请码已撤销', en: 'Current invite code revoked' },
  generateFailed: {
    'zh-CN': '邀请码生成失败，请稍后重试。',
    en: 'Unable to generate an invite code. Try again later.',
  },
  revokeFailed: {
    'zh-CN': '邀请码撤销失败，请稍后重试。',
    en: 'Unable to revoke the invite code. Try again later.',
  },
} as const;

export interface InviteCodeData {
  code: string;
  expiredAt?: string;
  createdAt?: string;
}

// Quick filter chip definitions — preset views (T8). Keys + filter logic live
// in ./list/quickFilterPresets so the toolbar and the view switcher share one
// source of truth.
export type QuickFilterKey = QuickFilterPresetKey;
