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

import React, { useEffect, useMemo, useRef } from 'react';
import { PropertyFieldRenderer } from '~/shared/designer/PropertyFieldRenderer';
import type { PropertySchema } from '~/shared/designer/types';
import type { FieldAdapter } from '~/ui/field-adapter';
import { cn } from '~/utils/cn';
import { getLocalizedText } from '~/utils/i18n';
import { useI18n } from '~/contexts/I18nContext';

/** Extended PropertySchema supporting `dependsOn.anyOf` for multi-value matching. */
export interface ExtendedPropertySchema<TLabel = string>
  extends Omit<PropertySchema<TLabel>, 'dependsOn' | 'itemSchema'> {
  dependsOn?: { field: string; value?: unknown; anyOf?: unknown[] };
  /** Override: itemSchema items are also ExtendedPropertySchema to support anyOf. */
  itemSchema?: ExtendedPropertySchema<TLabel>[];
}

export interface SchemaBlockConfigPanelProps<T extends Record<string, unknown>> {
  schemas: ExtendedPropertySchema<string>[];
  value: T;
  onChange: (next: T, changedKey?: string) => void;
  readonly?: boolean;
  className?: string;
}

export function SchemaBlockConfigPanel<T extends Record<string, unknown>>({
  schemas,
  value,
  onChange,
  readonly,
  className,
}: SchemaBlockConfigPanelProps<T>) {
  const { locale } = useI18n();
  const resolveGroup = (group: unknown): string | undefined => {
    if (group == null) return undefined;
    return getLocalizedText(group as string | Record<string, string>, locale) || undefined;
  };
  const grouped = useMemo(() => groupByKey(schemas, resolveGroup), [schemas, locale]);
  const latestValueRef = useRef(value);

  useEffect(() => {
    latestValueRef.current = value;
  }, [value]);

  return (
    <div className={cn('space-y-6', className)}>
      {grouped.map(([groupKey, groupSchemas]) => {
        const visible = groupSchemas.filter((s) => evaluateDependsOn(s.dependsOn, value));
        if (visible.length === 0) return null;
        const groupLabel = groupKey ?? 'default';
        const title = groupKey ?? 'General';
        return (
          <section
            key={groupLabel}
            className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm"
            data-testid={`schema-config-group-${groupLabel}`}
          >
            <div className="mb-4 flex items-start justify-between gap-4 border-b border-slate-100 pb-3">
              <div>
                <h3 className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
                  {title}
                </h3>
                <p className="mt-1 text-sm text-slate-500">
                  {buildGroupHint(groupKey, visible.length)}
                </p>
              </div>
              <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-medium text-slate-500">
                {visible.length}
              </span>
            </div>
            <div className="space-y-3">
              {visible.map((schema) => {
                const adapter: FieldAdapter<unknown> = {
                  value: value[schema.key],
                  setValue: (v: unknown) => {
                    const next = { ...latestValueRef.current, [schema.key]: v };
                    latestValueRef.current = next;
                    onChange(next, schema.key);
                  },
                  disabled: readonly,
                  required: schema.required,
                };
                const rendererSchema = toRendererSchema(schema);
                return (
                  <div
                    key={schema.key}
                    className="rounded-2xl border border-slate-200 bg-slate-50/70 p-4"
                    data-testid={`schema-config-field-${schema.key}`}
                  >
                    <div className="mb-3 flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <div className="text-sm font-medium text-slate-800">
                          {schema.label}
                        </div>
                        {schema.description ? (
                          <div className="mt-1 text-xs leading-5 text-slate-500">
                            {schema.description}
                          </div>
                        ) : null}
                      </div>
                      <span className="rounded-full bg-white px-2.5 py-1 text-[11px] font-medium text-slate-400">
                        {schema.type}
                      </span>
                    </div>
                    <PropertyFieldRenderer
                      schema={rendererSchema as PropertySchema<string>}
                      adapter={adapter}
                    />
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

function toRendererSchema<TLabel>(
  schema: ExtendedPropertySchema<TLabel>,
): PropertySchema<TLabel> {
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
): string {
  if (!groupKey) {
    return `本组包含 ${visibleCount} 个可配置项`;
  }
  const normalized = groupKey.toLowerCase();
  if (normalized.includes('size') || normalized.includes('尺寸')) {
    return '控制宽度、间距和对齐方式，让表格更易扫读。';
  }
  if (normalized.includes('display') || normalized.includes('显示')) {
    return '决定字段如何被呈现，优先保证识别效率和阅读稳定性。';
  }
  if (normalized.includes('condition') || normalized.includes('条件')) {
    return '定义筛选逻辑、默认值和匹配方式。';
  }
  if (normalized.includes('appearance') || normalized.includes('外观')) {
    return '控制筛选项在页面中的出现位置和占用空间。';
  }
  if (normalized.includes('binding') || normalized.includes('绑定')) {
    return '把当前配置绑定到明确的动作和交互规则上。';
  }
  if (normalized.includes('sort') || normalized.includes('排序')) {
    return '决定用户进入页面后最先看到的排序结果。';
  }
  if (normalized.includes('pagination') || normalized.includes('分页')) {
    return '平衡列表密度、浏览成本和首屏压力。';
  }
  if (normalized.includes('interaction') || normalized.includes('交互')) {
    return '定义用户可以直接在列表面上完成什么操作。';
  }
  if (normalized.includes('basic') || normalized.includes('基础')) {
    return '先定义最核心的信息和文案，再补充附加能力。';
  }
  return `本组包含 ${visibleCount} 个可配置项`;
}
