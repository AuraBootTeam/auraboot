import { useEffect, useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useTimezone } from '~/contexts/TimezoneContext';
import { useAuth } from '~/contexts/AuthContext';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useDictCache } from './pages/hooks/useDictCache';
import { WorkspaceRecordSection } from './pages/detail/WorkspaceRecordSection';
import { enrichDetailField, evaluateDetailVisibleWhen } from './pages/DetailPageContent';
import type { UseDslFormReturn } from '../hooks/useDslForm';

export interface DslFormReadOnlyOptions {
  record: Record<string, any>;
  excludeFields?: string[];
  asideBlockIds?: string[];
  /** An authorized frozen projection must not fetch live model metadata. */
  fieldMetadata?: any[];
}

export function DslFormReadOnly({ form, record, excludeFields = [], asideBlockIds = [], fieldMetadata }: DslFormReadOnlyOptions & { form: UseDslFormReturn }) {
  const { t, locale } = useI18n();
  const { timezone } = useTimezone();
  const { hasPermission } = useAuth();
  const [metadata, setMetadata] = useState<any[] | null>(null);
  const [error, setError] = useState(false);
  const model = form.schema?.modelCode;
  const token = form.rendererProps.token || undefined;
  useEffect(() => {
    let current = true;
    setMetadata(null); setError(false);
    if (fieldMetadata) { setMetadata(fieldMetadata); return () => { current = false; }; }
    fetchResult<any[]>(`/api/dynamic/${model}/field-meta`, { method: 'get', token }).then(result => {
      if (!current) return;
      if (!ResultHelper.isSuccess(result) || !Array.isArray(result.data)) { setError(true); return; }
      setMetadata(result.data);
    }).catch(() => { if (current) setError(true); });
    return () => { current = false; };
  }, [model, token, fieldMetadata]);
  const sections = useMemo(() => (form.schema?.blocks || []).filter((block: any) =>
    block.blockType === 'form-section' && (!block.permissionCode || hasPermission(block.permissionCode)) &&
    evaluateDetailVisibleWhen(block.visibleWhen, record)
  ).map((block: any) => ({ ...block, fields: (block.fields || []).filter((field: any) =>
    !excludeFields.includes(field.field) && !field.hidden &&
    String(form.effectivePermissions[field.field] || '').toLowerCase() !== 'hidden' &&
    (!field.permissionCode || hasPermission(field.permissionCode)) && evaluateDetailVisibleWhen(field.visibleWhen, record)
  ).map((field: any) => enrichDetailField(field, metadata?.find(meta => meta.code === field.field))) })).filter((block: any) => block.fields.length),
  [form.schema, form.effectivePermissions, record, metadata, excludeFields, hasPermission]);
  const codes = useMemo(() => Array.from(new Set<string>(sections.flatMap((section: any) => section.fields.map((field: any) => field.dictCode).filter(Boolean)))), [sections]);
  const { loaded, getDictItems } = useDictCache({ dictCodes: codes, token });
  if (error) return <p role="alert">{locale.startsWith('zh') ? '表单字段加载失败，请刷新重试。' : 'Form fields could not be loaded. Refresh to retry.'}</p>;
  if (!metadata || !loaded) return <p role="status">{locale.startsWith('zh') ? '正在加载表单…' : 'Loading form…'}</p>;
  const render = (block: any) => <WorkspaceRecordSection key={block.id} block={block} fields={block.fields} record={record} token={token} locale={locale} timezone={timezone} t={t} getDictItems={getDictItems} />;
  const aside = sections.filter((block: any) => asideBlockIds.includes(block.id));
  return <div data-testid="dsl-form-readonly" className={aside.length ? 'grid gap-5 lg:grid-cols-[minmax(0,1fr)_300px]' : 'space-y-5'}>
    <div className="min-w-0 space-y-5">{sections.filter((block: any) => !asideBlockIds.includes(block.id)).map(render)}</div>
    {aside.length > 0 && <aside className="min-w-0 space-y-5">{aside.map(render)}</aside>}
  </div>;
}
