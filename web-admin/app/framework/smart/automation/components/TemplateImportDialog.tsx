import React, { useCallback, useRef, useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import { RelationField, type RelationOption } from '~/ui/smart/picker/RelationField';
import type { AutomationTemplateDocument, AutomationTemplateParameter } from '../services/automationTemplateIO';

function RecordInput({parameter, value, headers, onChange}: {
  parameter: AutomationTemplateParameter; value?: string; headers: HeadersInit; onChange: (value: string) => void;
}) {
  const st = useSmartText();
  const [error, setError] = useState('');
  const knownOptions = useRef(new Map<string, RelationOption>());
  const valueRef = useRef(value); valueRef.current = value;
  // Keep headers stable across picker updates without restarting its data-loading effect.
  const headersRef = useRef(headers); headersRef.current = headers;
  const fetchOptions = useCallback(async (keyword?: string) => {
    try {
      const query = new URLSearchParams({pageNum: '1', pageSize: '100', ...(keyword ? {keyword} : {})});
      const response = await fetch(`/api/dynamic/${encodeURIComponent(parameter.modelCode)}/list?${query}`, {headers: headersRef.current});
      const result = await response.json();
      if (!response.ok || String(result?.code) !== '0' || !Array.isArray(result.data?.records)) throw new Error();
      const options: RelationOption[] = result.data.records.flatMap((record: Record<string, unknown>) => {
        const label = record[parameter.displayField];
        if (typeof record.pid !== 'string' || typeof label !== 'string' || !label.trim()) return [];
        return [{value: record.pid, label}];
      });
      options.forEach(option => knownOptions.current.set(option.value, option));
      setError(options.length ? '' : st({zh: '没有可选择的记录，请先创建记录，或调整搜索条件。', en: 'No records available. Create a record or adjust the search.'}));
      const selected = valueRef.current ? knownOptions.current.get(valueRef.current) : undefined;
      return selected && !options.some(option => option.value === selected.value) ? [selected, ...options] : options;
    } catch {
      setError(st({zh: '无法读取记录。请检查当前空间和数据读取权限后重试。', en: 'Records could not be loaded. Check the workspace and read permissions, then retry.'}));
      throw new Error('Template record options unavailable');
    }
  }, [parameter.modelCode, parameter.displayField, st]);
  return <RelationField name={parameter.key} label={parameter.label} required value={value}
    targetModelCode={parameter.modelCode} displayField="label" valueField="value" relationType="many_to_one"
    placeholder={st({zh: '搜索并选择本次使用的记录', en: 'Search and select the record to use'})}
    fetchOptions={fetchOptions} onChange={next => onChange(typeof next === 'string' ? next : '')} error={error} />;
}

export function TemplateImportDialog({document, headers, onCancel, onImported}: {
  document: AutomationTemplateDocument; headers: HeadersInit; onCancel: () => void; onImported: () => void;
}) {
  const st = useSmartText();
  const [name, setName] = useState(document.name);
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const parameters = document.templateParameters ?? [];
  const ready = Boolean(name.trim()) && parameters.every(parameter => Boolean(values[parameter.key]));
  const submit = async () => {
    if (!ready || busy) return;
    setBusy(true); setError('');
    try {
      const response = await fetch('/api/automations', {method: 'POST', headers,
        body: JSON.stringify({...document, name: name.trim(), templateValues: values, enabled: false})});
      const result = await response.json();
      if (!response.ok || String(result?.code) !== '0' || !result.data?.pid || result.data.enabled !== false) throw new Error();
      onImported();
    } catch {
      setError(st({zh: '导入失败。请确认所选记录仍可读取，并检查自动化管理权限后重试。', en: 'Import failed. Verify the selected record and automation permissions, then retry.'}));
    } finally { setBusy(false); }
  };
  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
    <section role="dialog" aria-modal="true" aria-labelledby="template-import-title" className="w-full max-w-lg rounded-xl bg-white shadow-xl">
      <header className="border-b border-gray-200 px-6 py-5"><h2 id="template-import-title" className="text-lg font-semibold">{st({zh: '配置导入参数', en: 'Configure template inputs'})}</h2>
        <p className="mt-1 text-sm text-gray-500">{st({zh: '选择本空间的真实记录，系统会统一填写所有引用。', en: 'Select a record in this workspace. All references will be bound together.'})}</p></header>
      <div className="max-h-[65vh] space-y-5 overflow-y-auto px-6 py-5">
        <label className="block text-sm font-medium">{st({zh: '副本名称', en: 'Copy name'})}<input aria-label={st({zh: '副本名称', en: 'Copy name'})} value={name} maxLength={200} onChange={event => setName(event.target.value)} className="mt-2 w-full rounded-md border border-gray-300 px-3 py-2" /></label>
        {parameters.map(parameter => <div key={parameter.key}><RecordInput parameter={parameter} value={values[parameter.key]} headers={headers} onChange={value => setValues(current => ({...current, [parameter.key]: value}))} />
          {parameter.helpText && <p className="mt-2 text-xs leading-5 text-gray-500">{parameter.helpText}</p>}</div>)}
        <p className="rounded-lg bg-amber-50 p-3 text-sm leading-6 text-amber-900">{document.description}</p>
        <p className="text-xs text-gray-500">{st({zh: '导入不会执行流程，也不会增加数据权限。副本保持停用，检查后再启用。', en: 'Import does not run the flow or grant permissions. Review the disabled copy before enabling.'})}</p>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      </div>
      <footer className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4"><button disabled={busy} onClick={onCancel} className="rounded-md border px-4 py-2 text-sm">{st({zh: '取消', en: 'Cancel'})}</button>
        <button disabled={!ready || busy} onClick={submit} className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50">{st(busy ? {zh: '正在导入…', en: 'Importing…'} : {zh: '导入停用副本', en: 'Import disabled copy'})}</button></footer>
    </section>
  </div>;
}
