/**
 * PropertyFieldRenderer - Unified property field renderer for all designers.
 *
 * Accepts a FieldAdapter (value/setValue/error/required) and a PropertySchema,
 * then renders the appropriate base-field component. This eliminates ~95%
 * duplication between DashboardPropertyField and Flow PropertyField.
 */

import React, { useState, useEffect, useMemo } from 'react';
import { Check } from 'lucide-react';
import {
  BaseInput,
  BaseSelect,
  BaseSwitch,
  BaseTextarea,
  BaseFormulaEditor,
  BaseResourceSelect,
} from '~/ui/base-fields';
import {
  fetchPageOptions,
  fetchDashboardOptions,
  fetchProcessOptions,
  fetchAutomationOptions,
  fetchCommandOptions,
  fetchModelOptions,
  fetchFieldOptions,
  fetchSemanticModelOptions,
} from '~/shared/services/resourceSelectService';
import { ExpressionEditor } from './expression';
import type { FieldOption as ExpressionFieldOption } from './expression/types';
import { DependentFieldSelect } from './DependentFieldSelect';
import { DependentMultiSelect } from './DependentMultiSelect';
import { LocalizedTextInput, type LocalizedTextValue } from './LocalizedTextInput';
import { IconPicker } from '~/plugins/core-designer/components/studio/workbench/panels/property-editors/IconPicker';
import { ArrayItemEditor } from './ArrayItemEditor';
import { createDecisionApi } from '~/shared/decision/api/decisionApi';
import { factCatalogToFieldOptions } from '~/shared/decision/ui/factCatalogAdapter';
import type { FieldOption as DecisionFieldOption } from '~/shared/decision/ui/ConditionBuilder';
import {
  DecisionRuleBindingBlock,
  type DecisionOption,
  type RuleConsumerBindingDraft,
} from '~/ui/smart/decision/DecisionRuleBindingBlock';
import { getApiService } from '~/shared/services/ApiService';
import { dictService } from '~/shared/services/dictService';
import { toast } from 'sonner';
import type { FieldAdapter } from '~/ui/field-adapter';
import type { PropertySchema } from './types';
import { getLocalizedText, useSmartText } from '~/utils/i18n';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export interface PropertyFieldRendererProps {
  /** Schema that describes the field type, label, options, etc. */
  schema: PropertySchema<string>;
  /** FieldAdapter that bridges the designer store with base-field components. */
  adapter: FieldAdapter<unknown>;
}

/**
 * Render a single property field based on its PropertySchema.
 *
 * The caller is responsible for:
 *  - Resolving i18n labels to plain strings before passing `schema`
 *  - Creating the appropriate FieldAdapter (flow, dashboard, etc.)
 *  - Evaluating `dependsOn` visibility (keep at panel level)
 */
