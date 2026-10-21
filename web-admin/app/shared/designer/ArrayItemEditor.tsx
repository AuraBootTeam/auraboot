import React, { useCallback, useId, useState, useEffect } from 'react';
import { PropertyFieldRenderer } from './PropertyFieldRenderer';
import type { PropertySchema } from './types';
import { useI18n } from '~/contexts/I18nContext';

export interface ArrayItemEditorProps<T extends Record<string, unknown>> {
  itemSchema: PropertySchema<any>[];
  value: T;
  onChange: (next: T) => void;
  onRemove: () => void;
  itemLabel: string;
  readonly?: boolean;
  onMove?: (direction: 'up' | 'down') => void;
  first?: boolean;
  last?: boolean;
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
  onMove,
  first,
  last,
}: ArrayItemEditorProps<T>) {
  const itemId = useId();
  const { locale } = useI18n();
  const [errors, setErrors] = useState<Record<string, string>>({});
  useEffect(() => setErrors({}), [value]);

  const updateField = useCallback(
    (key: string, next: unknown) => {
      if (readonly) return;
      const field = itemSchema.find((field) => field.key === key);
      if (
        typeof next === 'number' &&
        (!Number.isFinite(next) ||
          (field?.min !== undefined && next < field.min) ||
          (field?.max !== undefined && next > field.max))
      ) {
        const zh = locale.startsWith('zh');
        const message = !Number.isFinite(next)
          ? (zh ? '请输入有效数值' : 'Enter a finite number')
          : field?.min !== undefined && next < field.min
            ? (zh ? `数值不能小于 ${field.min}` : `Value must be at least ${field.min}`)
            : (zh ? `数值不能大于 ${field?.max}` : `Value must be at most ${field?.max}`);
        setErrors((previous) => ({ ...previous, [key]: message }));
        return;
      }
      setErrors((previous) => {
        const nextErrors = { ...previous };
        delete nextErrors[key];
        return nextErrors;
      });
      onChange({ ...value, [key]: next } as T);
    },
    [value, onChange, itemSchema, readonly, locale],
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
    <div role="group" aria-label={itemLabel} className="rounded-lg border border-gray-200 p-3">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-sm font-medium text-gray-700">{itemLabel}</span>
        <div className="flex items-center gap-2">
        {onMove && <>
          <button type="button" disabled={readonly || first} onClick={()=>onMove('up')}
            aria-label={locale.startsWith('zh') ? '上移' : 'Move up'}
            className="text-sm text-blue-600 disabled:opacity-40">↑</button>
          <button type="button" disabled={readonly || last} onClick={()=>onMove('down')}
            aria-label={locale.startsWith('zh') ? '下移' : 'Move down'}
            className="text-sm text-blue-600 disabled:opacity-40">↓</button>
        </>}
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
              <PropertyFieldRenderer
                schema={field as PropertySchema<string>}
                fieldId={`${itemId}-${field.key}`}
                adapter={adapter}
              />
              {errors[field.key] && (
                <p role="alert" className="mt-1 text-xs text-red-600">
                  {errors[field.key]}
                </p>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
