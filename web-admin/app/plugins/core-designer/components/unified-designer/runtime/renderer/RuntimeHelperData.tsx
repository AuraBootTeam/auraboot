import type { DslBlockV3 } from '../../types';
import {
  normalizeRuntimeExecutionError,
  type RuntimeExecutionIssue,
  type RuntimeExecutionServices,
  type RuntimeHelperBlockData,
} from '../runtimeExecution';
import { DESIGNER_I18N } from '~/shared/designer';
import React from 'react';
import {
  RuntimePermissionContext,
  useRuntimeText,
  type RuntimePageContext,
} from './runtimeContexts';
import {
  getRuntimeBlockContext,
  getRuntimePermissionCode,
  hasExecutableHelperDataSource,
} from './runtimePresentation';





export function useRuntimeHelperBlockData(
  block: DslBlockV3,
  runtimeServices: RuntimeExecutionServices | undefined,
  pageContext: RuntimePageContext,
  blockPath: string[],
) {
  const hasPermission = React.useContext(RuntimePermissionContext);
  const [runtimeData, setRuntimeData] = React.useState<RuntimeHelperBlockData | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [runtimeError, setRuntimeError] = React.useState<RuntimeExecutionIssue | null>(null);
  const dataSourceSignature = JSON.stringify(block.dataSource ?? {});
  const permissionCode = getRuntimePermissionCode(block);
  const permissionAllowed = permissionCode ? hasPermission(permissionCode) : true;
  const shouldLoadData = Boolean(
    runtimeServices?.loadHelperBlockData && hasExecutableHelperDataSource(block) && permissionAllowed,
  );

  React.useEffect(() => {
    if (!shouldLoadData || !runtimeServices?.loadHelperBlockData) {
      setRuntimeData(null);
      setRuntimeError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setRuntimeError(null);
    void runtimeServices
      .loadHelperBlockData(block, getRuntimeBlockContext(block, pageContext, blockPath))
      .then((data) => {
        if (!cancelled) setRuntimeData(data);
      })
      .catch((loadError: unknown) => {
        if (!cancelled) {
          setRuntimeData(null);
          setRuntimeError(normalizeRuntimeExecutionError(loadError));
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [block, dataSourceSignature, runtimeServices, shouldLoadData]);

  return { runtimeData, loading, runtimeError, permissionCode, permissionAllowed };
}

export function RuntimeHelperDataStatus({
  blockId,
  data,
  loading,
  error,
  permissionCode,
  permissionAllowed,
}: {
  blockId: string;
  data: RuntimeHelperBlockData | null;
  loading: boolean;
  error: RuntimeExecutionIssue | null;
  permissionCode: string;
  permissionAllowed: boolean;
}) {
  const t = useRuntimeText();
  const permissionStatus = permissionCode ? (
    <div
      className={`mt-1 text-xs ${
        permissionAllowed ? 'text-slate-500' : 'font-medium text-amber-700'
      }`}
      data-testid={`runtime-helper-permission-${blockId}`}
      data-permission-code={permissionCode}
      data-permission-allowed={String(permissionAllowed)}
    >
      {permissionAllowed ? `Permission: ${permissionCode}` : `Requires permission: ${permissionCode}`}
    </div>
  ) : null;

  let dataStatus: React.ReactNode = null;
  if (loading) {
    dataStatus = (
      <div
        className="mt-1 text-xs text-slate-400"
        data-testid={`runtime-helper-loading-${blockId}`}
      >
        {t(DESIGNER_I18N.unified.runtime.loadingLiveData)}
      </div>
    );
  } else if (error) {
    dataStatus = (
      <div className="mt-1 text-xs text-red-600" data-testid={`runtime-helper-error-${blockId}`}>
        {error.message}
      </div>
    );
  } else if (data?.source) {
    dataStatus = (
      <div
        className="mt-1 text-xs text-slate-400"
        data-testid={`runtime-helper-source-${blockId}`}
      >
        {data.source}
      </div>
    );
  }

  if (!permissionStatus && !dataStatus) return null;

  return (
    <>
      {permissionStatus}
      {dataStatus}
    </>
  );
}
