import { useEffect, useMemo, useState } from 'react';
import { PropertyFieldRenderer } from '~/shared/designer/PropertyFieldRenderer';
import type { PropertySchema } from '~/shared/designer/types';
import { fetchFieldOptions, type ResourceOption } from '~/shared/services/resourceSelectService';
import { fetchResult } from '~/shared/services/http-client';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import type { FieldOption } from './ConditionBuilder';
import TEXT from './ActionModelParameters.i18n.json';

interface CommandMetadata {
  modelCode: string;
  inputSchema?: string;
  executionConfig?: string;
}

function object(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
}

export function commandParameterKeys(command: CommandMetadata): string[] {
  const schema = command.inputSchema ? object(JSON.parse(command.inputSchema)) : {};
  const config = command.executionConfig ? object(JSON.parse(command.executionConfig)) : {};
  const properties = object(schema.properties);
  const fields = config.inputFields;
  return [...new Set([...Object.keys(properties),
    ...(Array.isArray(fields) ? fields.filter((field): field is string => typeof field === 'string') : [])])];
}

/** Schema-driven model-operation parameters; it does not execute business actions. */
export function ActionModelParameters({ actionType, payload, fields, onChange, currentModelCode = '', readOnly = false }: {
  actionType: string;
  payload: Record<string, unknown>;
  fields: FieldOption[];
  onChange: (payload: Record<string, unknown>) => void;
  readOnly?: boolean;
  currentModelCode?: string;
}) {
  const { locale } = useI18n();
  const text = (key: keyof typeof TEXT) => getLocalizedText(TEXT[key], locale);
  const isCommand = actionType === 'EXECUTE_COMMAND';
  const modelCode = !isCommand && currentModelCode ? currentModelCode : typeof payload.modelCode === 'string' ? payload.modelCode : '';
  const commandCode = typeof payload.commandCode === 'string' ? payload.commandCode : '';
  const [modelFields, setModelFields] = useState<ResourceOption[]>([]);
  const [parameterKeys, setParameterKeys] = useState<string[]>([]);
  const [parameterSchema, setParameterSchema] = useState<Record<string, unknown>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const values = object(isCommand ? payload.params : payload.fields);
  const [selectedField, setSelectedField] = useState('');
  const references = useMemo(() => fields.filter(field => field.visible !== false && !field.masked)
    .map(field => ({ label: `${field.modelName ? `${field.modelName} · ` : ''}${field.label}`,
      value: `\u0024{${field.scope}.${field.path}}` })), [fields]);

  useEffect(() => {
    let cancelled = false;
    setParameterKeys([]);
    setParameterSchema({});
    setModelFields([]);
    setError('');
    if (!modelCode) return;
    setLoading(true);
    Promise.all([
      fetchFieldOptions(modelCode),
      isCommand && commandCode
        ? fetchResult<CommandMetadata>(`/api/meta/commands/by-code/${encodeURIComponent(commandCode)}`)
        : Promise.resolve(undefined),
    ]).then(([loadedFields, response]) => {
      if (cancelled) return;
      if (response?.data && response.data.modelCode !== modelCode) throw Error('Command model mismatch');
      setModelFields(loadedFields);
      setParameterKeys(response?.data ? commandParameterKeys(response.data) : []);
      setParameterSchema(response?.data?.inputSchema ? object(JSON.parse(response.data.inputSchema)) : {});
    }).catch(reason => {
      if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason));
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [modelCode, commandCode, isCommand]);

  const patch = (key: string, value: unknown) => {
    if (readOnly) return;
    onChange({ ...payload, [key]: value });
  };
  const render = (schema: PropertySchema<string>, value: unknown, setValue: (value: unknown) => void) => (
    <PropertyFieldRenderer key={schema.key} schema={schema} adapter={{
      value, setValue, disabled: readOnly || loading, readOnly,
      required: schema.required, context: { modelCode },
    }} />
  );
  const updateValue = (key: string, value: unknown) => patch(isCommand ? 'params' : 'fields', { ...values, [key]: value });
  const keys = isCommand ? parameterKeys : Object.keys(values);

  return <div className="rule-action-model" data-testid="rule-action-model-parameters">
    {render({ key: 'modelCode', label: text('model'), type: !isCommand && currentModelCode ? 'select' : 'model-select', required: true,
      ...(!isCommand && currentModelCode ? { options: [{ label: fields.find(field => field.modelCode === currentModelCode)?.modelName ?? currentModelCode, value: currentModelCode }] } : {}) }, modelCode, value => {
      if (!readOnly) onChange({ ...payload, modelCode: value, commandCode: '', params: {}, fields: {} });
    })}
    {isCommand && render({ key: 'commandCode', label: text('command'), type: 'command-select', required: true }, commandCode, value => {
      if (!readOnly) onChange({ ...payload, commandCode: value, params: {} });
    })}
    {isCommand && render({ key: 'targetRecordPid', label: text('target'), type: 'select',
      options: [...(modelCode === currentModelCode ? [{label: text('currentRecord'), value: '${record.recordPid}'}] : []), ...references.filter(reference => fields.some(field => `${field.scope}.${field.path}` === reference.value.slice(2,-1) && field.reference?.targetEntity === modelCode))], description: text('targetHint') }, payload.targetRecordPid ?? '', value => patch('targetRecordPid', value))}
    {loading && <p role="status">{text('loading')}</p>}
    {error && <p role="alert" className="text-red-700">{text('loadError')} · {error}</p>}
    {!isCommand && modelCode && render({ key: 'field', label: text('field'), type: 'select',
      options: modelFields.map(field => ({ label: field.label, value: field.value })) }, selectedField, value => {
        const key = String(value);
        setSelectedField(key);
        if (key && !Object.hasOwn(values, key)) updateValue(key, '');
      })}
    {keys.map(key => {
      const metadata = modelFields.find(field => field.value === key);
      const declared = object(object(parameterSchema.properties)[key]);
      const label = typeof declared.title === 'string' ? declared.title : metadata?.label ?? text('unnamedParameter');
      const value = values[key];
      const reference = typeof value === 'string' && value.startsWith('${');
      const type = typeof declared.type === 'string' ? declared.type : metadata?.description?.toLowerCase();
      const literalType = ['integer', 'decimal', 'number', 'long'].includes(type ?? '') ? 'number'
        : type === 'boolean' ? 'boolean' : 'text';
      const compatibleReferences = references.filter(reference => fields.some(field => {
        if (`${field.scope}.${field.path}` !== reference.value.slice(2,-1)) return false;
        if (literalType === 'number') return ['integer','decimal'].includes(field.dataType);
        if (literalType === 'boolean') return field.dataType === 'boolean';
        return !['integer','decimal','boolean','collection','object'].includes(field.dataType);
      }));
      return <div className="rule-action-assignment" key={key}>
        <div><strong>{label}</strong>{!isCommand && <button type="button" disabled={readOnly}
          onClick={() => { const next = { ...values }; delete next[key]; patch('fields', next); }}
          className="ml-2 text-xs text-slate-500">{text('remove')}</button>}</div>
        {render({ key: `${key}-source`, label: text('source'), type: 'select', options: [
          { label: text('fixed'), value: 'fixed' }, { label: text('reference'), value: 'reference' },
        ] }, reference ? 'reference' : 'fixed', next => updateValue(key, next === 'reference' ? '${}' : ''))}
        {render({ key: `${key}-value`, label: text('value'), type: reference ? 'select' : literalType,
          required: Array.isArray(parameterSchema.required) && parameterSchema.required.includes(key),
          ...(reference ? { options: [...(literalType === 'text' ? [{ label: text('currentRecord'), value: '${record.recordPid}' }] : []), ...compatibleReferences] } : {}) }, value ?? '', next => updateValue(key, next))}
      </div>;
    })}
    {isCommand && commandCode && !loading && !error && keys.length === 0 && <p>{text('noParams')}</p>}
    <p className="text-sm text-slate-500">{text('permissionHint')}</p>
  </div>;
}
