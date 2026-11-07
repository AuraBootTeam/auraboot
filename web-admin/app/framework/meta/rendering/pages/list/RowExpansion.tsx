import { useEffect, useState } from 'react';
import { useAuth } from '~/contexts/AuthContext';
import type { RowExpansionConfig } from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import TEXT from './RowExpansion.i18n.json';

interface Props {
  config: RowExpansionConfig;
  record: Record<string, unknown>;
  locale: string;
  t: (key: string) => string;
}
interface Page {
  records: Record<string, unknown>[];
  total: number;
}
const fieldPattern = /^[A-Za-z_][A-Za-z0-9_]*$/;
const scalar = (value: unknown) =>
  typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value));

/** Only explicit row fields become escaped path values; metadata cannot open another origin. */
export function rowExpansionEndpoint(
  config: RowExpansionConfig,
  record: Record<string, unknown>,
): string {
  const endpoint = config.endpoint.replace(
    /\$\{row\.([A-Za-z_][A-Za-z0-9_]*)\}/g,
    (_match, field: string) => {
      const value = Object.prototype.hasOwnProperty.call(record, field) ? record[field] : undefined;
      if (!scalar(value) || String(value).trim() === '' || ['.', '..'].includes(String(value)))
        throw new Error('Missing expansion reference');
      return encodeURIComponent(String(value));
    },
  );
  const address = new URL(endpoint, 'https://row-expansion.invalid');
  if (
    !endpoint.startsWith('/api/') ||
    endpoint.includes('\\') ||
    /\$\{|[{}]/.test(endpoint) ||
    address.origin !== 'https://row-expansion.invalid' ||
    !address.pathname.startsWith('/api/')
  ) {
    throw new Error('Invalid expansion endpoint');
  }
  return endpoint;
}

export function rowExpansionLabel(
  config: RowExpansionConfig,
  record: Record<string, unknown>,
  expanded: boolean,
  locale: string,
  t: (key: string) => string,
): string {
  const label = getLocalizedText(
    expanded ? TEXT.collapse : (config.label ?? TEXT.expand),
    locale,
    t,
  );
  const title = config.titleField ? record[config.titleField] : undefined;
  return scalar(title) && String(title).trim() ? `${label}: ${String(title)}` : label;
}

/** Mounted only after an actual expansion. An identity/config change discards the entire page session. */
export function RowExpansion({ config, record, locale, t }: Props) {
  const { user } = useAuth();
  let endpoint = '';
  try {
    endpoint = rowExpansionEndpoint(config, record);
  } catch {
    /* Render a localized configuration failure; never issue a partial request. */
  }
  const identity = JSON.stringify([user?.tenantId, user?.pid, endpoint, config, locale]);
  return (
    <RowExpansionSession
      key={identity}
      config={config}
      record={record}
      locale={locale}
      t={t}
      identity={identity}
      endpoint={endpoint}
      authorized={Boolean(user?.tenantId && user?.pid)}
    />
  );
}

function RowExpansionSession({
  config,
  record,
  locale,
  t,
  endpoint,
  authorized,
  identity,
}: Props & {
  endpoint: string;
  authorized: boolean;
  identity: string;
}) {
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale, t);
  const [page, setPage] = useState(1),
    [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<Page>(),
    [loading, setLoading] = useState(true),
    [failed, setFailed] = useState(false);
  const size = config.pageSize ?? 10,
    pageParam = config.pageParam ?? 'page',
    sizeParam = config.sizeParam ?? 'size';
  useEffect(() => {
    let active = true;
    setLoading(true);
    setFailed(false);
    setResult(undefined);
    const valid =
      endpoint &&
      authorized &&
      Number.isInteger(size) &&
      size >= 1 &&
      size <= 100 &&
      fieldPattern.test(pageParam) &&
      fieldPattern.test(sizeParam) &&
      pageParam !== sizeParam &&
      [0, 1].includes(config.pageIndexBase ?? 0) &&
      Array.isArray(config.columns) &&
      config.columns.length > 0 &&
      config.columns.length <= 12 &&
      config.columns.every((column) => fieldPattern.test(column.field));
    if (!valid) {
      setLoading(false);
      setFailed(true);
      return () => {
        active = false;
      };
    }
    fetchResult<Page>(endpoint, {
      method: 'get',
      params: { [pageParam]: page - 1 + (config.pageIndexBase ?? 0), [sizeParam]: size },
    })
      .then((response) => {
        if (!active) return;
        const value = response.data,
          key = config.rowKey ?? 'pid';
        if (
          !ResultHelper.isSuccess(response) ||
          !value ||
          !Array.isArray(value.records) ||
          !Number.isSafeInteger(value.total) ||
          value.total < 0 ||
          value.records.length !== Math.min(size, Math.max(0, value.total - (page - 1) * size)) ||
          value.records.some(
            (row) =>
              !row ||
              typeof row !== 'object' ||
              Array.isArray(row) ||
              !scalar(row[key]) ||
              !String(row[key]),
          ) ||
          new Set(value.records.map((row) => String(row[key]))).size !== value.records.length
        )
          throw new Error('Invalid expansion page');
        setResult(value);
      })
      .catch(() => {
        if (active) setFailed(true);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [identity, page, attempt]);
  const label = rowExpansionLabel(config, record, false, locale, t);
  return (
    <div className="bg-subtle min-w-0 space-y-3 p-4" data-testid="row-expansion-detail">
      {loading && (
        <p role="status" className="text-text-3 text-sm">
          {text('loading')}
        </p>
      )}
      {failed && (
        <div role="alert" className="text-danger flex items-center gap-3 text-sm">
          {text('failed')}
          <button
            type="button"
            className="focus-visible:shadow-focus rounded border px-3 py-1"
            onClick={() => setAttempt((previous) => previous + 1)}
          >
            {text('retry')}
          </button>
        </div>
      )}
      {!loading && !failed && result && (
        <>
          {result.records.length === 0 ? (
            <p className="text-text-3 text-sm">{text('empty')}</p>
          ) : (
            <div className="min-w-0 overflow-x-auto">
              <table
                aria-label={label}
                className="text-text-2 w-full table-fixed text-left text-sm"
              >
                <thead className="text-text-3">
                  <tr>
                    {config.columns.map((column) => (
                      <th
                        key={column.field}
                        scope="col"
                        className="px-3 py-2 font-medium"
                        style={{ width: column.width }}
                      >
                        {getLocalizedText(column.label, locale, t)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-border bg-panel divide-y">
                  {result.records.map((row) => (
                    <tr key={String(row[config.rowKey ?? 'pid'])}>
                      {config.columns.map((column) => (
                        <td
                          key={column.field}
                          className={`px-3 py-3 align-top break-words ${column.multiline ? 'whitespace-pre-wrap' : ''}`}
                        >
                          {scalar(row[column.field]) ? String(row[column.field]) : '—'}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {result.total > size || page > 1 ? (
            <div className="text-text-3 flex items-center justify-end gap-3 text-sm">
              <button
                type="button"
                className="border-border rounded border px-3 py-1 disabled:opacity-40"
                disabled={page === 1}
                onClick={() => setPage((previous) => previous - 1)}
              >
                {text('previous')}
              </button>
              <span>
                {page} / {Math.max(1, Math.ceil(result.total / size))}
              </span>
              <button
                type="button"
                className="border-border rounded border px-3 py-1 disabled:opacity-40"
                disabled={page * size >= result.total}
                onClick={() => setPage((previous) => previous + 1)}
              >
                {text('next')}
              </button>
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
