/**
 * SchemaBlockConfigPanel — Generic schema-driven block configuration panel.
 *
 * Looks up a PropertySchema[] registry by block.blockType, groups fields by
 * their `group` property, evaluates `dependsOn` conditional visibility, and
 * renders each field through PropertyFieldRenderer via a dot-path FieldAdapter.
 *
 * Block config schemas are defined in ./block-schemas/ and registered via
 * BLOCK_CONFIG_SCHEMAS. Blocks without a schema show the
 * "No configuration available" empty state.
 */

import React, { useCallback, useMemo } from 'react';
import type { PropertySchema } from '~/shared/designer/types';
import { PropertyFieldRenderer } from '~/shared/designer/PropertyFieldRenderer';
import type { FieldAdapter } from '~/ui/field-adapter';
import type { CanvasBlock } from '~/plugins/core-designer/components/studio/domain/canvas/types';
import { BlockRegistry } from '~/plugins/core-designer/components/studio/registry';

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export interface SchemaBlockConfigPanelProps {
  block: CanvasBlock;
  onUpdate: (patch: Partial<CanvasBlock>) => void;
}

export const SchemaBlockConfigPanel: React.FC<SchemaBlockConfigPanelProps> = ({
  block,
  onUpdate,
}) => {
  const schemas = BlockRegistry.getSchema(block.blockType);

  if (!schemas || schemas.length === 0) {
    return (
      <div style={{ padding: 16, color: '#9ca3af', fontSize: 12 }}>
        No configuration available for <strong>{block.blockType}</strong>
      </div>
    );
  }

  // Group schemas by their `group` field (defaults to "General")
  const groups = useMemo(() => {
    const map = new Map<string, PropertySchema<string>[]>();
    for (const s of schemas) {
      const g = s.group ?? 'General';
      if (!map.has(g)) map.set(g, []);
      map.get(g)!.push(s);
    }
    return map;
  }, [schemas]);

  // Build a FieldAdapter that reads/writes block.config using dot-path notation
  const createAdapter = useCallback(
    (schema: PropertySchema<string>): FieldAdapter<unknown> => ({
      value: getNestedValue(block.config, schema.key) ?? schema.defaultValue,
      setValue: (value: unknown) => {
        const newConfig = setNestedValue({ ...block.config }, schema.key, value);
        onUpdate({ config: newConfig });
      },
      required: schema.required,
    }),
    [block.config, onUpdate],
  );

  // Evaluate dependsOn conditional visibility
  const isVisible = useCallback(
    (schema: PropertySchema<string>) => {
      if (!schema.dependsOn) return true;
      let depValue = getNestedValue(block.config, schema.dependsOn.field);
      // Fall back to the controlling field's defaultValue when config is empty
      // (e.g. a newly added block whose config is still {})
      if (depValue === undefined) {
        const controllingSchema = schemas.find((s) => s.key === schema.dependsOn!.field);
        if (controllingSchema?.defaultValue !== undefined) {
          depValue = controllingSchema.defaultValue;
        }
      }
      if (schema.dependsOn.value !== undefined) {
        // Support array values: match if depValue is included in the array
        if (Array.isArray(schema.dependsOn.value)) {
          return schema.dependsOn.value.includes(depValue);
        }
        return depValue === schema.dependsOn.value;
      }
      return Boolean(depValue);
    },
    [block.config, schemas],
  );

  return (
    <div data-testid={`${block.blockType}-schema-config`}>
      {Array.from(groups.entries()).map(([groupName, fields]) => {
        const visibleFields = fields.filter(isVisible);
        if (visibleFields.length === 0) return null;

        return (
          <div
            key={groupName}
            style={{ marginBottom: 16, paddingBottom: 16, borderBottom: '1px solid #f3f4f6' }}
          >
            {/* Group header */}
            <div
              style={{
                fontSize: 10,
                color: '#9ca3af',
                fontWeight: 700,
                textTransform: 'uppercase' as const,
                letterSpacing: 0.8,
                marginBottom: 10,
              }}
            >
              {groupName}
            </div>

            {/* Fields */}
            {visibleFields.map((schema) => (
              <div key={schema.key} style={{ marginBottom: 8 }}>
                <PropertyFieldRenderer schema={schema} adapter={createAdapter(schema)} />
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
};

// ---------------------------------------------------------------------------
// Utility: dot-path nested value access / mutation
// ---------------------------------------------------------------------------

/** Read a value at a dot-separated path from an object. */
function getNestedValue(obj: Record<string, unknown>, path: string): unknown {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return path.split('.').reduce((acc: any, key) => acc?.[key], obj);
}

/**
 * Return a new object with the value at `path` set to `value`.
 * Supports multi-level paths like "features.search".
 */
function setNestedValue(
  obj: Record<string, unknown>,
  path: string,
  value: unknown,
): Record<string, unknown> {
  const keys = path.split('.');
  if (keys.length === 1) {
    return { ...obj, [keys[0]]: value };
  }
  const [first, ...rest] = keys;
  const nested = (obj[first] as Record<string, unknown>) ?? {};
  return { ...obj, [first]: setNestedValue({ ...nested }, rest.join('.'), value) };
}
