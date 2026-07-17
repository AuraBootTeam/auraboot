/**
 * TimeGrainPicker
 *
 * Bucket a date/datetime dimension by a time grain. Aggregate dimensions are bare
 * columns, so "opportunities per month" was impossible from the designer — it took a
 * hand-written namedQuery with DATE_TRUNC. The backend now accepts a `col__grain`
 * dimension suffix; this is the UI for it.
 *
 * Emits a dimension string of the form `field__grain` (e.g.
 * `crm_opp_expected_close_date__month`), which the caller merges into `dimensions`.
 */

import React from 'react';
import type { FieldOption } from './types';

/** Grains the backend accepts (mirrors ALLOWED_GRAINS server-side). */
export const TIME_GRAINS = [
  { value: 'day', label: '按天' },
  { value: 'week', label: '按周' },
  { value: 'month', label: '按月' },
  { value: 'quarter', label: '按季度' },
  { value: 'year', label: '按年' },
] as const;

/** Field types that can be time-bucketed. */
const DATE_FIELD_TYPES = new Set(['date', 'datetime', 'timestamp']);

export function isDateField(field: FieldOption): boolean {
  return DATE_FIELD_TYPES.has((field.fieldType || '').toLowerCase());
}

/** Split a dimension into its base column and grain, if it carries a `__grain` suffix. */
export function parseGrainDimension(dimension: string): { field: string; grain: string | null } {
  const sep = dimension.indexOf('__');
  if (sep < 0) return { field: dimension, grain: null };
  return { field: dimension.slice(0, sep), grain: dimension.slice(sep + 2) };
}

export interface TimeGrainPickerProps {
  /** Date/datetime fields available on the model. */
  dateFields: FieldOption[];
  /** Currently selected field (base column) and grain, if any. */
  field: string;
  grain: string;
  onChange: (field: string, grain: string) => void;
  label?: string;
}

export const TimeGrainPicker: React.FC<TimeGrainPickerProps> = ({
  dateFields,
  field,
  grain,
  onChange,
  label,
}) => {
  if (dateFields.length === 0) return null;

  return (
    <div data-testid="time-grain-picker">
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}
      <div className="flex items-center gap-2">
        <select
          data-testid="grain-field"
          value={field}
          onChange={(e) => onChange(e.target.value, e.target.value ? grain || 'month' : '')}
          className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">不分桶</option>
          {dateFields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
        <select
          data-testid="grain-value"
          value={grain}
          disabled={!field}
          onChange={(e) => onChange(field, e.target.value)}
          className="rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:bg-gray-100"
        >
          {TIME_GRAINS.map((g) => (
            <option key={g.value} value={g.value}>
              {g.label}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
};

export default TimeGrainPicker;
