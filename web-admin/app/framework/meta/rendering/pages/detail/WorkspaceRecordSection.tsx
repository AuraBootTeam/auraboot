import React, { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { formatInTimezone } from '~/shared/services/dateTimeFormatService';
import { WorkspaceCell } from '~/framework/meta/runtime/renderers/WorkspaceCells';
import { FileAttachmentField } from '~/ui/smart/display/FileAttachmentField';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';

/** Read-only business facts, including declared attributes of referenced objects. */
export function WorkspaceRecordSection({ block, fields, record, token, locale, timezone, t, getDictItems }: any) {
  const [references, setReferences] = useState<Record<string, any>>({});
  const [referenceError, setReferenceError] = useState(false);
  const targets = JSON.stringify(fields.filter((field: any) => field.referenceDisplayField && record[field.field]).map((field: any) => ({ field: field.field, model: field.refTarget?.targetModel || field.refTarget?.targetEntity || field.referenceModelCode, pid: record[field.field] })));
  useEffect(() => {
    let current = true;
    setReferences({}); setReferenceError(false);
    const requests = JSON.parse(targets);
    Promise.all(requests.map(async (target: any) => {
      if (!target.model) throw new Error('Referenced model is required.');
      const result = await fetchResult(`/api/dynamic/${target.model}/${encodeURIComponent(target.pid)}`, { method: 'get', token });
      if (!ResultHelper.isSuccess(result)) throw new Error('Referenced object could not be loaded.');
      return [target.field, result.data];
    })).then(entries => { if (current) setReferences(Object.fromEntries(entries)); })
      .catch(() => { if (current) setReferenceError(true); });
    return () => { current = false; };
  }, [targets, token]);
  const display = (field: any) => {
    const value = field.referenceDisplayField ? references[field.field]?.[field.referenceDisplayField] : record[`${field.field}_display`] ?? record[field.field];
    if (value == null || value === '') return '—';
    if (String(field.component).toLowerCase() === 'richtext' || field.dataType === 'richtext')
      return <div className="prose prose-sm max-w-none break-words dark:prose-invert" data-testid={`readonly-richtext-${field.field}`} dangerouslySetInnerHTML={{ __html: sanitizeHtml(String(value)) }} />;
    if (field.dictCode) {
      const item = (getDictItems(field.dictCode) || []).find((item: any) => String(item.value) === String(value));
      return item ? getLocalizedText(item.label, locale, t) : '—';
    }
    if (typeof value === 'boolean') return getLocalizedText(value ? { 'zh-CN':'是',en:'Yes' } : { 'zh-CN':'否',en:'No' }, locale, t);
    if (field.presentation) return <WorkspaceCell value={value} record={record} column={{ ...field, timezone }} locale={locale} />;
    if (field.dataType === 'datetime' || field.dataType === 'timestamp') return formatInTimezone(value, locale.startsWith('zh') ? 'YYYY年MM月DD日 HH:mm:ss' : 'MMM DD, YYYY HH:mm:ss', timezone);
    if (field.dataType === 'date') return formatInTimezone(value, locale.startsWith('zh') ? 'YYYY年MM月DD日' : 'MMM DD, YYYY', timezone);
    if (typeof value === 'object' || /^[0-9A-HJKMNP-TV-Z]{26}$/.test(String(value))) return '—';
    return String(value);
  };
  return <section className="border-border bg-panel rounded-card border px-6 py-4" data-testid={`object-section-${block.id}`}>
    <h3 className="text-text mb-4 text-sm font-semibold">{getLocalizedText(block.title, locale, t)}</h3>
    {referenceError && <p role="alert" className="text-status-red mb-3 text-xs">{getLocalizedText({ 'zh-CN': '关联对象加载失败，请刷新重试。', en: 'Could not load the related object. Refresh to try again.' }, locale, t)}</p>}
    <dl className="grid grid-cols-1 gap-x-8 gap-y-3 md:grid-cols-2">
      {fields.map((field: any, index: number) => <div key={`${field.field}:${index}`} className={(field.colSpan || 6) >= 12 ? 'md:col-span-2' : ''}>
        {!field.hideLabel && <dt className="text-text-3 mb-1 text-[11px]">{getLocalizedText(field.label || {}, locale, t)}</dt>}
        <dd className="text-text text-xs leading-5 whitespace-pre-wrap">
          {(field.dataType === 'file' || String(field.component).toLowerCase() === 'fileattachment') ? <FileAttachmentField value={record[field.field]} readOnly /> : field.dataType === 'reference' && !field.referenceDisplayField && record[field.field] && (field.refTarget?.targetModel || field.refTarget?.targetEntity || field.referenceModelCode) ? <Link className="hover:text-accent" to={`/p/${field.refTarget?.targetModel || field.refTarget?.targetEntity || field.referenceModelCode}/view/${record[field.field]}`}>{display(field)}</Link> : display(field)}
        </dd>
      </div>)}
    </dl>
  </section>;
}
