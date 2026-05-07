import React, { useCallback } from 'react';
import { PropertyFieldRenderer } from './PropertyFieldRenderer';
import type { PropertySchema } from './types';
import { useLocalizedText } from '~/utils/i18n';

export interface ArrayItemEditorProps<T extends Record<string, unknown>> {
  itemSchema: PropertySchema<any>[];
  value: T;
  onChange: (next: T) => void;
  onRemove: () => void;
  itemLabel: string;
  readonly?: boolean;
}

/**
 * Render one item of an `'array'` PropertySchema field. Each item field is
 * delegated to PropertyFieldRenderer so dependsOn within the same item
 * (sibling-field reference) works naturally.
 *
 * Cross-item / cross-panel dependsOn is intentionally NOT supported.
 */
export function ArrayItemEditor<T extends Record<string, unknown>>({
  itemSchema,
  value,
  onChange,
  onRemove,
  itemLabel,
  readonly,
}: ArrayItemEditorProps<T>) {
  const lt = useLocalizedText();

  const updateField = useCallback(
    (key: string, next: unknown) => {
      onChange({ ...value, [key]: next } as T);
    },
    [value, onChange],
  );

  // Resolve sibling-field dependsOn within this item.
  const isVisible = useCallback(
    (schema: PropertySchema<any>) => {
      const dep = (schema as any).dependsOn as
        | { field: string; value?: unknown; anyOf?: unknown[] }
        | undefined;
      if (!dep) return true;
      const sibling = (value as any)[dep.field];
      if (dep.anyOf) return dep.anyOf.includes(sibling);
      return sibling === dep.value;
    },
    [value],
  );

  return (
    <div className="rounded-lg border border-gray-200 p-3">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-sm font-medium text-gray-700">{itemLabel}</span>
        <button
          type="button"
          onClick={onRemove}
          disabled={readonly}
          aria-label="remove"
          className="text-sm text-red-600 hover:text-red-700 disabled:opacity-50"
        >
          删除
        </button>
      </div>
      <div className="space-y-2">
        {itemSchema.filter(isVisible).map((field) => {
          const adapter = {
            value: (value as any)[field.key],
            setValue: (next: unknown) => updateField(field.key, next),
            error: undefined,
            required: field.required,
            disabled: readonly,
          };
          return (
            <div key={field.key}>
              <label className="mb-0.5 block text-xs text-gray-600">
                {typeof field.label === 'string' ? field.label : lt(field.label as any)}
              </label>
              <PropertyFieldRenderer
                schema={field as PropertySchema<string>}
                adapter={adapter}
              />
            </div>
          );
        })}
      </div>
    </div>
  );
}
