import { useState, useCallback, useEffect, useRef } from 'react';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import {
  mergeLoadedRecordWithDirtyFields,
  normalizeLoadedRecordResponseForForm,
} from './formRecordValues';
import { resolveEditRecordEndpoint, shouldLoadMainRecordForForm } from './formActions';
import type { useFormPageState } from './useFormPageState';
import type { useFormPageRuntime } from './useFormPageRuntime';

type UseFormRecordArgs = Pick<
  ReturnType<typeof useFormPageState>,
  | 'isEditMode'
  | 'recordPid'
  | 'schema'
  | 'dirtyFieldsRef'
  | 'tableName'
  | 'token'
  | 'setFormData'
  | 'setInitialFormData'
  | 'formData'
  | 'showToast'
  | 'setFieldErrors'
  | 'setSummaryErrors'
> &
  Pick<
    ReturnType<typeof useFormPageRuntime>,
    'runtime' | 'navigate' | 'dataSourceManager' | 'locale' | 't'
  >;

export function useFormRecord({
  isEditMode,
  recordPid,
  schema,
  dirtyFieldsRef,
  tableName,
  token,
  setFormData,
  setInitialFormData,
  runtime,
  formData,
  navigate,
  dataSourceManager,
  locale,
  t,
  showToast,
  setFieldErrors,
  setSummaryErrors,
}: UseFormRecordArgs) {
  const [mainRecordLoaded, setMainRecordLoaded] = useState(!isEditMode);
  const fieldDataTypesRef = useRef<Record<string, string>>({});
  const loadMainRecord = useCallback(
    async (options?: { preserveDirty?: boolean }) => {
      if (!shouldLoadMainRecordForForm(recordPid, schema)) return;
      const preserveDirty = options?.preserveDirty ?? true;
      dirtyFieldsRef.current.clear();
      setMainRecordLoaded(false);
      try {
        const endpoint = resolveEditRecordEndpoint(schema, tableName, recordPid || undefined);
        const recordSource = schema?.recordSource || schema?.extension?.recordSource;
        const resp = await fetchResult<any>(endpoint, {
          method: (recordSource?.method as any) || 'get',
          token: token || undefined,
        });
        if (ResultHelper.isSuccess(resp) && resp.data) {
          const normalizedRecord = normalizeLoadedRecordResponseForForm(
            resp.data,
            fieldDataTypesRef.current,
          );
          setFormData((prev) =>
            preserveDirty
              ? mergeLoadedRecordWithDirtyFields(normalizedRecord, prev, dirtyFieldsRef.current)
              : normalizedRecord,
          );
          setInitialFormData(normalizedRecord);
        }
      } catch {
        // Keep the form usable when a transient refresh request fails; callers
        // surface action errors separately when needed.
      } finally {
        setMainRecordLoaded(true);
      }
    },
    [recordPid, schema, tableName, token, dirtyFieldsRef, setFormData, setInitialFormData],
  );
  const reloadMainRecord = useCallback(
    () => loadMainRecord({ preserveDirty: false }),
    [loadMainRecord],
  );

  const syncRuntimeFormScope = useCallback(
    (nextFormData: Record<string, any>) => {
      if (!runtime) return;
      const scopeId = runtime.getScopeId();
      runtime.getStateManager().updateScope(scopeId, {
        form: recordPid ? { ...nextFormData, pid: recordPid } : nextFormData,
      });
    },
    [runtime, recordPid],
  );

  const syncRuntimeFieldValue = useCallback(
    (fieldCode: string, value: unknown) => {
      if (!runtime) return;
      const scopeId = runtime.getScopeId();
      runtime.getStateManager().updateScope(scopeId, (prev) => ({
        form: {
          ...(prev.form || {}),
          [fieldCode]: value,
          ...(recordPid ? { pid: recordPid } : {}),
        },
      }));
    },
    [runtime, recordPid],
  );

  // Tracks an in-flight submit so the form buttons disable + show a spinner.
  // Save/submit run through handleFormAction -> fetchResult directly (not
  // useActionHandler), so its `loading` never flips for these paths; this
  // local flag covers them and prevents duplicate submits.
  const [submitting, setSubmitting] = useState(false);

  // Sync formData with runtime scope state
  useEffect(() => {
    syncRuntimeFormScope(formData);
  }, [formData, syncRuntimeFormScope]);

  // Use unified action handler hook with SchemaRuntime support
  const { handleAction, loading, error, setError } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {
      data: formData,
      setData: setFormData,
      loadData: reloadMainRecord,
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
  });

  const clearFieldError = useCallback(
    (fieldCode: string) => {
      setFieldErrors((prev) => {
        if (!prev[fieldCode]) return prev;
        const next = { ...prev };
        delete next[fieldCode];
        return next;
      });
      setSummaryErrors([]);
      setError(null);
    },
    [setError, setFieldErrors, setSummaryErrors],
  );
  return {
    mainRecordLoaded,
    fieldDataTypesRef,
    loadMainRecord,
    syncRuntimeFieldValue,
    submitting,
    setSubmitting,
    handleAction,
    loading,
    error,
    setError,
    clearFieldError,
  };
}
