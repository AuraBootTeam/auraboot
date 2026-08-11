import React, { useEffect, useMemo, useState } from 'react';
import { GitBranch, Loader2 } from 'lucide-react';
import { loadAuthoringChangeItems, splitAuthoringChangeSet } from './authoringService';
import type { AuthoringChangeItem, AuthoringSession, AuthoringSplitResult } from './types';

export function AuthoringChangeSetSplitPanel({
  session,
  enabled,
  onSplit,
}: {
  session: AuthoringSession;
  enabled: boolean;
  onSplit: (result: AuthoringSplitResult) => Promise<void> | void;
}) {
  const [items, setItems] = useState<AuthoringChangeItem[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [title, setTitle] = useState('');
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<AuthoringSplitResult | null>(null);
  const eligible =
    enabled &&
    ['DRAFT', 'REJECTED'].includes(session.changeSetStatus) &&
    session.state === 'ACTIVE';

  useEffect(() => {
    setResult(null);
    setSelected(new Set());
    setTitle('拆分自当前 ChangeSet');
    setReason('');
  }, [session.changeSetPid, session.sessionPid]);

  useEffect(() => {
    if (!eligible) {
      setItems([]);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    void loadAuthoringChangeItems(session.sessionPid)
      .then((loaded) => {
        if (!cancelled) {
          setItems(loaded);
          setSelected(
            (current) =>
              new Set(
                [...current].filter((pid) => loaded.some((item) => item.changeItemPid === pid)),
              ),
          );
        }
      })
      .catch((loadError) => {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : '无法加载 ChangeSet 变更项');
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [eligible, session.revision, session.sessionPid]);

  const selectedItems = useMemo(
    () => items.filter((item) => selected.has(item.changeItemPid)),
    [items, selected],
  );
  const canSubmit =
    !pending &&
    selectedItems.length > 0 &&
    selectedItems.length < items.length &&
    title.trim().length > 0 &&
    reason.trim().length > 0;

  if (!eligible && !result) return null;
  if (!loading && items.length < 2 && !result && !error) return null;

  const toggle = (pid: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(pid)) next.delete(pid);
      else next.add(pid);
      return next;
    });
  };

  const submit = async () => {
    if (!canSubmit) return;
    setPending(true);
    setError(null);
    try {
      const split = await splitAuthoringChangeSet(
        session.sessionPid,
        session.revision,
        selectedItems.map((item) => item.changeItemPid),
        title.trim(),
        reason.trim(),
      );
      setResult(split);
      setItems(split.sourceItems);
      setSelected(new Set());
      await onSplit(split);
    } catch (splitError) {
      setError(
        splitError instanceof Error
          ? splitError.message
          : '拆分失败；跨分组存在依赖时必须调整选择后重试',
      );
    } finally {
      setPending(false);
    }
  };

  return (
    <details
      className="mt-2 rounded-md border border-violet-200 bg-violet-50 text-sm text-violet-950"
      data-testid="authoring-split-panel"
    >
      <summary className="flex min-h-10 cursor-pointer list-none items-center gap-2 px-3 py-2 font-semibold">
        <GitBranch className="h-4 w-4" />
        拆分 ChangeSet{items.length > 0 ? ` · ${items.length} 项` : ''}
      </summary>
      <div className="border-t border-violet-200 px-3 py-3">
        <p className="text-xs leading-5 text-violet-900">
          仅把依赖独立的变更拆到新
          ChangeSet。两个分组都从同一基线重放；作者、diff、依赖和审计会保留。
        </p>

        {loading ? (
          <div className="mt-3 flex items-center gap-2 text-xs">
            <Loader2 className="h-4 w-4 animate-spin" /> 正在分析变更依赖…
          </div>
        ) : (
          <div className="mt-3 grid gap-2" role="group" aria-label="选择拆出的变更项">
            {items.map((item) => (
              <label
                key={item.changeItemPid}
                className="flex cursor-pointer items-start gap-2 rounded-md border border-violet-200 bg-white px-3 py-2"
              >
                <input
                  type="checkbox"
                  checked={selected.has(item.changeItemPid)}
                  onChange={() => toggle(item.changeItemPid)}
                  disabled={pending}
                  className="mt-1"
                  data-testid={`authoring-split-item-${item.changeItemPid}`}
                />
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-2 font-medium text-slate-800">
                    <span>页面变更</span>
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">
                      {item.operation}
                    </span>
                    <span className={riskBadge(item.riskLevel)}>{item.riskLevel}</span>
                  </span>
                  <span className="mt-0.5 block text-xs text-slate-600">
                    {changeItemLocationLabel(item.propertyPath)}
                  </span>
                </span>
              </label>
            ))}
          </div>
        )}

        {!loading && items.length >= 2 ? (
          <div className="mt-3 rounded-md bg-white px-3 py-2 text-xs text-slate-700">
            原 ChangeSet 保留 {items.length - selectedItems.length} 项；新 ChangeSet 拆出{' '}
            {selectedItems.length} 项。两边都必须至少保留 1 项。
          </div>
        ) : null}

        <div className="mt-3 grid gap-3 lg:grid-cols-2">
          <label className="text-xs font-medium text-slate-700">
            新 ChangeSet 标题
            <input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              maxLength={200}
              disabled={pending}
              className="mt-1 block min-h-9 w-full rounded-md border border-slate-300 bg-white px-2 text-sm"
              data-testid="authoring-split-title"
            />
          </label>
          <label className="text-xs font-medium text-slate-700">
            拆分原因（进入审计）
            <textarea
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              rows={2}
              maxLength={1000}
              disabled={pending}
              placeholder="说明为什么两个分组可以独立评审和发布"
              className="mt-1 block min-h-16 w-full resize-y rounded-md border border-slate-300 bg-white px-2 py-2 text-sm"
              data-testid="authoring-split-reason"
            />
          </label>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-3">
          <button
            type="button"
            disabled={!canSubmit}
            onClick={() => void submit()}
            className="inline-flex min-h-9 items-center gap-2 rounded-md bg-violet-700 px-3 font-semibold text-white hover:bg-violet-800 disabled:cursor-not-allowed disabled:bg-slate-300"
            data-testid="authoring-split-submit"
          >
            {pending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <GitBranch className="h-4 w-4" />
            )}
            {pending ? '正在校验并拆分…' : '校验依赖并拆分'}
          </button>
          <span className="text-xs text-slate-600">跨分组路径或结构依赖由服务端 fail-closed。</span>
        </div>

        {result ? (
          <div
            className="mt-3 rounded-md border border-emerald-300 bg-emerald-50 px-3 py-2 text-sm text-emerald-950"
            role="status"
            data-testid="authoring-split-success"
          >
            已创建新的 ChangeSet，原 ChangeSet 已重建为剩余变更。
            <a
              className="ml-2 font-semibold text-emerald-800 underline"
              href={splitTargetHref(result.targetSession.sessionPid)}
              data-testid="authoring-split-target-link"
            >
              打开拆出的 ChangeSet
            </a>
          </div>
        ) : null}
        {error ? (
          <div
            className="mt-3 rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-800"
            role="alert"
          >
            {error}
          </div>
        ) : null}
      </div>
    </details>
  );
}

function riskBadge(risk: string): string {
  return risk === 'L3'
    ? 'rounded bg-red-100 px-1.5 py-0.5 text-xs font-semibold text-red-800'
    : 'rounded bg-blue-100 px-1.5 py-0.5 text-xs font-semibold text-blue-800';
}

function changeItemLocationLabel(propertyPath?: string | null): string {
  if (!propertyPath) return '结构变更';
  if (propertyPath.includes('title') || propertyPath.includes('label')) return '标题与文案';
  if (propertyPath.includes('field')) return '字段配置';
  if (propertyPath.includes('$structure')) return '页面结构';
  return '展示属性';
}

function splitTargetHref(sessionPid: string): string {
  if (typeof window === 'undefined') {
    return `/unified-designer?authoringSession=${encodeURIComponent(sessionPid)}`;
  }
  const url = new URL(window.location.href);
  [
    'contextId',
    'authoringSession',
    'reviewSession',
    'changeSetId',
    'reviewChangeSetId',
    'conflictContext',
  ].forEach((parameter) => url.searchParams.delete(parameter));
  url.searchParams.set('authoringSession', sessionPid);
  return `${url.pathname}${url.search}${url.hash}`;
}
