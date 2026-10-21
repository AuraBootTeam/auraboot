import { useEffect, useState } from 'react';
import type { FieldAdapter } from '~/ui/field-adapter';
import { useI18n } from '~/contexts/I18nContext';
import { fetchFieldOptions } from '~/shared/services/resourceSelectService';
import messages from './ModelFieldMapEditor.i18n.json';

/** Author the existing object payload using real model metadata, without changing its protocol. */
export function ModelFieldMapEditor({
  adapter,
  label,
  modelKey = 'modelCode',
}: {
  adapter: FieldAdapter<unknown>;
  label?: string;
  modelKey?: string;
}) {
  const { locale } = useI18n();
  const text = messages[locale.startsWith('zh') ? 'zh-CN' : 'en'];
  const model = String(adapter.context?.[modelKey] ?? '');
  const [fields, setFields] = useState<Awaited<ReturnType<typeof fetchFieldOptions>>>([]);
  const [loadedModel, setLoadedModel] = useState('');
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [revision, setRevision] = useState(0);
  const [adding, setAdding] = useState(false);
  useEffect(() => {
    let cancelled = false;
    setState('loading');
    setAdding(false);
    if (!model) return;
    fetchFieldOptions(model)
      .then((value) => {
        if (cancelled) return;
        setFields(value.filter((field) => !field.readOnly));
        setLoadedModel(model);
        setState('ready');
      })
      .catch(() => {
        if (!cancelled) setState('error');
      });
    return () => {
      cancelled = true;
    };
  }, [model, revision]);

  let mapping: Record<string, unknown> = {};
  let invalid = false;
  try {
    const value = typeof adapter.value === 'string' ? JSON.parse(adapter.value) : adapter.value;
    if (value != null) {
      if (typeof value !== 'object' || Array.isArray(value)) invalid = true;
      else mapping = value as Record<string, unknown>;
    }
  } catch {
    invalid = true;
  }
  const ready = state === 'ready' && loadedModel === model;
  const disabled = adapter.disabled || adapter.readOnly || !ready || invalid;
  const entries = Object.entries(mapping);
  const update = (key: string, value: unknown) => adapter.setValue({ ...mapping, [key]: value });
  const rename = (oldKey: string, key: string, value: unknown) => {
    const next = { ...mapping };
    delete next[oldKey];
    next[key] = value;
    adapter.setValue(next);
  };
  return (
    <section data-testid="model-field-map" className="min-w-0 space-y-3">
      {label && <h4 className="text-sm font-medium text-gray-800">{label}</h4>}
      {!model ? (
        <p className="text-sm text-gray-500">{text.selectModel}</p>
      ) : state === 'error' ? (
        <div role="alert" className="text-sm text-red-700">
          {text.failed}
          <button
            type="button"
            onClick={() => setRevision((n) => n + 1)}
            className="ml-2 text-blue-600"
          >
            {text.refresh}
          </button>
        </div>
      ) : !ready ? (
        <p role="status" className="text-sm text-gray-500">
          {text.loading}
        </p>
      ) : (
        fields.length === 0 && <p className="text-sm text-gray-500">{text.empty}</p>
      )}
      {invalid && (
        <p role="alert" className="text-sm text-red-700">
          {text.invalid}
        </p>
      )}
      {entries.map(([key, value], index) => {
        const selected = fields.find((field) => field.value === key);
        const expression = typeof value === 'string' && value.includes('${');
        const structured = value !== null && typeof value === 'object';
        const numeric =
          !expression &&
          (typeof value === 'number' ||
            /^(integer|decimal|number)$/.test(selected?.description ?? ''));
        const boolean =
          !expression && (typeof value === 'boolean' || selected?.description === 'boolean');
        return (
          <div
            key={key}
            className="min-w-0 space-y-2 rounded-md border border-gray-200 bg-gray-50/60 p-3"
          >
            <div className="flex min-w-0 items-center gap-2">
              <select
                aria-label={`${text.field} ${index + 1}`}
                data-testid={`model-map-field-${index}`}
                disabled={disabled}
                className="min-w-0 flex-1 rounded border border-gray-300 bg-white px-2 py-2 text-sm"
                value={key}
                onChange={(event) => rename(key, event.target.value, value)}
              >
                {!selected && <option value={key}>{text.unknown}</option>}
                {fields
                  .filter((field) => field.value === key || !(field.value in mapping))
                  .map((field) => (
                    <option key={field.value} value={field.value}>
                      {field.label}
                    </option>
                  ))}
              </select>
              <button
                type="button"
                disabled={disabled}
                aria-label={`${text.remove} ${index + 1}`}
                data-testid={`model-map-remove-${index}`}
                className="shrink-0 px-1 text-gray-500 hover:text-red-600 disabled:opacity-40"
                onClick={() => {
                  const next = { ...mapping };
                  delete next[key];
                  adapter.setValue(next);
                }}
              >
                ×
              </button>
            </div>
            <div className="flex min-w-0 items-center gap-2">
              <select
                aria-label={`${text.value} ${index + 1}`}
                disabled={disabled || structured}
                value={structured ? 'structured' : expression ? 'expression' : 'fixed'}
                className="w-24 shrink-0 rounded border border-gray-300 bg-white px-2 py-2 text-xs"
                onChange={(event) =>
                  update(key, event.target.value === 'expression' ? '${trigger.recordPid}' : '')
                }
              >
                <option value="fixed">{text.fixed}</option>
                <option value="expression">{text.expression}</option>
                {structured && <option value="structured">{text.complex}</option>}
              </select>
              {structured ? (
                <details className="min-w-0 text-xs">
                  <summary>{text.preview}</summary>
                  <pre className="max-h-40 overflow-auto break-all whitespace-pre-wrap">
                    {JSON.stringify(value, null, 2)}
                  </pre>
                </details>
              ) : boolean ? (
                <input
                  type="checkbox"
                  aria-label={`${text.fixed} ${index + 1}`}
                  data-testid={`model-map-value-${index}`}
                  disabled={disabled}
                  checked={value === true}
                  onChange={(event) => update(key, event.target.checked)}
                />
              ) : (
                <input
                  aria-label={`${text.fixed} ${index + 1}`}
                  data-testid={`model-map-value-${index}`}
                  disabled={disabled}
                  type={numeric ? 'number' : 'text'}
                  step="any"
                  className="min-w-0 flex-1 rounded border border-gray-300 bg-white px-2 py-2 text-sm"
                  value={value == null ? '' : String(value)}
                  onChange={(event) =>
                    update(
                      key,
                      numeric && event.target.value !== ''
                        ? Number(event.target.value)
                        : event.target.value,
                    )
                  }
                />
              )}
            </div>
          </div>
        );
      })}
      {adding && (
        <select
          aria-label={text.choose}
          data-testid="model-map-new-field"
          disabled={disabled}
          className="w-full rounded border border-gray-300 px-2 py-2 text-sm"
          value=""
          onChange={(event) => {
            update(event.target.value, '');
            setAdding(false);
          }}
        >
          <option value="" disabled>
            {text.choose}
          </option>
          {fields
            .filter((field) => !(field.value in mapping))
            .map((field) => (
              <option key={field.value} value={field.value}>
                {field.label}
              </option>
            ))}
        </select>
      )}
      {!adapter.readOnly && (
        <button
          type="button"
          data-testid="model-map-add"
          disabled={disabled || fields.every((field) => field.value in mapping)}
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm text-blue-600 disabled:opacity-40"
          onClick={() => setAdding(true)}
        >
          {text.add}
        </button>
      )}
      {entries.length === 0 && !invalid && (
        <p className="text-xs leading-relaxed text-gray-500">{text.none}</p>
      )}
    </section>
  );
}
