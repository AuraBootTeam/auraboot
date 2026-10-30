import { useCallback, useEffect } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import {
  isDetailRecordAccessDenied,
  isDetailRecordNotFound,
  shouldSkipDetailModelFieldMeta,
  isSuccessfulDetailResult,
  fetchDetailRecord,
  unwrapDetailRecord,
  enrichDetailRecordDisplayFields,
  loadDetailRecordRefreshSnapshot,
  assertStrictDetailRefreshParent,
} from './detailRecordRequests';
import type { useDetailPageState } from './useDetailPageState';

type UseDetailRecordArgs = Pick<
  ReturnType<typeof useDetailPageState>,
  | 'routeRecordPid'
  | 'hasApiSingletonRecordSource'
  | 'recordModelCode'
  | 'setRecordLoading'
  | 'setRecordError'
  | 'schema'
  | 'token'
  | 'setModelFieldMap'
  | 'modelFieldMap'
  | 'location'
  | 'setRawData'
  | 'setRecordData'
  | 'setRecordAccessDenied'
  | 'setRecordNotFound'
  | 'tableName'
  | 'rawData'
>;

export function useDetailRecord({
  routeRecordPid,
  hasApiSingletonRecordSource,
  recordModelCode,
  setRecordLoading,
  setRecordError,
  schema,
  token,
  setModelFieldMap,
  modelFieldMap,
  location,
  setRawData,
  setRecordData,
  setRecordAccessDenied,
  setRecordNotFound,
  tableName,
  rawData,
}: UseDetailRecordArgs) {
  useEffect(() => {
    if ((!routeRecordPid && !hasApiSingletonRecordSource) || !recordModelCode) {
      setRecordLoading(false);
      setRecordError(null);
      return;
    }
    const currentRecordPid = routeRecordPid;
    const currentModelCode = recordModelCode;
    let cancelled = false;

    async function loadModelFields(): Promise<Map<string, any> | undefined> {
      // Use /api/dynamic/{pageKey}/field-meta which requires model-level read permission
      // instead of /api/meta/models/{pid}/fields which requires management permission
      const pageKey = currentModelCode;
      if (!pageKey || shouldSkipDetailModelFieldMeta(schema)) return;
      const fieldsRes = await fetchResult<any[]>(`/api/dynamic/${pageKey}/field-meta`, {
        method: 'get',
        token: token || undefined,
      });
      if (cancelled) return;
      if (!ResultHelper.isSuccess(fieldsRes) || !fieldsRes.data) return;
      const map = new Map<string, any>();
      for (const f of fieldsRes.data) {
        map.set(f.code, f);
      }
      setModelFieldMap(map);
      return map;
    }
    const modelFields = loadModelFields().catch(() => undefined);

    async function loadRecord(): Promise<void> {
      try {
        const result = await fetchDetailRecord(
          schema,
          currentModelCode,
          currentRecordPid,
          token || undefined,
          location.search,
        );
        if (cancelled) return;
        if (isSuccessfulDetailResult(result) && result.data) {
          const recordPath = (schema as any)?.extension?.dataSource?.recordPath;
          const unwrappedRecord = unwrapDetailRecord(result.data, recordPath);
          setRawData(result.data);
          if (currentRecordPid && !shouldSkipDetailModelFieldMeta(schema)) {
            const enriched = await enrichDetailRecordDisplayFields(
              currentModelCode,
              currentRecordPid,
              unwrappedRecord,
              token || undefined,
              await modelFields,
              schema,
            );
            if (cancelled) return;
            setRecordData(enriched);
          } else {
            setRecordData(unwrappedRecord);
          }
          setRecordError(null);
          setRecordAccessDenied(false);
          setRecordNotFound(false);
        } else {
          setRawData(null);
          setRecordData({});
          setRecordAccessDenied(isDetailRecordAccessDenied(result));
          setRecordNotFound(isDetailRecordNotFound(result));
          setRecordError(
            (result as any)?.desc ||
              (result as any)?.message ||
              'The requested record was not found.',
          );
        }
      } catch (error) {
        if (cancelled) return;
        setRawData(null);
        setRecordData({});
        setRecordAccessDenied(false);
        setRecordNotFound(false);
        setRecordError(
          error instanceof Error ? error.message : 'The requested record could not be loaded.',
        );
      }
    }

    (async () => {
      try {
        // Fetch model fields and record data in parallel
        await Promise.all([modelFields, loadRecord().catch(() => {})]);
      } finally {
        if (!cancelled) setRecordLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [
    routeRecordPid,
    tableName,
    recordModelCode,
    schema,
    schema?.modelCode,
    token,
    hasApiSingletonRecordSource,
    location.search,
    setModelFieldMap,
    setRawData,
    setRecordAccessDenied,
    setRecordData,
    setRecordError,
    setRecordLoading,
    setRecordNotFound,
  ]);

  // Flow refresh must reject incomplete reads before exiting edit mode or clearing drafts.
  const reloadRecordStrict = useCallback(async (): Promise<void> => {
    if ((!routeRecordPid && !hasApiSingletonRecordSource) || !recordModelCode)
      throw new Error('Detail record refresh is unavailable');
    const currentRecordPid = routeRecordPid;
    const currentModelCode = recordModelCode;
    const recordPath = (schema as any)?.extension?.dataSource?.recordPath;
    const shouldEnrich = currentRecordPid && !shouldSkipDetailModelFieldMeta(schema);
    const previousParent = unwrapDetailRecord(rawData, recordPath);
    const expectedPid =
      (schema as any)?.extension?.dataSource?.adaptor === 'commandRecord' && currentRecordPid
        ? currentRecordPid
        : typeof previousParent.pid === 'string'
          ? currentRecordPid || previousParent.pid
          : undefined;
    const snapshot = await loadDetailRecordRefreshSnapshot(
      async () => {
        const result = await fetchDetailRecord(
          schema,
          currentModelCode,
          currentRecordPid,
          token || undefined,
          location.search,
        );
        if (isSuccessfulDetailResult(result))
          assertStrictDetailRefreshParent(result.data, recordPath, previousParent, expectedPid);
        return result;
      },
      recordPath,
      currentModelCode,
      shouldEnrich ? currentRecordPid : undefined,
      token || undefined,
      (model, pid, record, auth) =>
        enrichDetailRecordDisplayFields(model, pid, record, auth, modelFieldMap, schema),
    );
    if (!snapshot) throw new Error('Detail record refresh failed');
    setRawData(snapshot.rawData);
    setRecordData(snapshot.recordData);
  }, [
    routeRecordPid,
    recordModelCode,
    token,
    schema,
    hasApiSingletonRecordSource,
    location.search,
    rawData,
    modelFieldMap,
    setRawData,
    setRecordData,
  ]);

  // Existing sub-table callbacks retain their best-effort display refresh contract.
  const reloadRecord = useCallback(async (): Promise<void> => {
    try {
      await reloadRecordStrict();
    } catch {
      /* Preserve the current snapshot; a later explicit refresh can recover. */
    }
  }, [reloadRecordStrict]);
  return { reloadRecordStrict, reloadRecord };
}