export function PropertyFieldRenderer({ schema, adapter }: PropertyFieldRendererProps) {
  const { locale } = useI18n();
  const label = getLocalizedText(schema.label as string | Record<string, string>, locale);
  const placeholder = schema.placeholder
    ? getLocalizedText(schema.placeholder as string | Record<string, string>, locale)
    : undefined;
  const helpText = schema.description
    ? getLocalizedText(schema.description as string | Record<string, string>, locale)
    : undefined;

  switch (schema.type) {
    // ---- Text-like inputs ----
    case 'text':
    case 'model':
    case 'namedQuery':
      return (
        <BaseInput
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={
            placeholder ||
            (schema.type === 'model'
              ? 'Enter model code'
              : schema.type === 'namedQuery'
                ? 'Enter query code'
                : undefined)
          }
          helpText={helpText}
        />
      );

    case 'number': {
      // BaseInput stores `e.target.value` (always a string) in the adapter.
      // For `type: 'number'` schemas the persisted DSL must carry a real
      // number, otherwise downstream consumers (column.width, table.props
      // .pageSize, etc.) silently coerce types or fail Number comparisons.
      // Wrap the adapter so reads expose strings (for the input's `value`
      // prop) while writes coerce back to number | undefined.
      const baseAdapter = adapter as unknown as {
        value: unknown;
        setValue: (v: unknown) => void;
      } & Record<string, unknown>;
      const numericAdapter = {
        ...baseAdapter,
        value:
          baseAdapter.value === undefined || baseAdapter.value === null
            ? ''
            : String(baseAdapter.value),
        setValue: (v: unknown) => {
          if (v === '' || v === null || v === undefined) {
            baseAdapter.setValue(undefined);
            return;
          }
          const n = typeof v === 'number' ? v : Number(v);
          baseAdapter.setValue(Number.isNaN(n) ? undefined : n);
        },
      };
      return (
        <BaseInput
          adapter={numericAdapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder}
          helpText={helpText}
          type="number"
        />
      );
    }

    case 'textarea':
      return (
        <BaseTextarea
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder}
          helpText={helpText}
          rows={4}
        />
      );

    case 'json':
      return <JsonField adapter={adapter} name={schema.key} label={label} helpText={helpText} />;

    // ---- Selection fields ----
    case 'select':
      return (
        <BaseSelect
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder}
          helpText={helpText}
          options={(schema.options || []).map((opt) => ({
            label: getLocalizedText(opt.label as string | Record<string, string>, locale),
            value: opt.value,
          }))}
        />
      );

    case 'multiselect':
      if (schema.options && schema.options.length > 0) {
        return (
          <StaticMultiSelect
            adapter={adapter}
            label={label}
            placeholder={placeholder}
            helpText={helpText}
            options={schema.options.map((opt) => ({
              label: getLocalizedText(opt.label as string | Record<string, string>, locale),
              value: opt.value,
            }))}
          />
        );
      }
      return (
        <DependentMultiSelect
          adapter={adapter}
          label={label}
          helpText={helpText}
          placeholder={placeholder}
          dependsOnKey={schema.dependsOn?.field}
          optionSource={schema.optionSource}
          dictCode={schema.dictCode}
        />
      );

    case 'model-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select model...'}
          helpText={helpText}
          fetchOptions={fetchModelOptions}
        />
      );

    case 'field-select':
      return (
        <DependentFieldSelect
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select field...'}
          helpText={helpText}
        />
      );

    case 'boolean':
      return (
        <BaseSwitch adapter={adapter as any} name={schema.key} label={label} helpText={helpText} />
      );

    // ---- Expression / formula ----
    case 'expression':
      return (
        <ExpressionField
          schema={schema}
          adapter={adapter as any}
          name={schema.key}
          label={label}
          helpText={helpText}
        />
      );

    case 'formula':
      return (
        <BaseFormulaEditor
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder || 'Enter expression...'}
          helpText={helpText}
        />
      );

    // ---- Resource selects ----
    case 'page-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select page...'}
          helpText={helpText}
          fetchOptions={fetchPageOptions}
        />
      );

    case 'dashboard-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select dashboard...'}
          helpText={helpText}
          fetchOptions={fetchDashboardOptions}
        />
      );

    case 'process-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select process...'}
          helpText={helpText}
          fetchOptions={fetchProcessOptions}
        />
      );

    case 'automation-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select automation...'}
          helpText={helpText}
          fetchOptions={fetchAutomationOptions}
        />
      );

    case 'localizedText':
      return (
        <LocalizedTextInput
          value={adapter.value as LocalizedTextValue}
          onChange={(next) => adapter.setValue(next as unknown)}
          label={label}
          placeholder={placeholder}
          testId={schema.key}
        />
      );

    case 'icon':
      return (
        <IconPicker
          value={(adapter.value as string) || ''}
          onChange={(next) => adapter.setValue(next)}
          label={label}
          error={adapter.error}
          disabled={adapter.disabled}
        />
      );

    case 'command-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select command...'}
          helpText={helpText}
          fetchOptions={fetchCommandOptions}
        />
      );

    case 'rule-binding':
      return (
        <RuleBindingField
          adapter={adapter}
          label={label}
          helpText={helpText}
          mode={schema.ruleBindingMode ?? 'decision'}
          consumerType={schema.ruleBindingConsumerType}
          consumerCode={resolveRuleBindingConsumerCode(schema, adapter)}
          consumerCodeField={schema.ruleBindingConsumerCodeField}
          consumerNodeId={schema.ruleBindingConsumerNodeId}
          showImpactPreview={Boolean(schema.ruleBindingShowImpactPreview)}
          showTestRunner={Boolean(schema.ruleBindingShowTestRunner)}
          initialDecisionCode={schema.ruleBindingInitialDecisionCode}
          initialContextJson={resolveRuleBindingInitialContextJson(schema, adapter)}
          fieldCatalogModelCode={resolveRuleBindingModelCode(schema, adapter)}
        />
      );

    case 'semantic-model-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select semantic model...'}
          helpText={helpText}
          fetchOptions={fetchSemanticModelOptions}
        />
      );

    case 'array': {
      const items: Record<string, unknown>[] = Array.isArray(adapter.value)
        ? (adapter.value as Record<string, unknown>[])
        : [];

      const addLabel = typeof schema.addButtonLabel === 'string' ? schema.addButtonLabel : '+ Add';
      const emptyPlaceholder =
        typeof schema.placeholder === 'string' ? schema.placeholder : undefined;

      const buildDefaultItem = (): Record<string, unknown> => {
        const obj: Record<string, unknown> = {};
        for (const field of schema.itemSchema ?? []) {
          obj[field.key] =
            field.defaultValue !== undefined ? field.defaultValue : defaultForType(field.type);
        }
        return obj;
      };

      return (
        <div>
          {items.length === 0 && emptyPlaceholder && (
            <p className="text-sm text-gray-400">{emptyPlaceholder}</p>
          )}
          {items.map((item, idx) => {
            const resolvedLabel = schema.itemLabel
              ? schema.itemLabel(item, idx)
              : `Item ${idx + 1}`;
            return (
              <ArrayItemEditor
                key={idx}
                itemSchema={(schema.itemSchema ?? []) as PropertySchema<string>[]}
                value={item as any}
                onChange={(next) => {
                  const updated = items.map((it, i) => (i === idx ? next : it));
                  (adapter as FieldAdapter<unknown>).setValue(updated);
                }}
                onRemove={() => {
                  const updated = items.filter((_, i) => i !== idx);
                  (adapter as FieldAdapter<unknown>).setValue(updated);
                }}
                itemLabel={resolvedLabel}
              />
            );
          })}
          <button
            type="button"
            onClick={() => {
              (adapter as FieldAdapter<unknown>).setValue([...items, buildDefaultItem()]);
            }}
            className="mt-2 text-sm text-blue-600 hover:text-blue-700"
          >
            {addLabel}
          </button>
        </div>
      );
    }

    case 'dict-select':
      return (
        <DictSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder}
          helpText={helpText}
          dictCodeFilter={schema.dictCodeFilter}
        />
      );

    default:
      return (
        <BaseInput
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder}
          helpText={helpText}
        />
      );
  }
}

