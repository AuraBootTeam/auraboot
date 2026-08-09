import React, { useMemo, useState } from 'react';
import { CircleAlert, GitMerge, Loader2 } from 'lucide-react';
import type {
  StudioMergeResolution,
  StudioThreeWayMerge,
} from './persistence/contextualAuthoringAdapter';

export function AuthoringConflictResolutionPanel({
  merge,
  baseRevision,
  latestRevision,
  pending,
  error,
  onResolve,
  onUseLatest,
}: {
  merge: StudioThreeWayMerge;
  baseRevision: number;
  latestRevision: number;
  pending: boolean;
  error?: string | null;
  onResolve: (resolutions: Record<string, StudioMergeResolution>) => Promise<void> | void;
  onUseLatest: () => Promise<void> | void;
}) {
  const [resolutions, setResolutions] = useState<Record<string, StudioMergeResolution>>({});
  const unresolvedCount = useMemo(
    () => merge.conflicts.filter((conflict) => !resolutions[conflict.id]).length,
    [merge.conflicts, resolutions],
  );
  const mergeBlocked = merge.unsupported.length > 0;

  return (
    <section
      className="mx-4 mt-3 rounded-lg border border-amber-300 bg-amber-50 shadow-sm"
      data-testid="authoring-conflict-panel"
      aria-labelledby="authoring-conflict-title"
    >
      <div className="flex items-start gap-3 border-b border-amber-200 px-4 py-3 text-amber-950">
        <CircleAlert className="mt-0.5 h-5 w-5 shrink-0" />
        <div>
          <h2 id="authoring-conflict-title" className="font-semibold">
            检测到并发变更，已停止保存
          </h2>
          <p className="mt-1 text-xs leading-5">
            你的编辑基于 r{baseRevision}，服务器现为 r{latestRevision}。系统不会采用最后写入覆盖；
            请在应用设计中心查看 Base / Mine / Latest，逐项选择 Mine 或 Latest，再基于最新修订重新保存。
          </p>
          <p className="mt-1 text-xs">
            已安全重放 {merge.autoMergedChanges} 项不冲突变更；待人工裁决 {merge.conflicts.length} 项。
          </p>
        </div>
      </div>

      {merge.unsupported.length > 0 ? (
        <div className="border-b border-amber-200 px-4 py-3 text-sm text-red-800" role="alert">
          <div className="font-semibold">存在无法自动表达的结构差异</div>
          <ul className="mt-1 list-disc space-y-1 pl-5 text-xs">
            {merge.unsupported.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {merge.conflicts.length > 0 ? (
        <div className="max-h-[45vh] space-y-3 overflow-y-auto p-4">
          {merge.conflicts.map((conflict, index) => (
            <article
              key={conflict.id}
              className="rounded-md border border-amber-200 bg-white p-3"
              data-testid={`authoring-conflict-${index}`}
            >
              <div className="text-sm font-semibold text-slate-900">
                {conflict.kind === 'ORDER' ? '同级区块顺序' : conflict.propertyPath}
              </div>
              <div className="mt-0.5 text-xs text-slate-500">
                {conflict.blockType ? `${conflict.blockType} · ` : ''}{conflict.blockId}
              </div>
              <div className="mt-3 grid gap-2 lg:grid-cols-3">
                <ConflictValue label="Base" value={conflict.baseValue} tone="base" />
                <ConflictValue label="Mine" value={conflict.mineValue} tone="mine" />
                <ConflictValue label="Latest" value={conflict.latestValue} tone="latest" />
              </div>
              <fieldset className="mt-3 flex flex-wrap gap-4 text-sm">
                <legend className="sr-only">选择 {conflict.propertyPath} 的保留版本</legend>
                <ResolutionOption
                  conflictId={conflict.id}
                  value="MINE"
                  checked={resolutions[conflict.id] === 'MINE'}
                  label="保留 Mine"
                  onChange={() =>
                    setResolutions((current) => ({ ...current, [conflict.id]: 'MINE' }))
                  }
                />
                <ResolutionOption
                  conflictId={conflict.id}
                  value="LATEST"
                  checked={resolutions[conflict.id] === 'LATEST'}
                  label="保留 Latest"
                  onChange={() =>
                    setResolutions((current) => ({ ...current, [conflict.id]: 'LATEST' }))
                  }
                />
              </fieldset>
            </article>
          ))}
        </div>
      ) : (
        <div className="px-4 py-3 text-sm text-slate-700">
          Mine 与 Latest 修改了不同属性，可在确认后安全合并。
        </div>
      )}

      {error ? (
        <div className="mx-4 mb-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">
          {error}
        </div>
      ) : null}

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-amber-200 px-4 py-3">
        <span className="text-xs text-slate-600">
          {mergeBlocked
            ? '结构差异需先放弃 Mine 或进入后续专用结构流程。'
            : unresolvedCount > 0
              ? `还有 ${unresolvedCount} 项未裁决`
              : '所有冲突均已裁决'}
        </span>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => void onUseLatest()}
            disabled={pending}
            className="min-h-9 rounded-md border border-slate-300 bg-white px-3 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
            data-testid="authoring-conflict-use-latest"
          >
            {pending ? '处理中…' : '放弃 Mine，使用 Latest'}
          </button>
          <button
            type="button"
            onClick={() => void onResolve(resolutions)}
            disabled={pending || mergeBlocked || unresolvedCount > 0}
            className="inline-flex min-h-9 items-center gap-1.5 rounded-md bg-amber-700 px-3 text-sm font-semibold text-white hover:bg-amber-800 disabled:cursor-not-allowed disabled:bg-slate-300"
            data-testid="authoring-conflict-apply"
          >
            {pending ? <Loader2 className="h-4 w-4 animate-spin" /> : <GitMerge className="h-4 w-4" />}
            {pending ? '处理中…' : '应用裁决并重新保存'}
          </button>
        </div>
      </div>
    </section>
  );
}

function ConflictValue({
  label,
  value,
  tone,
}: {
  label: string;
  value: unknown;
  tone: 'base' | 'mine' | 'latest';
}) {
  const toneClass = {
    base: 'border-slate-200 bg-slate-50',
    mine: 'border-blue-200 bg-blue-50',
    latest: 'border-emerald-200 bg-emerald-50',
  }[tone];
  return (
    <div className={`min-w-0 rounded border p-2 ${toneClass}`}>
      <div className="text-[11px] font-semibold uppercase tracking-wide text-slate-600">{label}</div>
      <pre className="mt-1 max-h-28 overflow-auto whitespace-pre-wrap break-all text-xs text-slate-800">
        {formatConflictValue(value)}
      </pre>
    </div>
  );
}

function ResolutionOption({
  conflictId,
  value,
  checked,
  label,
  onChange,
}: {
  conflictId: string;
  value: StudioMergeResolution;
  checked: boolean;
  label: string;
  onChange: () => void;
}) {
  return (
    <label className="inline-flex min-h-9 cursor-pointer items-center gap-2 rounded-md border border-slate-200 px-3 hover:bg-slate-50">
      <input
        type="radio"
        name={`authoring-resolution-${conflictId}`}
        value={value}
        checked={checked}
        onChange={onChange}
      />
      {label}
    </label>
  );
}

function formatConflictValue(value: unknown): string {
  if (value === undefined) return '∅（不存在）';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}
