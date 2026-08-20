import React from 'react';
import { Layers3, LockKeyhole } from 'lucide-react';
import type { AuthoringOwnership } from './types';

export function AuthoringOwnershipNotice({ ownership }: { ownership?: AuthoringOwnership }) {
  if (!ownership?.tenantOverride) return null;

  return (
    <section
      className="rounded-md border border-indigo-200 bg-indigo-50 px-3 py-2 text-sm text-indigo-950"
      role="note"
      aria-label="租户派生层所有权"
      data-testid="authoring-ownership-notice"
    >
      <div className="flex items-start gap-2">
        <Layers3 className="mt-0.5 h-4 w-4 shrink-0" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2 font-semibold">
            <span>正在编辑租户派生层</span>
            <span className="rounded bg-indigo-100 px-1.5 py-0.5 text-xs text-indigo-800">
              {ownership.sourceOwnershipScope} → TENANT
            </span>
          </div>
          <p className="mt-1 text-xs leading-5">
            当前 ChangeSet 与发布只作用于本租户和当前环境；共享来源页面保持不变。
          </p>
          <p className="mt-1 flex items-center gap-1 text-xs text-indigo-800">
            <LockKeyhole className="h-3.5 w-3.5 shrink-0" />
            共享源不可在此修改；恢复默认时应回到 {ownership.restoreTarget} 层。
          </p>
        </div>
      </div>
    </section>
  );
}
