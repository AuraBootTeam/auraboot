/**
 * GanttConfigPanel Component
 *
 * Configuration panel for GANTT view type.
 * Allows users to select fields for start date, end date, title, progress, and dependencies.
 */

import React, { useCallback, useMemo } from 'react';
import type { ViewConfig } from '~/framework/smart/types/savedView';
import type { FieldOption } from './KanbanConfigPanel';
import { cn } from '~/utils/cn';

export interface GanttConfigPanelProps {
  /** Current view configuration */
  viewConfig: ViewConfig;
  /** Callback when configuration changes */
  onChange: (config: ViewConfig) => void;
  /** Available model fields */
  fields: FieldOption[];
}

/**
 * GanttConfigPanel - Configuration panel for Gantt view
 */
export const GanttConfigPanel: React.FC<GanttConfigPanelProps> = ({
  viewConfig,
  onChange,
  fields,
}) => {
  const handleFieldChange = useCallback(
    (key: string, value: string) => {
      onChange({ ...viewConfig, [key]: value || undefined });
    },
    [viewConfig, onChange],
  );

  // Filter date-type fields
  const dateFields = useMemo(
    () =>
      fields.filter(
        (f) =>
          f.dataType === 'date' ||
          f.dataType === 'datetime' ||
          f.dataType === 'date' ||
          f.dataType === 'datetime' ||
          f.code?.toLowerCase().includes('date') ||
          f.code?.toLowerCase().includes('time'),
      ),
    [fields],
  );

  // Filter number-type fields for progress
  const numberFields = useMemo(
    () =>
      fields.filter(
        (f) =>
          f.dataType === 'number' ||
          f.dataType === 'decimal' ||
          f.dataType === 'integer' ||
          f.dataType === 'number' ||
          f.dataType === 'integer' ||
          f.dataType === 'decimal' ||
          f.code?.toLowerCase().includes('progress') ||
          f.code?.toLowerCase().includes('percent'),
      ),
    [fields],
  );

  const selectClass = cn(
    'w-full px-3 py-1.5 text-sm border border-gray-300 rounded-md bg-white',
    'focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500',
  );

  return (
    <div className="space-y-3">
      {/* Start Date Field (required) */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-600">
          Start Date Field <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.ganttStartDateField || ''}
          onChange={(e) => handleFieldChange('ganttStartDateField', e.target.value)}
          className={selectClass}
        >
          <option value="">Select start date field...</option>
          {(dateFields.length > 0 ? dateFields : fields).map((f) => (
            <option key={f.code} value={f.code}>
              {f.name || f.code}
            </option>
          ))}
        </select>
      </div>

      {/* End Date Field (required) */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-600">
          End Date Field <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.ganttEndDateField || ''}
          onChange={(e) => handleFieldChange('ganttEndDateField', e.target.value)}
          className={selectClass}
        >
          <option value="">Select end date field...</option>
          {(dateFields.length > 0 ? dateFields : fields).map((f) => (
            <option key={f.code} value={f.code}>
              {f.name || f.code}
            </option>
          ))}
        </select>
      </div>

      {/* Title Field */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-600">Title Field</label>
        <select
          value={viewConfig.ganttTitleField || ''}
          onChange={(e) => handleFieldChange('ganttTitleField', e.target.value)}
          className={selectClass}
        >
          <option value="">Default (name)</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name || f.code}
            </option>
          ))}
        </select>
      </div>

      {/* Progress Field */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-600">
          Progress Field (0-100)
        </label>
        <select
          value={viewConfig.ganttProgressField || ''}
          onChange={(e) => handleFieldChange('ganttProgressField', e.target.value)}
          className={selectClass}
        >
          <option value="">None</option>
          {(numberFields.length > 0 ? numberFields : fields).map((f) => (
            <option key={f.code} value={f.code}>
              {f.name || f.code}
            </option>
          ))}
        </select>
      </div>

      {/* Dependency Field */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-600">Dependency Field</label>
        <select
          value={viewConfig.ganttDependencyField || ''}
          onChange={(e) => handleFieldChange('ganttDependencyField', e.target.value)}
          className={selectClass}
        >
          <option value="">None</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name || f.code}
            </option>
          ))}
        </select>
      </div>

      {/* Default View Mode */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-600">Default View</label>
        <select
          value={viewConfig.ganttDefaultView || 'Day'}
          onChange={(e) => handleFieldChange('ganttDefaultView', e.target.value)}
          className={selectClass}
        >
          <option value="Day">Day</option>
          <option value="Week">Week</option>
          <option value="Month">Month</option>
        </select>
      </div>
    </div>
  );
};

export default GanttConfigPanel;
