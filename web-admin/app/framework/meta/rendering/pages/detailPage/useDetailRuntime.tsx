import { reloadDetailForReview } from './reloadDetailForReview';
import { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { usePageRuntime } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import { useRuntimeStateSubscription } from '~/framework/meta/rendering/blocks/workbenchBlockUtils';
import {
  resolvePermissionHiddenDataSourceIds,
  evaluateDetailVisibleWhen,
} from './detailLayoutValues';
import type { useDetailPageState } from './useDetailPageState';
import type { useDetailMetadata } from './useDetailMetadata';
import type { useDetailRecord } from './useDetailRecord';

type UseDetailRuntimeArgs = Pick<
  ReturnType<typeof useDetailPageState>,
  'recordData' | 'schema' | 'recordPid' | 'hasPermission' | 'token' | 'tableName'
> &
  Pick<ReturnType<typeof useDetailMetadata>, 'showToast'> &
  Pick<ReturnType<typeof useDetailRecord>, 'reloadRecordStrict' | 'reloadRecord'>;

export function useDetailRuntime({
  recordData,
  schema,
  recordPid,
  hasPermission,
  token,
  showToast,
  reloadRecordStrict,
  reloadRecord,
  tableName,
}: UseDetailRuntimeArgs) {
  const runtimeContext = useMemo(
    () => ({
      record: recordData,
      row: recordData,
      form: recordData,
      $page: {
        kind: (schema as any)?.kind,
        modelCode: (schema as any)?.modelCode,
        pageKey: (schema as any)?.pageKey,
        recordPid: recordPid || undefined,
      },
    }),
    [recordData, recordPid, schema],
  );

  // Data sources referenced only by permission-hidden tabs must not fire: the
  // runtime registers and auto-fetches eagerly, and a surface the user cannot
  // open would still collect designed backend 403s on every page load.
  const permissionHiddenDataSourceIds = useMemo(
    () => resolvePermissionHiddenDataSourceIds(schema, hasPermission),
    [schema, hasPermission],
  );

  // Use usePageRuntime instead of useDynamicPageSetup
  const { runtime, dataSourceManager, t, locale, navigate } = usePageRuntime(schema, {
    token: token || undefined,
    showToast,
    additionalContext: runtimeContext,
    loadData: reloadRecordStrict,
    excludeDataSourceIds: permissionHiddenDataSourceIds,
  });
  useRuntimeStateSubscription(runtime);
  const [detailRefreshVersion, setDetailRefreshVersion] = useState(0);
  const refreshDetailAfterChildChange = useCallback(async () => {
    await reloadRecord();
    const sourceIds = Object.keys(schema?.dataSources || {}).filter(
      (id) => !permissionHiddenDataSourceIds.has(id),
    );
    if (sourceIds.length > 0) await dataSourceManager.reload(sourceIds);
    setDetailRefreshVersion((version) => version + 1);
  }, [reloadRecord, schema?.dataSources, permissionHiddenDataSourceIds, dataSourceManager]);

  const pendingReviews = useRef(new Set<AbortController>());
  useEffect(() => {
    const pending = pendingReviews.current;
    return () => {
      pending.forEach((controller) => controller.abort());
      pending.clear();
    };
  }, [dataSourceManager, recordPid, tableName, token]);

  const refreshDetailForReview = useCallback(async () => {
    const sourceIds = Object.keys(schema?.dataSources || {}).filter(
      (id) => !permissionHiddenDataSourceIds.has(id),
    );
    const controller = new AbortController();
    pendingReviews.current.add(controller);
    try {
      await reloadDetailForReview(
        reloadRecordStrict,
        dataSourceManager,
        sourceIds,
        controller.signal,
      );
      setDetailRefreshVersion((version) => version + 1);
    } finally {
      pendingReviews.current.delete(controller);
    }
  }, [reloadRecordStrict, schema?.dataSources, permissionHiddenDataSourceIds, dataSourceManager]);

  // Use unified action handler for toolbar buttons (commandCode, navigateTo)
  const {
    handleAction,
    loading: actionLoading,
    error: actionError,
    setError,
  } = useActionHandler({
    runtime,
    navigate,
    tableName,
    // Route segments are page keys on custom pages; record reads authorize by
    // model, so pin the command snapshot against the schema's model.
    recordReadModel: schema?.modelCode,
    context: {
      record: recordData,
      loadData: refreshDetailAfterChildChange,
      loadDataForReview: refreshDetailForReview,
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
  });

  // Evaluate visibleWhen against both the record and the reactive page state.
  // Top-level blocks live outside BlockRenderer, so they must subscribe here;
  // otherwise state.set updates sibling action bars but never reveals these blocks.
  const evaluateVisibleWhen = useCallback(
    (visibleWhen: string | undefined): boolean => {
      return evaluateDetailVisibleWhen(visibleWhen, recordData, runtime?.getContext());
    },
    [recordData, runtime],
  );

  // Resolve button label using i18n conventions
  const resolveButtonLabel = useCallback(
    (button: ButtonConfig): string => {
      if (button.content) return getLocalizedText(button.content, locale, t);
      if (button.label) return getLocalizedText(button.label, locale, t);
      // Try plugin-namespaced key from commandCode (e.g., "crm:contact_lead" → "crm.action.contact_lead")
      if (button.commandCode && button.commandCode.includes(':')) {
        const [ns, actionCode] = button.commandCode.split(':');
        const nsKey = `${ns}.action.${actionCode}`;
        const nsResolved = t(nsKey);
        if (nsResolved !== nsKey) return nsResolved;
      }
      if (button.label) {
        const labelStr = typeof button.label === 'string' ? button.label : undefined;
        if (labelStr) {
          const labelResolved = t(labelStr);
          if (labelResolved && labelResolved !== labelStr) return labelResolved;
          return labelStr;
        }
      }
      if (button.action && typeof button.action === 'string') {
        // Map action values to i18n keys (e.g., "edit" → "update" in i18n)
        const actionKeyMap: Record<string, string> = {
          edit: 'update',
          navigate: 'view',
        };
        const i18nAction = actionKeyMap[button.action] || button.action;
        const i18nKey = `action.${i18nAction}`;
        const resolved = t(i18nKey);
        if (resolved !== i18nKey) return resolved;
      }
      // Try button code as i18n key
      const codeKey = `action.${button.code}`;
      const codeResolved = t(codeKey);
      if (codeResolved !== codeKey) return codeResolved;
      return button.code;
    },
    [locale, t],
  );
  return {
    runtime,
    dataSourceManager,
    t,
    locale,
    detailRefreshVersion,
    refreshDetailAfterChildChange,
    handleAction,
    actionLoading,
    actionError,
    setError,
    evaluateVisibleWhen,
    resolveButtonLabel,
  };
}
