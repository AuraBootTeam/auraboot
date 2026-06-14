import React, { useEffect, useMemo, useRef } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { LocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  readDataSourceRecord,
  readDataSourceState,
  readPath,
  useDataSourceSubscription,
} from './workbenchBlockUtils';

export interface StatusBannerBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const toneClass: Record<string, string> = {
  blue: 'border-blue-200 bg-blue-50 text-blue-900',
  amber: 'border-amber-200 bg-amber-50 text-amber-900',
  red: 'border-rose-200 bg-rose-50 text-rose-900',
  green: 'border-emerald-200 bg-emerald-50 text-emerald-900',
  gray: 'border-gray-200 bg-gray-50 text-gray-900',
};

const dotClass: Record<string, string> = {
  blue: 'bg-blue-500',
  amber: 'bg-amber-500',
  red: 'bg-rose-500',
  green: 'bg-emerald-500',
  gray: 'bg-gray-500',
};

function readLocalizedMapValue(
  map: unknown,
  key: string,
  locale: string,
  t: (key: string) => string,
): string {
  if (!map || typeof map !== 'object') return '';
  const value = (map as Record<string, unknown>)[key];
  if (!value) return '';
  return typeof value === 'string' ? value : getLocalizedText(value as LocalizedText, locale, t);
}

function interpolateTemplate(template: string, record: Record<string, any>): string {
  return template.replace(/\$\{([^}]+)\}/g, (_match, rawPath) => {
    const path = String(rawPath || '').trim().replace(/^record\./, '');
    const value = readPath(record, path);
    return value === undefined || value === null || value === '' ? '-' : String(value);
  });
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)) : [];
}

export const StatusBannerBlockRenderer: React.FC<StatusBannerBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;

  useDataSourceSubscription(runtime, dataSourceId);
  const dataSourceState = readDataSourceState(runtime, dataSourceId);
  const record = readDataSourceRecord(runtime, dataSourceId);

  const statusField = String((block as any).statusField || 'status');
  const errorField = String((block as any).errorField || 'errorMessage');
  const status = String(readPath(record, statusField) || '').trim();
  const hideStatuses = useMemo(() => new Set(asStringArray((block as any).hideStatuses)), [block]);
  const failedStatuses = useMemo(() => new Set(asStringArray((block as any).failedStatuses)), [block]);
  const polling = (block as any).poll || {};
  const pollStatuses = useMemo(() => new Set(asStringArray(polling.enabledWhenStatuses)), [polling]);
  const refreshPageStatuses = useMemo(
    () => new Set(asStringArray(polling.refreshPageWhenStatuses)),
    [polling],
  );
  const reloadDataSources = asStringArray(polling.reload);
  const pollIntervalMs = Number(polling.intervalMs || 3000);
  const manager = runtime.getDataSourceManager?.();
  const shouldPoll = Boolean(status && pollStatuses.has(status) && reloadDataSources.length > 0);
  const previousStatusRef = useRef(status);

  useEffect(() => {
    if (!shouldPoll || !manager?.reload) return undefined;
    const intervalId = window.setInterval(() => {
      void manager.reload(reloadDataSources);
    }, Math.max(1000, pollIntervalMs));
    return () => window.clearInterval(intervalId);
  }, [manager, pollIntervalMs, reloadDataSources.join('|'), shouldPoll]);

  useEffect(() => {
    const previousStatus = previousStatusRef.current;
    previousStatusRef.current = status;

    if (
      previousStatus &&
      previousStatus !== status &&
      pollStatuses.has(previousStatus) &&
      refreshPageStatuses.has(status)
    ) {
      window.location.reload();
    }
  }, [pollStatuses, refreshPageStatuses, status]);

  if (status && hideStatuses.has(status)) {
    return null;
  }

  if (dataSourceState?.loading && !dataSourceState.data) {
    return (
      <div
        data-testid={`status-banner-${block.id || 'block'}-loading`}
        className="rounded-lg border border-gray-200 bg-white p-3 text-sm text-gray-500"
      >
        {t('common.loading') !== 'common.loading' ? t('common.loading') : 'Loading...'}
      </div>
    );
  }

  if (dataSourceState?.error) {
    const message =
      dataSourceState.error instanceof Error
        ? dataSourceState.error.message
        : String(dataSourceState.error);
    return (
      <div
        role="alert"
        data-testid={`status-banner-${block.id || 'block'}-error`}
        className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800"
      >
        {message}
      </div>
    );
  }

  if (!status) {
    return null;
  }

  const tone =
    readPath((block as any).toneMap, status) ||
    (failedStatuses.has(status) ? 'red' : shouldPoll ? 'blue' : 'amber');
  const title =
    readLocalizedMapValue((block as any).titleMap, status, locale, t) ||
    readLocalizedMapValue((block as any).titleMap, '__default', locale, t) ||
    status;
  const descriptionTemplate =
    readLocalizedMapValue((block as any).descriptionMap, status, locale, t) ||
    readLocalizedMapValue((block as any).descriptionMap, '__default', locale, t) ||
    '';
  const errorMessage = readPath(record, errorField);
  const description = interpolateTemplate(
    failedStatuses.has(status) && errorMessage ? String(errorMessage) : descriptionTemplate,
    record,
  );
  const summaryFields = Array.isArray((block as any).summaryFields)
    ? (block as any).summaryFields
    : [];

  return (
    <section
      data-testid={`status-banner-${block.id || 'block'}`}
      className={`rounded-lg border p-4 shadow-sm ${toneClass[String(tone)] || toneClass.gray}`}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <span
            className={`mt-1 h-2.5 w-2.5 shrink-0 rounded-full ${
              dotClass[String(tone)] || dotClass.gray
            } ${shouldPoll ? 'animate-pulse' : ''}`}
          />
          <div className="min-w-0">
            <div className="text-sm font-semibold">{title}</div>
            {description && <div className="mt-1 text-sm opacity-80">{description}</div>}
          </div>
        </div>
        {shouldPoll && (
          <span className="rounded-full bg-white/70 px-2.5 py-1 text-xs font-medium">
            {getLocalizedText(
              (block as any).autoRefreshLabel || {
                'zh-CN': '自动刷新中',
                en: 'Auto refreshing',
              },
              locale,
              t,
            )}
          </span>
        )}
      </div>
      {summaryFields.length > 0 && (
        <dl className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-5">
          {summaryFields.map((field: any) => {
            const key = String(field.key || field.field || field.label);
            const value = readPath(record, field.field);
            if (value === undefined || value === null || value === '') return null;
            const displayValue = String(value);
            return (
              <div key={key} className="min-w-0 rounded-md bg-white/60 px-3 py-2">
                <dt className="text-xs opacity-70">
                  {getLocalizedText(field.label as LocalizedText, locale, t)}
                </dt>
                <dd className="mt-0.5 min-w-0 break-words text-sm font-semibold leading-snug" title={displayValue}>
                  {displayValue}
                </dd>
              </div>
            );
          })}
        </dl>
      )}
    </section>
  );
};

export default StatusBannerBlockRenderer;
