/**
 * DashboardFieldAdapter - Bridges Dashboard Zustand store with FieldAdapter interface.
 *
 * Similar to FlowFieldAdapter for Flow Designer, this hook enables base-field components
 * to read/write widget configuration data through the unified FieldAdapter interface.
 */

import { useCallback, useMemo } from 'react';
import type { FieldAdapter } from '~/components/field-adapter';
import { useDashboardStore } from '../store/useDashboardStore';

export interface DashboardFieldAdapterProps {
  /** Field key (supports dot notation for nested access, e.g. 'style.color') */
  fieldKey: string;
  /** Widget ID (defaults to selected widget) */
  widgetId?: string;
  /** Whether the field is required */
  required?: boolean;
  /** Whether the field is disabled */
  disabled?: boolean;
  /** Whether the field is read-only */
  readOnly?: boolean;
}

/**
 * Get nested value from object using dot notation
 */
function getNestedValue(obj: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce((acc: unknown, part) => {
    if (acc && typeof acc === 'object') {
      return (acc as Record<string, unknown>)[part];
    }
    return undefined;
  }, obj);
}

/**
 * Set nested value in object using dot notation (immutable)
 */
function setNestedValue(
  obj: Record<string, unknown>,
  path: string,
  value: unknown,
): Record<string, unknown> {
  const parts = path.split('.');
  const result = { ...obj };
  let current: Record<string, unknown> = result;

  for (let i = 0; i < parts.length - 1; i++) {
    const part = parts[i];
    if (!current[part] || typeof current[part] !== 'object') {
      current[part] = {};
    }
    current[part] = { ...(current[part] as Record<string, unknown>) };
    current = current[part] as Record<string, unknown>;
  }

  current[parts[parts.length - 1]] = value;
  return result;
}

export function useDashboardFieldAdapter<T = unknown>(
  props: DashboardFieldAdapterProps,
): FieldAdapter<T> {
  const { fieldKey, widgetId, required, disabled, readOnly } = props;
  const { selectedWidgetId, getWidgetById, updateWidgetConfig } = useDashboardStore();

  const targetWidgetId = widgetId || selectedWidgetId;
  const widget = targetWidgetId ? getWidgetById(targetWidgetId) : undefined;

  const value = widget
    ? (getNestedValue(widget.config as unknown as Record<string, unknown>, fieldKey) as T)
    : (undefined as unknown as T);

  const setValue = useCallback(
    (newValue: T) => {
      if (!targetWidgetId || !widget) return;
      const updatedConfig = setNestedValue(
        widget.config as unknown as Record<string, unknown>,
        fieldKey,
        newValue,
      );
      updateWidgetConfig(targetWidgetId, updatedConfig as any);
    },
    [targetWidgetId, widget, fieldKey, updateWidgetConfig],
  );

  const adapter: FieldAdapter<T> = useMemo(
    () => ({
      value,
      setValue,
      disabled,
      required,
      readOnly,
    }),
    [value, setValue, disabled, required, readOnly],
  );

  return adapter;
}

export { getNestedValue, setNestedValue };
