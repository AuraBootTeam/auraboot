/**
 * SchemaBlockConfigPanel
 *
 * Generic schema-driven configuration panel that wraps `PropertyFieldRenderer`
 * with group sectioning and `dependsOn` conditional visibility.
 *
 * Extends the base `PropertySchema.dependsOn` with an optional `anyOf` array
 * for multi-value matching:
 *   { field: 'mode', anyOf: ['a', 'b'] }  // visible if value is 'a' OR 'b'
 *
 * Designers must define PropertySchema[] and delegate rendering here instead
 * of hand-coding JSX panels (Studio hard-rule: Schema-driven).
 */

import React, { useEffect, useRef, useState } from 'react';
import { PropertyFieldRenderer } from '~/shared/designer/PropertyFieldRenderer';
import type { PropertySchema } from '~/shared/designer/types';
import type { FieldAdapter } from '~/ui/field-adapter';
import { cn } from '~/utils/cn';
import { getByPath, setByPath } from './propertyPath';
import { getLocalizedText } from '~/utils/i18n';
import { useI18n } from '~/contexts/I18nContext';

/** Extended PropertySchema supporting `dependsOn.anyOf` for multi-value matching. */
export interface ExtendedPropertySchema<TLabel = string> extends Omit<
  PropertySchema<TLabel>,
  'dependsOn' | 'itemSchema'
> {
  dependsOn?: { field: string; value?: unknown; anyOf?: unknown[] };
  /** Override: itemSchema items are also ExtendedPropertySchema to support anyOf. */
  itemSchema?: ExtendedPropertySchema<TLabel>[];
}

export interface SchemaBlockConfigPanelProps<T extends Record<string, unknown>> {
  schemas: ExtendedPropertySchema<string>[];
  value: T;
  onChange: (next: T, changedKey?: string) => void;
  readonly?: boolean;
  pathMode?: 'flat' | 'nested';
  /** Serialize declared defaults on edit when consumers require complete configuration objects. */
  persistDefaults?: boolean;
  density?: 'comfortable' | 'compact';
  context?: Record<string, unknown>;
  className?: string;
}