function ExpressionField({
  schema,
  adapter,
  name,
  label,
  helpText,
}: {
  schema: PropertySchema<string>;
  adapter: FieldAdapter<unknown>;
  name: string;
  label?: string;
  helpText?: string;
}) {
  const st = useSmartText();
  const modelCode = resolveExpressionFieldCatalogModelCode(schema, adapter);
  const [modelFields, setModelFields] = useState<ExpressionFieldOption[]>([]);

  useEffect(() => {
    let cancelled = false;
    if (!modelCode) {
      setModelFields((current) => (current.length === 0 ? current : []));
      return () => {
        cancelled = true;
      };
    }
    loadExpressionModelFields(modelCode, st)
      .then((fields) => {
        if (cancelled) return;
        setModelFields(fields);
      })
      .catch(() => {
        if (!cancelled) {
          setModelFields((current) => (current.length === 0 ? current : []));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [modelCode, st]);

  const fields = useMemo(
    () => [
      ...modelFields,
      ...triggerSampleFields(adapter.context, st),
      ...decisionOutputFields(adapter.context, st),
      ...workflowContextFields(adapter.context, st),
    ],
    [adapter.context, modelFields, st],
  );

  return (
    <ExpressionEditor
      adapter={adapter as any}
      name={name}
      label={label}
      helpText={helpText}
      modelFields={fields}
    />
  );
}

async function loadExpressionModelFields(
  modelCode: string,
  st: ReturnType<typeof useSmartText>,
): Promise<ExpressionFieldOption[]> {
  try {
    const api = createDecisionFieldCatalogApi();
    const fields = factCatalogToFieldOptions(await api.getFactCatalog(modelCode))
      .map((field) => decisionFactFieldToExpressionField(field, st))
      .filter((field): field is ExpressionFieldOption => Boolean(field));
    if (fields.length > 0) return fields;
  } catch (_err) {
    // Fall back to the legacy meta-model field endpoint for older runtimes.
  }
  const legacyFields = await fetchFieldOptions(modelCode);
  return legacyFields.map((option) => ({
    code: `record.${option.value}`,
    name: option.label,
    category: fieldCategoryFromDataType(option.description),
    group: st('$i18n:expression.fieldGroup.currentRecord', '当前记录'),
    insertion: `\${record.${option.value}}`,
  }));
}

function createDecisionFieldCatalogApi() {
  const service = getApiService();
  return createDecisionApi({
    get: (endpoint, params) => service.get(endpoint, params),
    post: (endpoint, body) => service.post(endpoint, body),
    delete: (endpoint) => service.delete(endpoint),
  });
}

function decisionFactFieldToExpressionField(
  field: DecisionFieldOption,
  st: ReturnType<typeof useSmartText>,
): ExpressionFieldOption | undefined {
  const path = field.path?.trim();
  if (!path) return undefined;
  const code = `${field.scope}.${path}`;
  return {
    code,
    name: field.label,
    category: fieldCategoryFromDataType(field.dataType),
    group: expressionFieldGroup(field, st),
    insertion: `\${${code}}`,
  };
}

function expressionFieldGroup(
  field: DecisionFieldOption,
  st: ReturnType<typeof useSmartText>,
): string {
  if (field.scope === 'record') {
    return field.modelName || st('$i18n:expression.fieldGroup.currentRecord', '当前记录');
  }
  const labels: Partial<Record<DecisionFieldOption['scope'], string>> = {
    actor: st('$i18n:expression.fieldGroup.actorContext', '操作者上下文'),
    event: st('$i18n:expression.fieldGroup.eventContext', '事件上下文'),
    process: st('$i18n:expression.fieldGroup.processContext', '流程上下文'),
    task: st('$i18n:expression.fieldGroup.taskContext', '任务上下文'),
    sla: st('$i18n:expression.fieldGroup.slaContext', 'SLA 上下文'),
    tenant: st('$i18n:expression.fieldGroup.tenantContext', '租户上下文'),
    time: st('$i18n:expression.fieldGroup.timeContext', '时间上下文'),
    env: st('$i18n:expression.fieldGroup.envContext', '环境上下文'),
  };
  return labels[field.scope] || field.modelName || field.scope;
}

function resolveExpressionFieldCatalogModelCode(
  schema: PropertySchema<string>,
  adapter: FieldAdapter<unknown>,
): string | undefined {
  if (schema.expressionFieldCatalogModelCode?.trim()) {
    return schema.expressionFieldCatalogModelCode.trim();
  }
  const field = schema.expressionFieldCatalogModelCodeField;
  if (field) {
    const value = adapter.context?.[field];
    if (typeof value === 'string' && value.trim()) {
      return value.trim();
    }
  }
  const contextModelCode = adapter.context?.modelCode;
  if (typeof contextModelCode === 'string' && contextModelCode.trim()) {
    return contextModelCode.trim();
  }
  const triggerModelCode = adapter.context?._flowTriggerModelCode;
  return typeof triggerModelCode === 'string' && triggerModelCode.trim()
    ? triggerModelCode.trim()
    : undefined;
}

function fieldCategoryFromDataType(dataType?: string): ExpressionFieldOption['category'] {
  const normalized = (dataType || '').toLowerCase();
  if (['number', 'integer', 'long', 'decimal', 'double', 'float', 'money'].includes(normalized)) {
    return 'number';
  }
  if (['boolean', 'bool'].includes(normalized)) {
    return 'boolean';
  }
  if (['array', 'list', 'multiselect'].includes(normalized)) {
    return 'array';
  }
  return 'string';
}

function triggerSampleFields(
  context: Record<string, unknown> | undefined,
  st: ReturnType<typeof useSmartText>,
): ExpressionFieldOption[] {
  const sample = firstPlainObject(context?._flowTriggerTestContext, context?.testContext);
  const record = firstPlainObject(
    isPlainObject(sample?.record) ? sample.record.data : undefined,
    sample?.record,
  );
  if (!record) return [];
  return Object.entries(record)
    .filter(([, value]) => value == null || ['string', 'number', 'boolean'].includes(typeof value))
    .map(([key, value]) => ({
      code: `record.${key}`,
      name: sampleFieldName(key, value),
      category: fieldCategoryFromValue(value),
      group: st('$i18n:expression.fieldGroup.triggerSample', '触发样例'),
      insertion: `\${record.${key}}`,
    }));
}

function decisionOutputFields(
  context: Record<string, unknown> | undefined,
  st: ReturnType<typeof useSmartText>,
): ExpressionFieldOption[] {
  const binding = parseRuleBindingValue(context?._flowTriggerRuleBinding ?? context?.ruleBinding);
  const mappings = binding?.decisionBinding?.outputMappings ?? [];
  return mappings
    .map((mapping) => mapping.output)
    .filter((output): output is string => typeof output === 'string' && output.trim().length > 0)
    .map((output) => ({
      code: `decision.outputs.${output}`,
      name: output,
      category: 'string' as const,
      group: st('$i18n:expression.fieldGroup.ruleOutputs', '规则输出'),
      insertion: `\${decision.outputs.${output}}`,
    }));
}

function workflowContextFields(
  context: Record<string, unknown> | undefined,
  st: ReturnType<typeof useSmartText>,
): ExpressionFieldOption[] {
  const fields: ExpressionFieldOption[] = [
    {
      code: 'decision.matched',
      name: st('$i18n:expression.variable.decisionMatched', '规则是否命中'),
      category: 'boolean',
      group: st('$i18n:expression.fieldGroup.decisionRuntime', '规则执行'),
      insertion: '${decision.matched}',
    },
    {
      code: 'decision.status',
      name: st('$i18n:expression.variable.decisionStatus', '规则状态'),
      category: 'string',
      group: st('$i18n:expression.fieldGroup.decisionRuntime', '规则执行'),
      insertion: '${decision.status}',
    },
  ];
  const triggerType = context?._flowTriggerType;
  if (triggerType === 'on_bpm_event') {
    fields.push(
      {
        code: 'processKey',
        name: st('$i18n:expression.variable.processKey', '流程标识'),
        category: 'string',
        group: st('$i18n:expression.fieldGroup.bpmContext', 'BPM 上下文'),
        insertion: '${processKey}',
      },
      {
        code: 'instanceId',
        name: st('$i18n:expression.variable.instanceId', '流程实例'),
        category: 'string',
        group: st('$i18n:expression.fieldGroup.bpmContext', 'BPM 上下文'),
        insertion: '${instanceId}',
      },
      {
        code: 'taskId',
        name: st('$i18n:expression.variable.taskId', '任务 ID'),
        category: 'string',
        group: st('$i18n:expression.fieldGroup.bpmContext', 'BPM 上下文'),
        insertion: '${taskId}',
      },
    );
  }
  return fields;
}

function firstPlainObject(...values: unknown[]): Record<string, unknown> | undefined {
  for (const value of values) {
    if (isPlainObject(value)) {
      return value;
    }
  }
  return undefined;
}

function fieldCategoryFromValue(value: unknown): ExpressionFieldOption['category'] {
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  if (Array.isArray(value)) return 'array';
  return 'string';
}

function sampleFieldName(key: string, value: unknown): string {
  if (value == null || value === '') return key;
  const rendered = String(value);
  return rendered.length > 24 ? `${key} · ${rendered.slice(0, 21)}...` : `${key} · ${rendered}`;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function StaticMultiSelect({
  adapter,
  label,
  placeholder,
  helpText,
  options,
}: {
  adapter: FieldAdapter<unknown>;
  label?: string;
  placeholder?: string;
  helpText?: string;
  options: { label: string; value: string; description?: string }[];
}) {
  const [search, setSearch] = useState('');
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = React.useRef<HTMLDivElement>(null);
  const selectedValues = Array.isArray(adapter.value) ? (adapter.value as string[]) : [];

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
        setSearch('');
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const filteredOptions = search
    ? options.filter(
        (option) =>
          option.label.toLowerCase().includes(search.toLowerCase()) ||
          option.value.toLowerCase().includes(search.toLowerCase()),
      )
    : options;

  const toggleOption = (value: string) => {
    const next = selectedValues.includes(value)
      ? selectedValues.filter((v) => v !== value)
      : [...selectedValues, value];
    adapter.setValue(next);
  };

  return (
    <div>
      {label && (
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {label}
          {adapter.required && <span className="ml-1 text-red-500">*</span>}
        </label>
      )}
      <div ref={containerRef} className="relative" onClick={() => setIsOpen(true)}>
        <div
          className={`flex min-h-[38px] cursor-text flex-wrap items-center gap-1 rounded-md border px-2 py-1 text-sm ${
            isOpen ? 'border-blue-500 ring-2 ring-blue-500' : 'border-gray-300'
          } ${adapter.error ? 'border-red-400' : ''} ${adapter.disabled ? 'bg-gray-50 text-gray-400' : ''}`}
        >
          {selectedValues.map((value) => {
            const option = options.find((o) => o.value === value);
            const display = option?.label || value;
            return (
              <span
                key={value}
                className="inline-flex items-center gap-1 rounded bg-blue-100 px-2 py-0.5 text-xs text-blue-700"
              >
                {display}
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    adapter.setValue(selectedValues.filter((v) => v !== value));
                  }}
                  className="ml-0.5 text-blue-400 hover:text-blue-700 focus:outline-none"
                  aria-label={`Remove ${display}`}
                  disabled={adapter.disabled}
                >
                  x
                </button>
              </span>
            );
          })}
          <input
            type="text"
            value={search}
            disabled={adapter.disabled}
            onChange={(event) => {
              setSearch(event.target.value);
              setIsOpen(true);
            }}
            onFocus={() => setIsOpen(true)}
            placeholder={selectedValues.length === 0 ? placeholder || 'Select...' : ''}
            className="min-w-[80px] flex-1 bg-transparent text-sm outline-none placeholder:text-gray-400 disabled:cursor-not-allowed"
          />
        </div>

        {isOpen && !adapter.disabled && (
          <div className="absolute z-50 mt-1 max-h-48 w-full overflow-y-auto rounded-md border border-gray-200 bg-white shadow-lg">
            {filteredOptions.length === 0 ? (
              <div className="px-3 py-2 text-sm text-gray-400">No results</div>
            ) : (
              filteredOptions.map((option) => {
                const checked = selectedValues.includes(option.value);
                return (
                  <button
                    key={option.value}
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      toggleOption(option.value);
                      setSearch('');
                    }}
                    className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-blue-50 ${
                      checked ? 'bg-blue-50 text-blue-700' : 'text-gray-700'
                    }`}
                  >
                    <span
                      className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border ${
                        checked
                          ? 'border-blue-500 bg-blue-500 text-white'
                          : 'border-gray-300 bg-white'
                      }`}
                    >
                      {checked && <Check className="h-3 w-3" aria-hidden="true" />}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{option.label}</span>
                  </button>
                );
              })
            )}
          </div>
        )}
      </div>
      {adapter.error ? (
        <p className="mt-1 text-xs text-red-500">{adapter.error}</p>
      ) : (
        helpText && <p className="mt-1 text-xs text-gray-500">{helpText}</p>
      )}
    </div>
  );
}

