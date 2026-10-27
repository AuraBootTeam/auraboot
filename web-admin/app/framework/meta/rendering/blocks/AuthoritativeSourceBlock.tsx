import React, { lazy, Suspense } from 'react';
import type { EChartsOption } from 'echarts';
import type { BlockConfig } from '../../schemas/types';
import type { AuthoritativeSourceConfig, SourceStateTone } from '../../schemas/source-state';
import type { SchemaRuntime } from '../../runtime/schema-runtime';
import { getLocalizedText } from '../../runtime/expression/i18n-renderer';
import { readSourceView, type SourceView } from './sourceStateModel';
import { useAuthoritativeSource, type SourceReadReason } from './useAuthoritativeSource';
import copy from './authoritative-source.i18n.json';

const Chart = lazy(() => import('~/framework/smart/components/charts/EChartsHost'));
const tones: Record<SourceStateTone, string> = {
  neutral: 'bg-subtle text-text-2',
  success: 'bg-status-green-bg text-status-green',
  warning: 'bg-status-amber-bg text-status-amber',
  danger: 'bg-status-red-bg text-status-red',
};

/** Preserve real gaps; the legacy chart adapter's Number(null) -> 0 is not used. */
export function buildAuthoritativeSeriesOption(
  view: SourceView,
  config: NonNullable<AuthoritativeSourceConfig['series']>,
  title: string,
): EChartsOption {
  return {
    animation: false,
    aria: { enabled: true },
    grid: { left: 48, right: 24, top: 20, bottom: 42 },
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    xAxis: { type: 'time', boundaryGap: [0, 0] },
    yAxis: {
      type: 'value',
      name: view.unit,
      ...(config.domain ? { min: config.domain[0], max: config.domain[1] } : {}),
    },
    series: [
      {
        name: title,
        type: config.kind,
        data: view.points,
        ...(config.kind === 'line' ? { connectNulls: false, smooth: false, showSymbol: true } : {}),
        ...(config.color
          ? { itemStyle: { color: config.color }, lineStyle: { color: config.color } }
          : {}),
      },
    ],
  };
}

export function AuthoritativeSourceBlock({
  block,
  runtime,
  kind,
}: {
  block: BlockConfig;
  runtime: SchemaRuntime;
  kind: 'metric' | 'chart';
}) {
  const context = runtime.getContext(),
    locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const label = (key: keyof typeof copy) => getLocalizedText(copy[key], locale, t);
  const title = block.title ? getLocalizedText(block.title, locale, t) : '';
  const source = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  const read = useAuthoritativeSource(
    runtime,
    source,
    block.props?.sourceState!,
    Number(block.refreshInterval ?? block.props?.refreshInterval ?? 0),
  );
  let view: SourceView | undefined,
    reason: SourceReadReason | undefined = read.reason;
  if (read.phase === 'ready') {
    try {
      if (!read.identity || (kind === 'chart') !== !!read.binding.series) throw new Error();
      view = readSourceView(read.data, read.binding, read.identity, locale, t);
    } catch {
      reason = 'invalid';
    }
  }
  const phase = reason ? 'error' : view?.empty ? 'empty' : read.phase;
  const feedback =
    phase === 'error'
      ? label(reason ?? 'unavailable')
      : phase === 'ready'
        ? undefined
        : label(phase);
  const canRetry =
    source &&
    read.identity &&
    !['pending', 'unconfigured'].includes(phase) &&
    reason !== 'configuration';

  return (
    <section
      className={`source-state-block rounded-card border-border bg-panel shadow-card min-w-0 border p-4 ${block.className || ''}`}
      data-testid="authoritative-source"
      data-phase={phase}
      aria-busy={phase === 'loading'}
    >
      {title && <h3 className="text-text-2 text-sm font-semibold">{title}</h3>}
      {feedback && (
        <p role={phase === 'error' ? 'alert' : 'status'} className="text-text-2 my-5 text-sm">
          {feedback}
        </p>
      )}
      {view && (
        <>
          {kind === 'metric' && !view.empty && (
            <div className="mt-2 flex flex-wrap items-baseline gap-2">
              <strong
                className="text-text text-2xl font-semibold"
                data-testid="authoritative-value"
              >
                {view.value}
              </strong>
              {view.unit && <span className="text-text-2 text-sm">{view.unit}</span>}
            </div>
          )}
          {view.statuses.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-2">
              {view.statuses
                .filter((status) => !view.suppressed || status.value !== view.value)
                .map((status, index) => (
                  <span
                    key={index}
                    className={`rounded-control px-2 py-1 text-xs ${tones[status.tone]}`}
                    aria-label={`${status.label}: ${status.value}`}
                  >
                    {status.value}
                  </span>
                ))}
            </div>
          )}
          {view.metadata.length > 0 && (
            <dl className="mt-4 grid min-w-0 grid-cols-2 gap-x-4 gap-y-3">
              {view.metadata.map((field, index) => (
                <div key={index} className="min-w-0">
                  <dt className="text-text-3 text-xs">{field.label}</dt>
                  <dd className="text-text-2 mt-1 text-xs [overflow-wrap:anywhere] break-words">
                    {field.value}
                  </dd>
                </div>
              ))}
            </dl>
          )}
          {kind === 'chart' && view.suppressed && (
            <p role="status" className="text-text-2 my-4 text-sm">
              {view.value}
            </p>
          )}
          {kind === 'chart' && !view.empty && !view.suppressed && read.binding.series && (
            <>
              <Suspense fallback={<p role="status">{label('loading')}</p>}>
                <div role="img" aria-label={title}>
                  <Chart
                    option={buildAuthoritativeSeriesOption(view, read.binding.series, title)}
                    notMerge
                    style={{ height: read.binding.series.height ?? 300 }}
                  />
                </div>
              </Suspense>
              <p className="text-text-3 text-xs">
                {label('points')}: {view.points?.length ?? 0} · {label('gaps')}:{' '}
                {view.points?.filter((point) => point[1] === null).length ?? 0}
              </p>
            </>
          )}
        </>
      )}
      <footer className="border-border mt-4 flex flex-wrap items-center justify-between gap-3 border-t pt-3">
        {read.readAt && view ? (
          <span className="text-text-3 text-xs">
            {label('readAt')}: {new Date(read.readAt).toLocaleString(locale)}
          </span>
        ) : (
          <span />
        )}
        {canRetry && (
          <button
            type="button"
            onClick={read.retry}
            disabled={phase === 'loading'}
            className="border-border text-text-2 rounded-control hover:bg-hover border px-3 py-1.5 text-xs disabled:cursor-not-allowed disabled:opacity-50"
          >
            {label('retry')}
          </button>
        )}
      </footer>
    </section>
  );
}
