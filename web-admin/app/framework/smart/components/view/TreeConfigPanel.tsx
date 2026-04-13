/**
 * TreeConfigPanel Component
 *
 * Configuration panel for TREE view type.
 * Allows users to select parent field, title field, and display fields.
 */

import React, { useCallback, useMemo } from 'react';
import type { ViewConfig } from '~/framework/smart/types/savedView';
import type { FieldOption } from './KanbanConfigPanel';
import { cn } from '~/utils/cn';

export interface TreeConfigPanelProps {
  /** Current view configuration */
  viewConfig: ViewConfig;
  /** Callback when configuration changes */
  onChange: (config: ViewConfig) => void;
  /** Available model fields */
  fields: FieldOption[];
}

/**
 * TreeConfigPanel - Configuration panel for Tree view
 */
export const TreeConfigPanel: React.FC<TreeConfigPanelProps> = ({
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

  const handleDisplayFieldsChange = useCallback(
    (fieldCode: string, checked: boolean) => {
      const current = viewConfig.treeDisplayFields || [];
      const updated = checked ? [...current, fieldCode] : current.filter((c) => c !== fieldCode);
      onChange({ ...viewConfig, treeDisplayFields: updated.length > 0 ? updated : undefined });
    },
    [viewConfig, onChange],
  );

  // Filter REFERENCE-type fields for parent field
  const referenceFields = useMemo(
    () =>
      fields.filter(
        (f) =>
          f.dataType === 'reference' ||
          f.dataType === 'reference' ||
          f.code?.toLowerCase().includes('parent'),
      ),
    [fields],
  );

  const selectClass = cn(
    'w-full px-3 py-1.5 text-sm border border-gray-300 rounded-md bg-white',
    'focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500',
  );

  return (
    <div className="space-y-3">
      {/* Parent Field (required) */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-600">
          Parent Field <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.treeParentField || ''}
          onChange={(e) => handleFieldChange('treeParentField', e.target.value)}
          className={selectClass}
        >
          <option value="">Select parent field...</option>
          {(referenceFields.length > 0 ? referenceFields : fields).map((f) => (
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
          value={viewConfig.treeTitleField || ''}
          onChange={(e) => handleFieldChange('treeTitleField', e.target.value)}
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

      {/* Display Fields (multi-select checkboxes) */}
      {fields.length > 0 && (
        <div>
          <label className="mb-1 block text-xs font-medium text-gray-600">Display Fields</label>
          <div className="max-h-32 space-y-1 overflow-y-auto rounded-md border border-gray-200 p-2">
            {fields.map((f) => (
              <label
                key={f.code}
                className="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-sm text-gray-700 hover:bg-gray-50"
              >
                <input
                  type="checkbox"
                  checked={(viewConfig.treeDisplayFields || []).includes(f.code)}
                  onChange={(e) => handleDisplayFieldsChange(f.code, e.target.checked)}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                {f.name || f.code}
              </label>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default TreeConfigPanel;