/** Wraps BaseResourceSelect with a label / helpText / field-error chrome. */
function ResourceSelectField({
  adapter,
  label,
  placeholder,
  helpText,
  fetchOptions,
}: {
  adapter: FieldAdapter<unknown>;
  label?: string;
  placeholder: string;
  helpText?: string;
  fetchOptions: () => Promise<{ label: string; value: string }[]>;
}) {
  // Surface the field-level validation error (P0-4 gate): without this, a
  // required resource select (e.g. trigger modelCode) that the save gate flags
  // showed no inline error — only the toolbar count badge — so the user could
  // not tell which field was wrong. Mirror the inline-error chrome of the other
  // field renderers (error text takes precedence over helpText).
  const hasError = !!adapter.error;
  return (
    <div>
      {label && (
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {label}
          {adapter.required && <span className="ml-1 text-red-500">*</span>}
        </label>
      )}
      <div className={hasError ? 'rounded-md ring-1 ring-red-500' : undefined}>
        <BaseResourceSelect
          value={(adapter.value as string) || ''}
          onChange={adapter.setValue as any}
          fetchOptions={fetchOptions}
          placeholder={placeholder}
        />
      </div>
      {hasError ? (
        <p className="mt-1 text-sm text-red-600">{adapter.error}</p>
      ) : (
        helpText && <p className="mt-1 text-xs text-gray-500">{helpText}</p>
      )}
    </div>
  );
}

