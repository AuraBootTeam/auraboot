import type { DslBlockV3 } from '../../types';
import React from 'react';
import { RuntimeLocaleContext, RuntimePermissionContext } from './runtimeContexts';
import { getBlockLabel, isRuntimeBlockPermissionAllowed } from './runtimePresentation';





export function RuntimeColumn({ block }: { block: DslBlockV3 }) {
  const hasPermission = React.useContext(RuntimePermissionContext);
  if (!isRuntimeBlockPermissionAllowed(block, hasPermission)) return null;

  return (
    <div
      className="border-r border-slate-200 px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-500 last:border-r-0"
      data-testid={`runtime-column-${block.id}`}
    >
      {getBlockLabel(block)}
    </div>
  );
}

export function RuntimeTitle({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  return (
    <div className="mb-3 text-sm font-semibold text-slate-900">{getBlockLabel(block, locale)}</div>
  );
}

export function RuntimePermissionNotice({
  permissionCode,
  testId,
}: {
  permissionCode: string;
  testId: string;
}) {
  return (
    <div
      className="text-xs font-medium text-amber-700"
      data-permission-allowed="false"
      data-permission-code={permissionCode}
      data-testid={testId}
    >
      Requires permission: {permissionCode}
    </div>
  );
}