export function SchemaBlockConfigPanel<T extends Record<string, unknown>>({
  schemas,
  value,
  onChange,
  readonly,
  pathMode = 'flat',
  persistDefaults = false,
  density = 'comfortable',
  context,
  className,
}: SchemaBlockConfigPanelProps<T>) {
  const { locale, t } = useI18n();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const read = (object: Record<string,unknown>, key: string) => pathMode === 'nested' ? getByPath(object,key) : object[key];
  const effectiveValue: Record<string, unknown> = { ...value };
  for (const schema of schemas) {
    if (read(value, schema.key) === undefined && schema.defaultValue !== undefined) {
      effectiveValue[schema.key] = schema.defaultValue;
    } else effectiveValue[schema.key] = read(value, schema.key);
  }
  const compact = density === 'compact';
  const resolveGroup = (group: unknown): string | undefined => {
    if (group == null) return undefined;
    return getLocalizedText(group as string | Record<string, string>, locale) || undefined;
  };
  const grouped = groupByKey(schemas, resolveGroup);
  const latestValueRef = useRef(value);

  useEffect(() => {
    latestValueRef.current = value;
  }, [value]);

  return (
    <div className={cn('space-y-6', className)}>
      {grouped.map(([groupKey, groupSchemas]) => {
        const visible = groupSchemas.filter((s) => evaluateDependsOn(s.dependsOn, effectiveValue));
        if (visible.length === 0) return null;
        const groupLabel = groupKey ?? 'default';
        const title = groupKey ?? t('schema_config.general', undefined, 'General');
        return (
          <section
            key={groupLabel}
            className={
              compact
                ? 'space-y-3 border-b border-slate-100 pb-4 last:border-0'
                : 'rounded-2xl border border-slate-200 bg-white p-4 shadow-sm'
            }
            data-testid={`schema-config-group-${groupLabel}`}
          >
            <div className="mb-4 flex items-start justify-between gap-4 border-b border-slate-100 pb-3">
              <div>
                <h3 className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
                  {title}
                </h3>
                <p className={compact ? 'hidden' : 'mt-1 text-sm text-slate-500'}>
                  {buildGroupHint(groupKey, visible.length, t)}
                </p>
              </div>
              <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-medium text-slate-500">
                {visible.length}
              </span>
            </div>
            <div className="space-y-3">
              {visible.map((schema) => {
                const adapter: FieldAdapter<unknown> = {
                  value:
                    read(value, schema.key) === undefined
                      ? schema.defaultValue
                      : read(value, schema.key),
                  setValue: (v: unknown) => {
                    if (readonly) return;
                    const invalid =
                      typeof v === 'number' &&
                      (!Number.isFinite(v) ||
                        (schema.min !== undefined && v < schema.min) ||
                        (schema.max !== undefined && v > schema.max));
                    if (invalid) {
                      setErrors((previous) => ({
                        ...previous,
                        [schema.key]: locale.startsWith('zh')
                          ? `请输入 ${schema.min ?? '−∞'} 至 ${schema.max ?? '∞'} 范围内的数值`
                          : `Enter a value between ${schema.min ?? '−∞'} and ${schema.max ?? '∞'}`,
                      }));
                      return;
                    }
                    setErrors((previous) => ({ ...previous, [schema.key]: '' }));
                    let next =
                      pathMode === 'nested'
                        ? setByPath(latestValueRef.current, schema.key, v)
                        : { ...latestValueRef.current, [schema.key]: v };
                    if (persistDefaults) {
                      for (const declared of schemas) {
                        if (read(next, declared.key) !== undefined || declared.defaultValue === undefined) continue;
                        const fallback = structuredClone(declared.defaultValue);
                        next = pathMode === 'nested'
                          ? setByPath(next, declared.key, fallback)
                          : { ...next, [declared.key]: fallback };
                      }
                    }
                    latestValueRef.current = next;
                    onChange(next, schema.key);
                  },
                  disabled: readonly,
                  required: schema.required,
                  context: { ...latestValueRef.current, ...context },
                };
                const rendererSchema = toRendererSchema(schema);
                return (
                  <div
                    key={schema.key}
                    className={
                      compact
                        ? 'min-w-0 space-y-1'
                        : 'rounded-2xl border border-slate-200 bg-slate-50/70 p-4'
                    }
                    data-testid={`schema-config-field-${schema.key}`}
                  >
                    {!compact && (
                      <div className="mb-3 flex items-center justify-between gap-3">
                        <div className="min-w-0">
                          <div className="text-sm font-medium text-slate-800">
                            {getLocalizedText(
                              schema.label as string | Record<string, string>,
                              locale,
                            )}
                          </div>
                          {schema.description ? (
                            <div className="mt-1 text-xs leading-5 text-slate-500">
                              {getLocalizedText(
                                schema.description as string | Record<string, string>,
                                locale,
                              )}
                            </div>
                          ) : null}
                        </div>
                      </div>
                    )}
                    <PropertyFieldRenderer
                      schema={rendererSchema as PropertySchema<string>}
                      adapter={adapter}
                    />
                    {errors[schema.key] ? (
                      <p role="alert" className="text-xs text-red-600">
                        {errors[schema.key]}
                      </p>
                    ) : null}
                  </div>
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
}

function evaluateDependsOn(
  dep: { field: string; value?: unknown; anyOf?: unknown[] } | undefined,
  value: Record<string, unknown>,
): boolean {
  if (!dep) return true;
  const actual = value[dep.field];
  if (dep.anyOf && Array.isArray(dep.anyOf)) return dep.anyOf.includes(actual);
  if (Object.prototype.hasOwnProperty.call(dep, 'value')) return actual === dep.value;
  // Only field specified (no value / no anyOf) → require truthy
  return !!actual;
}

function groupByKey<TLabel>(
  schemas: ExtendedPropertySchema<TLabel>[],
  resolveGroup: (group: TLabel | undefined) => string | undefined,
): [string | undefined, ExtendedPropertySchema<TLabel>[]][] {
  const map = new Map<string | undefined, ExtendedPropertySchema<TLabel>[]>();
  for (const s of schemas) {
    const key = resolveGroup(s.group);
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push(s);
  }
  return Array.from(map.entries());
}

function toRendererSchema<TLabel>(schema: ExtendedPropertySchema<TLabel>): PropertySchema<TLabel> {
  // If dependsOn has anyOf, strip it (already evaluated at panel level).
  // PropertyFieldRenderer's PropertySchema type only understands { field, value }.
  if (schema.dependsOn?.anyOf) {
    const { dependsOn: _dep, ...rest } = schema;
    return rest as unknown as PropertySchema<TLabel>;
  }
  return schema as unknown as PropertySchema<TLabel>;
}

function buildGroupHint(
  groupKey: string | undefined,
  visibleCount: number,
  t: ReturnType<typeof useI18n>['t'],
): string {
  if (!groupKey) {
    return t(
      'schema_config.count_hint',
      { count: visibleCount },
      'This group has ' + visibleCount + ' configurable settings.',
    );
  }
  const normalized = groupKey.toLowerCase();
  if (normalized.includes('size') || normalized.includes('尺寸')) {
    return t(
      'schema_config.size_hint',
      undefined,
      'Set width, spacing and alignment to make the table easier to scan.',
    );
  }
  if (normalized.includes('display') || normalized.includes('显示')) {
    return t(
      'schema_config.display_hint',
      undefined,
      'Choose how fields appear, keeping them recognizable and readable.',
    );
  }
  if (normalized.includes('condition') || normalized.includes('条件')) {
    return t(
      'schema_config.condition_hint',
      undefined,
      'Define filter logic, default values and matching rules.',
    );
  }
  if (normalized.includes('appearance') || normalized.includes('外观')) {
    return t(
      'schema_config.appearance_hint',
      undefined,
      'Set where filters appear and how much space they use.',
    );
  }
  if (normalized.includes('binding') || normalized.includes('绑定')) {
    return t(
      'schema_config.binding_hint',
      undefined,
      'Bind the configuration to explicit actions and interaction rules.',
    );
  }
  if (normalized.includes('sort') || normalized.includes('排序')) {
    return t(
      'schema_config.sort_hint',
      undefined,
      'Choose which records appear first when the page opens.',
    );
  }
  if (normalized.includes('pagination') || normalized.includes('分页')) {
    return t(
      'schema_config.pagination_hint',
      undefined,
      'Balance list density, browsing effort and the first view.',
    );
  }
  if (normalized.includes('interaction') || normalized.includes('交互')) {
    return t(
      'schema_config.interaction_hint',
      undefined,
      'Define the actions users can perform directly in the list.',
    );
  }
  if (normalized.includes('basic') || normalized.includes('基础')) {
    return t(
      'schema_config.basic_hint',
      undefined,
      'Start with essential information and labels, then add other capabilities.',
    );
  }
  return t(
    'schema_config.count_hint',
    { count: visibleCount },
    'This group has ' + visibleCount + ' configurable settings.',
  );
}
