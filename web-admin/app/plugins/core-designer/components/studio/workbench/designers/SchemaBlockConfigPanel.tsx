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

import React, { useMemo } from 'react';
import { PropertyFieldRenderer } from '~/shared/designer/PropertyFieldRenderer';
import type { PropertySchema } from '~/shared/designer/types';
import type { FieldAdapter } from '~/ui/field-adapter';

/** Extended PropertySchema supporting `dependsOn.anyOf` for multi-value matching. */
export interface ExtendedPropertySchema<TLabel = string>
  extends Omit<PropertySchema<TLabel>, 'dependsOn'> {
  dependsOn?: { field: string; value?: unknown; anyOf?: unknown[] };
}

export interface SchemaBlockConfigPanelProps<T extends Record<string, unknown>> {
  schemas: ExtendedPropertySchema<string>[];
  value: T;
  onChange: (next: T) => void;
  readonly?: boolean;
}

export function SchemaBlockConfigPanel<T extends Record<string, unknown>>({
  schemas,
  value,
  onChange,
  readonly,
}: SchemaBlockConfigPanelProps<T>) {
  const grouped = useMemo(() => groupByKey(schemas), [schemas]);

  return (
    <div className="space-y-6">
      {grouped.map(([groupKey, groupSchemas]) => {
        const visible = groupSchemas.filter((s) => evaluateDependsOn(s.dependsOn, value));
        if (visible.length === 0) return null;
        const groupLabel = groupKey ?? 'default';
        return (
          <section key={groupLabel} data-testid={`schema-config-group-${groupLabel}`}>
            {groupKey && (
              <h3 className="mb-3 text-xs font-semibold uppercase tracking-wide text-gray-500">
                {groupKey}
              </h3>
            )}
            <div className="space-y-3">
              {visible.map((schema) => {
                const adapter: FieldAdapter<unknown> = {
                  value: value[schema.key],
                  setValue: (v: unknown) => onChange({ ...value, [schema.key]: v }),
                  disabled: readonly,
                  required: schema.required,
                };
                const rendererSchema = toRendererSchema(schema);
                return (
                  <div key={schema.key}>
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
): [string | undefined, ExtendedPropertySchema<TLabel>[]][] {
  const map = new Map<string | undefined, ExtendedPropertySchema<TLabel>[]>();
  for (const s of schemas) {
    const key = s.group;
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
