import { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

type Localized = string | Record<string, string>;
type Choice = {
  value: string;
  label: Localized;
  selection: Record<string, unknown>;
  disabled?: boolean;
};
export interface ReferenceConfig {
  source: string;
  selectedSource: string;
  queryParam?: string;
  nextSource?: string;
  selectionFields?: string[];
  multiple?: boolean;
  scope: Record<string, string>;
}
function safeSource(source: string) {
  if (!source.startsWith('/api/') || /[\\#\r\n]/.test(source))
    throw new Error('Invalid reference source');
  return source;
}
export function validReferenceConfig(config?: ReferenceConfig): asserts config is ReferenceConfig {
  if (
    !config ||
    !config.scope ||
    !Object.keys(config.scope).length ||
    Object.values(config.scope).some((value) => typeof value !== 'string' || !value) ||
    (config.queryParam && !/^[a-zA-Z][a-zA-Z0-9]*$/.test(config.queryParam)) ||
    (config.multiple && config.selectionFields?.length) ||
    (config.selectionFields &&
      (!config.selectionFields.length ||
        new Set(config.selectionFields).size !== config.selectionFields.length))
  )
    throw new Error('Invalid reference configuration');
  safeSource(config.source);
  safeSource(config.selectedSource);
  if (config.nextSource) {
    safeSource(config.nextSource);
    if (!config.nextSource.includes('{value}')) throw new Error('Missing reference parent');
  }
}
function read(record: Record<string, unknown>, path: string): unknown {
  return path
    .split('.')
    .reduce<unknown>(
      (value, part) =>
        value && typeof value === 'object' ? (value as Record<string, unknown>)[part] : undefined,
      record,
    );
}
function equal(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object') return false;
  const a = Object.keys(left),
    b = Object.keys(right);
  return (
    a.length === b.length &&
    a.every((key) => b.includes(key) && equal((left as any)[key], (right as any)[key]))
  );
}
/** Paged native references stay outside the submitted record; only declared selection paths are assigned. */
export default function RecordReferencePicker({
  id,
  label,
  value,
  field,
  config,
  fetchResult,
  onChange,
}: {
  id: string;
  label: string;
  value: Record<string, unknown>;
  field: string;
  config: ReferenceConfig;
  fetchResult?: (url: string, options: any) => Promise<any>;
  onChange: (changes: Record<string, unknown>) => void;
}) {
  validReferenceConfig(config);
  const { locale, t } = useI18n();
  const zh = locale.startsWith('zh');
  const [open, setOpen] = useState(false),
    [parent, setParent] = useState<Choice | null>(null);
  const [draft, setDraft] = useState(''),
    [query, setQuery] = useState(''),
    [page, setPage] = useState(1);
  const [records, setRecords] = useState<Choice[]>([]),
    [selected, setSelected] = useState<Choice[]>([]);
  const [total, setTotal] = useState(0),
    [loading, setLoading] = useState(false),
    [error, setError] = useState(false);
  const [resolveError, setResolveError] = useState(false),
    [retry, setRetry] = useState(0);
  const scopeKey = JSON.stringify(config.scope);
  const selectionFields = config.selectionFields;
  const matches = (choice: Choice) =>
    selectionFields?.length
      ? selectionFields.every((path) => equal(read(value, path), read(choice.selection, path)))
      : config.multiple
        ? Array.isArray(read(value, field)) &&
          (read(value, field) as unknown[]).includes(choice.value)
        : read(value, field) === choice.value;
  async function request(
    url: string,
    paged: boolean,
  ): Promise<{ records: Choice[]; total: number }> {
    if (!fetchResult) throw new Error('Reference transport unavailable');
    const result = await fetchResult(safeSource(url), { method: 'get' });
    if (result?.code !== undefined && String(result.code) !== '0')
      throw new Error('Reference request rejected');
    const data = result?.data ?? result;
    if (
      !data ||
      Object.entries(config.scope).some(([key, expected]) => data[key] !== expected) ||
      !Array.isArray(data.records) ||
      !Number.isSafeInteger(data.total) ||
      data.total < data.records.length ||
      data.records.some(
        (choice: Choice) =>
          !choice ||
          typeof choice.value !== 'string' ||
          !choice.value ||
          !choice.label ||
          !choice.selection ||
          typeof choice.selection !== 'object' ||
          Array.isArray(choice.selection),
      ) ||
      new Set(data.records.map((choice: Choice) => choice.value)).size !== data.records.length ||
      (paged && (data.current !== page || data.pageSize !== 20))
    )
      throw new Error('Invalid reference response');
    return data;
  }
  useEffect(() => {
    let active = true;
    setSelected([]);
    setResolveError(false);
    request(config.selectedSource, false)
      .then((data) => {
        if (active) setSelected(data.records);
      })
      .catch(() => {
        if (active) setResolveError(true);
      });
    return () => {
      active = false;
    };
  }, [config.selectedSource, scopeKey, fetchResult, retry]);
  useEffect(() => {
    if (!open) return;
    let active = true;
    setLoading(true);
    setError(false);
    setRecords([]);
    setTotal(0);
    const source =
      parent && config.nextSource
        ? config.nextSource.replace('{value}', encodeURIComponent(parent.value))
        : config.source;
    const url = new URL(source, 'https://reference.invalid');
    url.searchParams.set('page', String(page));
    url.searchParams.set('pageSize', '20');
    if (query && config.queryParam && !parent) url.searchParams.set(config.queryParam, query);
    request(url.pathname + url.search, true)
      .then((data) => {
        if (active) {
          setRecords(data.records);
          setTotal(data.total);
        }
      })
      .catch(() => {
        if (active) setError(true);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [
    open,
    parent,
    page,
    query,
    config.source,
    config.nextSource,
    config.queryParam,
    scopeKey,
    fetchResult,
    retry,
  ]);
  const chosen = selected.filter(matches);
  const current = read(value, field);
  const hasValue = Array.isArray(current)
    ? current.length > 0
    : current !== null && current !== undefined && current !== '';
  const unresolved =
    hasValue &&
    (config.multiple ? chosen.length !== (current as unknown[]).length : chosen.length === 0);
  const text = (choice: Choice) => getLocalizedText(choice.label, locale, t);
  const buttonClass = 'rounded border px-2 py-1 text-sm disabled:opacity-40';
  function choose(choice: Choice) {
    if (choice.disabled) return;
    if (config.nextSource && !parent) {
      setParent(choice);
      setPage(1);
      return;
    }
    if (selectionFields?.length) {
      const changes = Object.fromEntries(
        selectionFields.map((path) => [path, read(choice.selection, path)]),
      );
      if (Object.values(changes).some((item) => item === undefined)) {
        setError(true);
        return;
      }
      onChange(changes);
    } else if (config.multiple) {
      const values = Array.isArray(current) ? current : [];
      onChange({
        [field]: values.includes(choice.value)
          ? values.filter((item) => item !== choice.value)
          : [...values, choice.value],
      });
    } else onChange({ [field]: choice.value });
    setSelected((old) => [...old.filter((item) => item.value !== choice.value), choice]);
    if (!config.multiple) setOpen(false);
  }
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        {chosen.map((choice) => (
          <span
            key={choice.value}
            className="rounded bg-gray-100 px-2 py-1 text-sm dark:bg-gray-700"
          >
            {text(choice)}
            {choice.disabled ? (zh ? '（不可用）' : ' (unavailable)') : ''}
          </span>
        ))}
        {unresolved && (
          <span className="text-sm text-amber-700">
            {zh
              ? '已有引用暂不可用或正在读取；原值保留，请选择以修复。'
              : 'Existing reference unavailable or loading. Its value is preserved; select to repair.'}
          </span>
        )}
        <button
          id={id}
          data-record-reference-trigger
          type="button"
          className={buttonClass}
          onClick={() => {
            setOpen(!open);
            setParent(null);
            setPage(1);
            setQuery('');
            setDraft('');
          }}
        >
          {open ? (zh ? '收起选择' : 'Hide choices') : zh ? `选择${label}` : `Choose ${label}`}
        </button>
        {hasValue && (
          <button
            type="button"
            className={buttonClass}
            onClick={() =>
              onChange(
                selectionFields?.length
                  ? Object.fromEntries(selectionFields.map((path) => [path, null]))
                  : { [field]: config.multiple ? [] : null },
              )
            }
          >
            {zh ? '清除选择' : 'Clear selection'}
          </button>
        )}
      </div>
      {resolveError && (
        <p role="alert" className="text-sm text-red-600">
          {zh
            ? '无法读取已选名称，请检查权限或重试。'
            : 'Unable to read selected names. Check access or retry.'}
          <button type="button" className={buttonClass} onClick={() => setRetry(retry + 1)}>
            {zh ? '重试' : 'Retry'}
          </button>
        </p>
      )}
      {open && (
        <div className="space-y-2 rounded border p-2">
          {parent && (
            <div>
              {text(parent)}{' '}
              <button
                type="button"
                className={buttonClass}
                onClick={() => {
                  setParent(null);
                  setPage(1);
                }}
              >
                {zh ? '重新选择名称' : 'Choose another name'}
              </button>
            </div>
          )}
          {!parent && config.queryParam && (
            <div className="flex gap-2">
              <input
                aria-label={zh ? `搜索${label}` : `Search ${label}`}
                className="min-w-0 flex-1 rounded border px-2 py-1 dark:bg-gray-700"
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
              />
              <button
                type="button"
                className={buttonClass}
                onClick={() => {
                  setQuery(draft.trim());
                  setPage(1);
                }}
              >
                {zh ? '搜索' : 'Search'}
              </button>
            </div>
          )}
          {loading ? (
            <p role="status">{zh ? '正在读取候选…' : 'Loading choices…'}</p>
          ) : error ? (
            <p role="alert">
              {zh
                ? '候选读取失败，请检查权限、草稿版本或连接。'
                : 'Unable to load choices. Check access, draft revision or connection.'}
              <button type="button" className={buttonClass} onClick={() => setRetry(retry + 1)}>
                {zh ? '重试' : 'Retry'}
              </button>
            </p>
          ) : (
            <>
              {!records.length && <p>{zh ? '没有可用选项' : 'No available choices'}</p>}
              <ul className="max-h-52 space-y-1 overflow-y-auto">
                {records.map((choice) => (
                  <li key={choice.value}>
                    <button
                      type="button"
                      disabled={choice.disabled}
                      aria-pressed={matches(choice)}
                      className={buttonClass}
                      onClick={() => choose(choice)}
                    >
                      {text(choice)}
                      {matches(choice) ? ' ✓' : ''}
                    </button>
                  </li>
                ))}
              </ul>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  disabled={page === 1}
                  className={buttonClass}
                  onClick={() => setPage(page - 1)}
                >
                  {zh ? '上一页' : 'Previous'}
                </button>
                <span>{zh ? `第 ${page} 页，共 ${total} 项` : `Page ${page}, ${total} items`}</span>
                <button
                  type="button"
                  disabled={page * 20 >= total}
                  className={buttonClass}
                  onClick={() => setPage(page + 1)}
                >
                  {zh ? '下一页' : 'Next'}
                </button>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