const RULE_BINDING_DEFAULT_DECISIONS: DecisionOption[] = [
  {
    code: 'approval_routing',
    name: '审批路由',
    outputs: [
      { id: 'severity', label: '审批等级', dataType: 'string' },
      { id: 'candidateGroups', label: '候选组', dataType: 'collection' },
      { id: 'assigneeUserId', label: '审批人', dataType: 'string' },
    ],
  },
  {
    code: 'sla_deadline',
    name: 'SLA 截止时间',
    outputs: [
      { id: 'deadlineMinutes', label: '截止分钟', dataType: 'integer' },
      { id: 'warningBeforeMinutes', label: '提前提醒分钟', dataType: 'integer' },
    ],
  },
  {
    code: 'leave_request_automation',
    name: '请假申请自动化策略',
    outputs: [
      { id: 'route', label: '动作路由', dataType: 'string' },
      { id: 'actions', label: '动作列表', dataType: 'collection' },
      { id: 'severity', label: '规则等级', dataType: 'string' },
    ],
  },
  {
    code: 'complaint_sla_deadline',
    name: '投诉 SLA 截止时间',
    outputs: [
      { id: 'deadlineMinutes', label: '截止分钟', dataType: 'integer' },
      { id: 'warningBeforeMinutes', label: '提前提醒分钟', dataType: 'integer' },
      { id: 'escalationLevel', label: '升级等级', dataType: 'string' },
    ],
  },
];

