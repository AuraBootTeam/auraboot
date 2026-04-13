/**
 * CalendarConfigPanel Component
 *
 * Configuration panel for creating/editing CALENDAR view settings.
 * Allows selecting dateField, titleField, endDateField, colorField, and default view.
 */

import React, { useCallback } from 'react';
import type { ViewConfig } from '~/framework/smart/types/savedView';
import type { FieldOption } from './KanbanConfigPanel';
import { cn } from '~/utils/cn';

/**
 * Props for CalendarConfigPanel component
 */
export interface CalendarConfigPanelProps {
  /** Current view configuration */
  viewConfig: ViewConfig;
  /** Callback when configuration changes */
  onChange: (config: ViewConfig) => void;
  /** Available model fields for selection */
  fields: FieldOption[];
  /** Custom CSS class */
  className?: string;
}

const CALENDAR_VIEW_OPTIONS = [
  { value: 'dayGridMonth', label: 'Month' },
  { value: 'timeGridWeek', label: 'Week' },
  { value: 'listWeek', label: 'List' },
] as const;

/**
 * CalendarConfigPanel - Configuration UI for calendar view settings
 */
export const CalendarConfigPanel: React.FC<CalendarConfigPanelProps> = ({
  viewConfig,
  onChange,
  fields,
  className,
}) => {
  const updateConfig = useCallback(
    (partial: Partial<ViewConfig>) => {
      onChange({ ...viewConfig, ...partial });
    },
    [viewConfig, onChange],
  );

  // Filter date-type fields
  const dateFields = fields.filter((f) =>
    ['date', 'datetime', 'timestamp', 'date', 'datetime'].includes(f.dataType),
  );

  const selectClassName =
    'w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500';

  return (
    <div className={cn('space-y-5', className)}>
      {/* Date Field (required) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          Date Field <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.calendarDateField || ''}
          onChange={(e) => updateConfig({ calendarDateField: e.target.value })}
          className={selectClassName}
        >
          <option value="">Select date field...</option>
          {dateFields.length > 0
            ? dateFields.map((f) => (
                <option key={f.code} value={f.code}>
                  {f.name} ({f.dataType})
                </option>
              ))
            : // Fallback: show all fields if no date fields detected
              fields.map((f) => (
                <option key={f.code} value={f.code}>
                  {f.name} ({f.dataType})
                </option>
              ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">
          Events will be placed on the calendar by this date field.
        </p>
      </div>

      {/* Title Field */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Title Field</label>
        <select
          value={viewConfig.calendarTitleField || ''}
          onChange={(e) => updateConfig({ calendarTitleField: e.target.value || undefined })}
          className={selectClassName}
        >
          <option value="">Default (name)</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
      </div>

      {/* End Date Field (optional) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">End Date Field</label>
        <select
          value={viewConfig.calendarEndDateField || ''}
          onChange={(e) => updateConfig({ calendarEndDateField: e.target.value || undefined })}
          className={selectClassName}
        >
          <option value="">None (single-day events)</option>
          {dateFields.length > 0
            ? dateFields.map((f) => (
                <option key={f.code} value={f.code}>
                  {f.name} ({f.dataType})
                </option>
              ))
            : fields.map((f) => (
                <option key={f.code} value={f.code}>
                  {f.name} ({f.dataType})
                </option>
              ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">
          For multi-day events, select a field for the end date.
        </p>
      </div>

      {/* Color Field (optional) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Color By Field</label>
        <select
          value={viewConfig.calendarColorField || ''}
          onChange={(e) => updateConfig({ calendarColorField: e.target.value || undefined })}
          className={selectClassName}
        >
          <option value="">Default color</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">
          Events will be color-coded by distinct values of this field.
        </p>
      </div>

      {/* Default View */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Default View</label>
        <div className="flex gap-2">
          {CALENDAR_VIEW_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={() => updateConfig({ calendarDefaultView: opt.value })}
              className={cn(
                'flex-1 rounded-md border px-3 py-2 text-sm transition-colors duration-100',
                (viewConfig.calendarDefaultView || 'dayGridMonth') === opt.value
                  ? 'border-blue-500 bg-blue-50 font-medium text-blue-700'
                  : 'border-gray-300 bg-white text-gray-600 hover:bg-gray-50',
              )}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};

export default CalendarConfigPanel;
