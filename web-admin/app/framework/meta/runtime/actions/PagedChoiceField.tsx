import { useEffect, useRef, useState } from 'react';
import { useAuth } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getLocalizedText } from '../expression/i18n-renderer';
import TEXT from './PagedChoiceField.i18n.json';

interface Choice { value: string; label: string | Record<string, string> }
interface ChoicePage { records: Choice[]; total: number; pageNum: number; pageSize: number }
interface Props { endpoint: string; value: string[]; onChange: (value: string[]) => void; label: string }

/** Bounded server-side choices; selected labels use the same authorized endpoint. */
export default function PagedChoiceField({ endpoint, value, onChange, label }: Props) {
  const { user } = useAuth();
  const identity = `${user?.tenantId ?? ''}:${user?.pid ?? ''}:${endpoint}`;
  return <PagedChoiceSession key={identity} identity={identity} endpoint={endpoint} value={value} onChange={onChange} label={label} />;
}

function PagedChoiceSession({ identity, endpoint, value, onChange, label }: Props & { identity: string }) {
  const { locale, t } = useI18n();
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale, t);
  const currentIdentity = useRef(identity); currentIdentity.current = identity;
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<{ identity: string; data: ChoicePage }>();
  const [labels, setLabels] = useState<{ identity: string; data: Map<string, Choice> }>();
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [hydrating, setHydrating] = useState(false);
  const [hydrationFailed, setHydrationFailed] = useState(false);
  const selectedKey = JSON.stringify(value);

  async function load(params: Record<string, string | number>): Promise<ChoicePage> {
    const response = await fetchResult<ChoicePage>(endpoint, { method: 'get', params });
    const data = response.data;
    if (!ResultHelper.isSuccess(response) || !data || !Array.isArray(data.records)
      || data.records.length > 50 || !Number.isInteger(data.total) || data.total < 0
      || data.records.some(choice => !choice || typeof choice.value !== 'string' || !choice.value
        || !choice.label || !['string', 'object'].includes(typeof choice.label))) {
      throw new Error('Invalid paged choice response');
    }
    return data;
  }

  useEffect(() => {
    let active = true;
    setLoading(true); setFailed(false);
    load({ query, pageNum: page, pageSize: 25 }).then(data => {
      if (active && currentIdentity.current === identity) setResult({ identity, data });
    }).catch(() => { if (active && currentIdentity.current === identity) setFailed(true); })
      .finally(() => { if (active && currentIdentity.current === identity) setLoading(false); });
    return () => { active = false; };
  }, [identity, query, page, retry]);

  useEffect(() => {
    let active = true;
    setHydrating(true); setHydrationFailed(false);
    const hydrate = async () => {
      const data = new Map<string, Choice>();
      for (let offset = 0; offset < value.length; offset += 50) {
        if (!active || currentIdentity.current !== identity) return;
        const batch = await load({ pageNum: 1, pageSize: 50, values: value.slice(offset, offset + 50).join(',') });
        batch.records.forEach(choice => data.set(choice.value, choice));
      }
      if (active && currentIdentity.current === identity) setLabels({ identity, data });
    };
    hydrate().catch(() => { if (active && currentIdentity.current === identity) setHydrationFailed(true); })
      .finally(() => { if (active && currentIdentity.current === identity) setHydrating(false); });
    return () => { active = false; };
  }, [identity, selectedKey, retry]);

  const data = result?.identity === identity ? result.data : undefined;
  const selectedLabels = labels?.identity === identity ? labels.data : new Map<string, Choice>();
  const selected = value.flatMap(id => selectedLabels.has(id) ? [selectedLabels.get(id)!] : []);
  const unavailable = !hydrating && !hydrationFailed && selected.length < value.length;
  const choices = [...selected, ...(loading || failed ? [] : data?.records ?? []).filter(choice => !value.includes(choice.value))];
  return <div className="overflow-hidden rounded-lg border border-gray-300 dark:border-gray-600">
    <input type="search" aria-label={`${label}: ${text('search')}`} placeholder={text('search')}
      value={query} onChange={event => { setQuery(event.target.value); setPage(1); }}
      className="w-full border-0 border-b border-gray-200 bg-transparent px-3 py-2 text-sm" />
    <div className="flex items-center justify-between px-3 py-2 text-sm">
      <span>{text('selected')}: {value.length}</span>
      {value.length > 0 && <button type="button" className="underline" onClick={() => onChange([])}>{text('clear')}</button>}
    </div>
    {(failed || hydrationFailed) && <div role="alert" className="px-3 py-2 text-sm text-red-600">
      {text('failed')} <button type="button" className="underline" onClick={() => setRetry(previous => previous + 1)}>{text('retry')}</button>
    </div>}
    {unavailable && <p role="alert" className="px-3 py-2 text-sm text-amber-700">{text('unavailable')}</p>}
    <div className="max-h-48 overflow-y-auto">
      {choices.map(choice => <label key={choice.value} className="flex min-h-11 cursor-pointer items-center gap-3 border-t border-gray-100 px-3 py-2 text-sm">
        <input type="checkbox" checked={value.includes(choice.value)} onChange={() => onChange(value.includes(choice.value)
          ? value.filter(id => id !== choice.value) : [...value, choice.value])} />
        <span className="min-w-0 break-words">{getLocalizedText(choice.label, locale, t)}</span>
      </label>)}
      {(loading || hydrating) && <p role="status" className="px-3 py-2 text-sm">{text('loading')}</p>}
      {!loading && !failed && choices.length === 0 && <p className="px-3 py-3 text-sm text-gray-500">{text('empty')}</p>}
    </div>
    <div className="flex items-center justify-between gap-2 border-t px-3 py-2 text-sm">
      <button type="button" className="rounded-md border border-gray-200 px-2 py-1 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-gray-600 dark:hover:bg-gray-700" disabled={loading || page === 1} onClick={() => setPage(previous => previous - 1)}>{text('previous')}</button>
      <span>{page} / {Math.max(1, Math.ceil((data?.total ?? 0) / 25))}</span>
      <button type="button" className="rounded-md border border-gray-200 px-2 py-1 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-gray-600 dark:hover:bg-gray-700" disabled={loading || failed || page * 25 >= (data?.total ?? 0)} onClick={() => setPage(previous => previous + 1)}>{text('next')}</button>
    </div>
  </div>;
}
