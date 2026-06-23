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
  { value: 'dayGridMonth', label: '月' },
  { value: 'timeGridWeek', label: '周' },
  { value: 'listWeek', label: '列表' },
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
          日期字段 <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.calendarDateField || ''}
          onChange={(e) => updateConfig({ calendarDateField: e.target.value })}
          className={selectClassName}
        >
          <option value="">选择日期字段...</option>
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
          事件会根据这个日期字段显示在日历中。
        </p>
      </div>

      {/* Title Field */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">标题字段</label>
        <select
          value={viewConfig.calendarTitleField || ''}
          onChange={(e) => updateConfig({ calendarTitleField: e.target.value || undefined })}
          className={selectClassName}
        >
          <option value="">默认名称字段</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
      </div>

      {/* End Date Field (optional) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">结束日期字段</label>
        <select
          value={viewConfig.calendarEndDateField || ''}
          onChange={(e) => updateConfig({ calendarEndDateField: e.target.value || undefined })}
          className={selectClassName}
        >
          <option value="">不设置（单日事件）</option>
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
          多日事件可选择结束日期字段。
        </p>
      </div>

      {/* Color Field (optional) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">颜色分组字段</label>
        <select
          value={viewConfig.calendarColorField || ''}
          onChange={(e) => updateConfig({ calendarColorField: e.target.value || undefined })}
          className={selectClassName}
        >
          <option value="">默认颜色</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">
          事件会按该字段的不同取值显示颜色。
        </p>
      </div>

      {/* Default View */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">默认视图</label>
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