function parseRuleBindingValue(value: unknown): RuleConsumerBindingDraft | undefined {
  if (!value) return undefined;
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value) as unknown;
      return parseRuleBindingValue(parsed);
    } catch {
      return undefined;
    }
  }
  return typeof value === 'object' && !Array.isArray(value)
    ? (value as RuleConsumerBindingDraft)
    : undefined;
}

function ruleBindingDecisionOptions(
  value: unknown,
  initialDecisionCode?: string,
): DecisionOption[] {
  const decisions = [...RULE_BINDING_DEFAULT_DECISIONS];
  const decisionCode =
    parseRuleBindingValue(value)?.decisionBinding?.decisionCode ?? initialDecisionCode;
  if (decisionCode && !decisions.some((decision) => decision.code === decisionCode)) {
    decisions.push({ code: decisionCode, name: decisionCode });
  }
  return decisions;
}

function resolveRuleBindingModelCode(
  schema: PropertySchema<string>,
  adapter: FieldAdapter<unknown>,
): string | undefined {
  if (schema.ruleBindingFieldCatalogModelCode?.trim()) {
    return schema.ruleBindingFieldCatalogModelCode.trim();
  }
  const field = schema.ruleBindingFieldCatalogModelCodeField;
  if (!field) return undefined;
  const value = adapter.context?.[field];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function resolveRuleBindingConsumerCode(
  schema: PropertySchema<string>,
  adapter: FieldAdapter<unknown>,
): string | undefined {
  if (schema.ruleBindingConsumerCode?.trim()) {
    return schema.ruleBindingConsumerCode.trim();
  }
  const field = schema.ruleBindingConsumerCodeField;
  if (!field) return undefined;
  const value = adapter.context?.[field];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function normalizeRuleBindingInitialContext(
  value: Record<string, unknown>,
): Record<string, unknown> {
  const normalized = Object.fromEntries(
    Object.entries(value).filter(([, scopeValue]) => isPlainObject(scopeValue)),
  );
  const record = normalized.record;
  if (!isPlainObject(record)) {
    return normalized;
  }
  if (isPlainObject(record.data)) {
    return normalized;
  }
  return {
    ...normalized,
    record: {
      data: record,
    },
  };
}

function resolveRuleBindingInitialContextJson(
  schema: PropertySchema<string>,
  adapter: FieldAdapter<unknown>,
): string | undefined {
  if (schema.ruleBindingInitialContextJson?.trim()) {
    return schema.ruleBindingInitialContextJson.trim();
  }
  const field = schema.ruleBindingInitialContextJsonField;
  if (!field) return undefined;
  const value = adapter.context?.[field];
  if (typeof value === 'string' && value.trim()) {
    return value.trim();
  }
  if (isPlainObject(value)) {
    return JSON.stringify(normalizeRuleBindingInitialContext(value), null, 2);
  }
  return undefined;
}

function RuleBindingField({
  adapter,
  label,
  helpText,
  mode,
  consumerType,
  consumerCode,
  consumerCodeField,
  consumerNodeId,
  showImpactPreview,
  showTestRunner,
  initialDecisionCode,
  initialContextJson,
  fieldCatalogModelCode,
}: {
  adapter: FieldAdapter<unknown>;
  label?: string;
  helpText?: string;
  mode: 'condition' | 'decision' | 'combined';
  consumerType?: string;
  consumerCode?: string;
  consumerCodeField?: string;
  consumerNodeId?: string;
  showImpactPreview?: boolean;
  showTestRunner?: boolean;
  initialDecisionCode?: string;
  initialContextJson?: string;
  fieldCatalogModelCode?: string;
}) {
  return (
    <div data-testid="rule-binding-property-field">
      {label && <div className="mb-1 text-sm font-medium text-gray-700">{label}</div>}
      {helpText && <p className="mb-2 text-xs text-gray-500">{helpText}</p>}
      <DecisionRuleBindingBlock
        value={adapter.value as RuleConsumerBindingDraft | string | undefined}
        onChange={(next) => adapter.setValue(next)}
        block={{
          props: {
            mode,
            consumerType,
            consumerCode,
            consumerCodeField,
            consumerNodeId,
            initialDecisionCode,
            decisions: ruleBindingDecisionOptions(adapter.value, initialDecisionCode),
            fieldCatalogMode: 'merge',
            fieldCatalogModelCode,
            showImpactPreview,
            showTestRunner,
            initialContextJson,
          },
        }}
      />
      {adapter.error ? <p className="mt-1 text-xs text-red-600">{adapter.error}</p> : null}
    </div>
  );
}

/** JSON field - handles serialization/deserialization for object values. */
function JsonField({
  adapter,
  name,
  label,
  helpText,
}: {
  adapter: FieldAdapter<unknown>;
  name: string;
  label?: string;
  helpText?: string;
}) {
  const displayValue =
    typeof adapter.value === 'string' ? adapter.value : JSON.stringify(adapter.value, null, 2);

  const jsonAdapter = {
    ...adapter,
    value: displayValue,
    setValue: (val: string) => {
      try {
        adapter.setValue(JSON.parse(val));
      } catch {
        // Keep raw string if not valid JSON
        adapter.setValue(val);
      }
    },
  };

  return (
    <BaseTextarea
      adapter={jsonAdapter as any}
      name={name}
      label={label}
      placeholder="{}"
      helpText={helpText}
      rows={4}
      className="font-mono"
    />
  );
}

// ---------------------------------------------------------------------------
// 'array' helpers
// ---------------------------------------------------------------------------

/** Return a sensible empty default for a given PropertyType. */
function defaultForType(type: string): unknown {
  switch (type) {
    case 'text':
    case 'textarea':
    case 'expression':
    case 'formula':
    case 'json':
      return '';
    case 'number':
      return 0;
    case 'boolean':
      return false;
    case 'multiselect':
    case 'array':
      return [];
    default:
      return undefined;
  }
}

// ---------------------------------------------------------------------------
// 'dict-select' component
// ---------------------------------------------------------------------------

/** Async dict selector — fetches all published dicts on mount and renders a {@code <select>}. */
function DictSelectField({
  adapter,
  label,
  placeholder,
  helpText,
  dictCodeFilter,
}: {
  adapter: FieldAdapter<unknown>;
  label?: string;
  placeholder?: string;
  helpText?: string;
  dictCodeFilter?: string[];
}) {
  const st = useSmartText();
  const [dicts, setDicts] = useState<{ code: string; name: string }[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    dictService
      .findAll()
      .then((all) => {
        if (!alive) return;
        const filtered = dictCodeFilter ? all.filter((d) => dictCodeFilter.includes(d.code)) : all;
        setDicts(filtered as { code: string; name: string }[]);
      })
      .catch((err) => {
        if (!alive) return;
        console.error('[dict-select] load failed', err);
        toast.error(st('$i18n:common.options_load_failed') || 'Failed to load options');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (loading) {
    return (
      <span className="text-sm text-gray-400">{st('$i18n:common.loading') || 'Loading...'}</span>
    );
  }

  return (
    <div>
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}
      <select
        value={(adapter.value as string) ?? ''}
        onChange={(e) => adapter.setValue(e.target.value || undefined)}
        disabled={adapter.disabled}
        className="block w-full rounded border border-gray-300 px-2 py-1.5 text-sm"
      >
        <option value="">{placeholder ?? '—'}</option>
        {dicts.map((d) => (
          <option key={d.code} value={d.code}>
            {d.name}
          </option>
        ))}
      </select>
      {helpText && <p className="mt-1 text-xs text-gray-500">{helpText}</p>}
    </div>
  );
}

export default PropertyFieldRenderer;
