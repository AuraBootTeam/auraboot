import React, { useEffect, useState, useSyncExternalStore } from 'react';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { useI18n } from '~/contexts/I18nContext';

export interface TimeWindowFilterConfig {
  stateKey: string;
  defaultDays?: number;
  maxDays?: number;
}
interface WindowValue {
  from: string;
  to: string;
}
const DAY = 86400000;

function recentWindow(days: number): WindowValue {
  const end = Math.floor(Date.now() / 60000) * 60000;
  return { from: new Date(end - days * DAY).toISOString(), to: new Date(end).toISOString() };
}
function inputValue(iso: string): string {
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** A reusable, atomic query-window control. Draft edits never change query parameters. */
export function TimeWindowFilter({
  config,
  runtime,
}: {
  config: TimeWindowFilterConfig;
  runtime: SchemaRuntime;
}) {
  const { locale } = useI18n();
  const l = (zh: string, en: string) => (locale === 'zh-CN' ? zh : en);
  const days = config.defaultDays ?? 30;
  const maxDays = config.maxDays ?? 366;
  if (
    !config.stateKey ||
    !Number.isInteger(days) ||
    days <= 0 ||
    !Number.isInteger(maxDays) ||
    maxDays < days
  ) {
    throw new Error('Time window requires a state key and positive defaultDays <= maxDays');
  }
  const manager = runtime.getStateManager();
  const store = manager.getStore(runtime.getScopeId());
  const applied = useSyncExternalStore(
    store.subscribe,
    () => store.getState().state?.[config.stateKey] as WindowValue | undefined,
    () => undefined,
  );
  const [initial] = useState(() => applied ?? recentWindow(days));
  const [from, setFrom] = useState(() => inputValue(initial.from));
  const [to, setTo] = useState(() => inputValue(initial.to));
  const [error, setError] = useState('');
  const commit = (value: WindowValue) =>
    manager.updateState(runtime.getScopeId(), config.stateKey, value);
  useEffect(() => {
    if (!store.getState().state?.[config.stateKey])
      manager.updateState(runtime.getScopeId(), config.stateKey, initial);
  }, [manager, runtime, store, config.stateKey, initial]);
  const apply = () => {
    const start = new Date(from).getTime();
    const end = new Date(to).getTime();
    if (!from || !to || !Number.isFinite(start) || !Number.isFinite(end)) {
      setError(l('请选择有效的开始和结束时间。', 'Choose valid start and end times.'));
      return;
    }
    if (start >= end || end - start > maxDays * DAY) {
      setError(
        l(
          `开始时间须早于结束时间，范围最多 ${maxDays} 天。`,
          `Start must precede end; the range cannot exceed ${maxDays} days.`,
        ),
      );
      return;
    }
    setError('');
    commit({ from: new Date(start).toISOString(), to: new Date(end).toISOString() });
  };
  const reset = () => {
    const next = recentWindow(days);
    setFrom(inputValue(next.from));
    setTo(inputValue(next.to));
    setError('');
    commit(next);
  };
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const format = (value: string) =>
    new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(value),
    );
  return (
    <section
      className="rounded-control border-border bg-subtle border p-4"
      data-testid="query-time-window"
    >
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          {l('开始时间（含）', 'Start time (inclusive)')}
          <input
            aria-label={l('开始时间（含）', 'Start time (inclusive)')}
            type="datetime-local"
            value={from}
            onChange={(event) => setFrom(event.target.value)}
            className="rounded-control border-border bg-panel border px-3 py-2"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          {l('结束时间（不含）', 'End time (exclusive)')}
          <input
            aria-label={l('结束时间（不含）', 'End time (exclusive)')}
            type="datetime-local"
            value={to}
            onChange={(event) => setTo(event.target.value)}
            className="rounded-control border-border bg-panel border px-3 py-2"
          />
        </label>
        <button
          type="button"
          onClick={apply}
          className="rounded-control bg-accent px-4 py-2 text-sm text-white"
        >
          {l('应用时间范围', 'Apply time range')}
        </button>
        <button
          type="button"
          onClick={reset}
          className="rounded-control border-border bg-panel border px-4 py-2 text-sm"
        >
          {l(`最近 ${days} 天`, `Last ${days} days`)}
        </button>
      </div>
      <p className="text-text-3 mt-2 text-sm">
        {l('输入按本地时区解释：', 'Times use your local timezone: ')}
        {timezone}
      </p>
      {applied && (
        <p className="text-text-2 mt-1 text-sm" data-testid="applied-time-window">
          {l('已应用：', 'Applied: ')}
          {format(applied.from)} — {format(applied.to)}
        </p>
      )}
      {error && (
        <p role="alert" className="text-status-red mt-2 text-sm">
          {error}{' '}
          {l('仍显示上一次已应用范围的结果。', 'Results still use the previously applied range.')}
        </p>
      )}
    </section>
  );
}
