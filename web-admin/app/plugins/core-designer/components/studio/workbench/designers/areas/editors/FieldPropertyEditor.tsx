/**
 * FieldPropertyEditor - V4 字段属性编辑器
 *
 * Based on JSON configuration, renders property editing UI for DSL fields.
 * Supports conditional visibility based on block type and data type.
 *
 * Uses simple native form components to avoid complex hook dependencies.
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react';
import type { DslFieldOverride, BlockType } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { parseFieldShorthand } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { useDslRegistry } from '~/contexts/DslRegistryContext';
import fieldPropertyConfig from '../configs/field-property-panel.json';
import {
  FieldPermissionSection,
  type FieldPermissionValue,
} from './FieldPermissionSection';

/**
 * Static fallback for data type → component options mapping.
 * At runtime, the DSL registry provides this via extensions.renderComponents.
 */
const DATATYPE_COMPONENT_OPTIONS_FALLBACK: Record<
  string,
  Array<{ label: string; value: string }>
> = {
  STRING: [
    { label: 'Input', value: 'smart-input' },
    { label: 'Textarea', value: 'smart-textarea' },
    { label: 'Password', value: 'smart-password' },
  ],
  TEXT: [
    { label: 'Textarea', value: 'smart-textarea' },
    { label: 'Rich Text', value: 'smart-richtext' },
  ],
  INTEGER: [
    { label: 'Number Input', value: 'smart-numberinput' },
    { label: 'Slider', value: 'smart-slider' },
  ],
  DECIMAL: [
    { label: 'Number Input', value: 'smart-numberinput' },
    { label: 'Currency', value: 'smart-currency' },
  ],
  BOOLEAN: [
    { label: 'Switch', value: 'smart-switch' },
    { label: 'Checkbox', value: 'smart-checkbox' },
  ],
  DATE: [{ label: 'Date Picker', value: 'smart-datepicker' }],
  DATETIME: [{ label: 'DateTime Picker', value: 'smart-datetimepicker' }],
  ENUM: [
    { label: 'Select', value: 'smart-select' },
    { label: 'Radio', value: 'smart-radio' },
  ],
  REFERENCE: [
    { label: 'Select', value: 'smart-select' },
    { label: 'Tree Select', value: 'smart-treeselect' },
  ],
  FILE: [{ label: 'Upload', value: 'smart-upload' }],
  IMAGE: [
    { label: 'Upload', value: 'smart-upload' },
    { label: 'Image Picker', value: 'smart-imagepicker' },
  ],
};

/**
 * Build DATATYPE_COMPONENT_OPTIONS from the DSL registry's renderComponents.
 * Groups components by their compatible dataTypes.
 */
function buildComponentOptionsFromRegistry(
  renderComponents: Array<{ code: string; dataTypes?: string[]; category?: string }>,
): Record<string, Array<{ label: string; value: string }>> | null {
  if (!renderComponents || renderComponents.length === 0) return null;
  const map: Record<string, Array<{ label: string; value: string }>> = {};
  for (const rc of renderComponents) {
    const option = { label: rc.code, value: rc.code };
    if (rc.dataTypes && rc.dataTypes.length > 0) {
      for (const dt of rc.dataTypes) {
        if (!map[dt]) map[dt] = [];
        map[dt].push(option);
      }
    }
  }
  return Object.keys(map).length > 0 ? map : null;
}

export interface FieldPropertyEditorProps {
  fieldRef: DslFieldRef;
  blockType: BlockType;
  dataType?: string;
  onChange: (updates: Partial<DslFieldOverride>) => void;
  onClose: () => void;
  readonly?: boolean;
}

// Import type for field ref
type DslFieldRef = string | DslFieldOverride;

interface FieldConfig {
  field: string;
  component: string;
  props: any;
  layout?: { colSpan?: number };
  visible?: string;
  optionsKey?: string;
}

interface SectionConfig {
  code: string;
  title: string;
  layout: { columns: number; gap: string };
  visible?: string;
  fields: FieldConfig[];
}

export const FieldPropertyEditor: React.FC<FieldPropertyEditorProps> = ({
  fieldRef,
  blockType,
  dataType = 'string',
  onChange,
  onClose,
  readonly,
}) => {
  // DSL registry: use render components from server if available
  const { ensureLoaded, renderComponents } = useDslRegistry();
  useEffect(() => { ensureLoaded(); }, [ensureLoaded]);
  const DATATYPE_COMPONENT_OPTIONS = useMemo(() => {
    return (
      buildComponentOptionsFromRegistry(renderComponents) || DATATYPE_COMPONENT_OPTIONS_FALLBACK
    );
  }, [renderComponents]);

  // Parse field to get current values
  const fieldData = useMemo(() => parseFieldShorthand(fieldRef), [fieldRef]);

  const [expandedSections, setExpandedSections] = useState<Set<string>>(
    new Set(['basic', 'validation']),
  );

  // Toggle section expansion
  const toggleSection = useCallback((sectionCode: string) => {
    setExpandedSections((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(sectionCode)) {
        newSet.delete(sectionCode);
      } else {
        newSet.add(sectionCode);
      }
      return newSet;
    });
  }, []);

  // Evaluate visibility expression
  const evalVisible = useCallback(
    (expr: string | undefined): boolean => {
      if (!expr) return true;

      try {
        // Simple expression evaluation
        const code = expr
          .replace(/blockType/g, `'${blockType}'`)
          .replace(/dataType/g, `'${dataType}'`);
        // eslint-disable-next-line no-new-func
        return new Function(`return ${code}`)();
      } catch {
        return true;
      }
    },
    [blockType, dataType],
  );

  // Handle field value change
  const handleFieldChange = useCallback(
    (fieldName: string, value: any) => {
      // Convert empty strings to undefined for cleaner DSL
      const cleanValue = value === '' ? undefined : value;
      onChange({ [fieldName]: cleanValue });
    },
    [onChange],
  );

  // Read current fieldPermission from props bag
  const currentFieldPermission = useMemo((): FieldPermissionValue | undefined => {
    const fp = (fieldData as DslFieldOverride & { props?: Record<string, unknown> }).props
      ?.fieldPermission;
    if (fp && typeof fp === 'object') {
      const typed = fp as Record<string, unknown>;
      return {
        view: Array.isArray(typed.view) ? (typed.view as string[]) : [],
        edit: Array.isArray(typed.edit) ? (typed.edit as string[]) : [],
      };
    }
    return undefined;
  }, [fieldData]);

  // Update fieldPermission inside props bag
  const handleFieldPermissionChange = useCallback(
    (next: FieldPermissionValue | null) => {
      const existingProps =
        (fieldData as DslFieldOverride & { props?: Record<string, unknown> }).props ?? {};
      if (next === null) {
        // Remove fieldPermission key
        const { fieldPermission: _removed, ...rest } = existingProps as Record<string, unknown>;
        onChange({ props: Object.keys(rest).length > 0 ? rest : undefined } as Partial<DslFieldOverride>);
      } else {
        onChange({ props: { ...existingProps, fieldPermission: next } } as Partial<DslFieldOverride>);
      }
    },
    [fieldData, onChange],
  );

  // Get component options based on data type
  const getComponentOptions = useCallback((): Array<{ label: string; value: string }> => {
    return DATATYPE_COMPONENT_OPTIONS[dataType] || DATATYPE_COMPONENT_OPTIONS.STRING;
  }, [dataType]);

  // Render a single field using native HTML components
  const renderField = useCallback(
    (fieldConfig: FieldConfig) => {
      // Check visibility
      if (!evalVisible(fieldConfig.visible)) {
        return null;
      }

      const fieldValue = (fieldData as any)[fieldConfig.field];
      const colSpan = fieldConfig.layout?.colSpan || 1;
      const isDisabled = readonly || fieldConfig.props?.disabled;

      // Get options for select
      let options = fieldConfig.props?.options || [];
      if (fieldConfig.optionsKey === 'componentOptions') {
        options = getComponentOptions();
      }

      // Render based on component type
      let component: React.ReactNode;
      switch (fieldConfig.component) {
        case 'SmartInput':
          component = (
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600">
                {fieldConfig.props.label}
              </label>
              <input
                type={fieldConfig.props.type || 'text'}
                value={fieldValue ?? ''}
                onChange={(e) => {
                  const val =
                    fieldConfig.props.type === 'number'
                      ? e.target.value === ''
                        ? undefined
                        : Number(e.target.value)
                      : e.target.value;
                  handleFieldChange(fieldConfig.field, val);
                }}
                placeholder={fieldConfig.props.placeholder}
                disabled={isDisabled}
                min={fieldConfig.props.min}
                className={`w-full rounded-md border border-gray-200 px-2 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none ${isDisabled ? 'bg-gray-50 text-gray-400' : 'bg-white'} `}
              />
            </div>
          );
          break;

        case 'SmartSelect':
          component = (
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600">
                {fieldConfig.props.label}
              </label>
              <select
                value={fieldValue ?? ''}
                onChange={(e) => {
                  const val = e.target.value === '' ? undefined : e.target.value;
                  // Try to convert to number if applicable
                  const numVal = Number(val);
                  handleFieldChange(fieldConfig.field, !isNaN(numVal) && val !== '' ? numVal : val);
                }}
                disabled={isDisabled}
                className={`w-full rounded-md border border-gray-200 px-2 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none ${isDisabled ? 'bg-gray-50 text-gray-400' : 'bg-white'} `}
              >
                {fieldConfig.props.allowClear && (
                  <option value="">{fieldConfig.props.placeholder || '请选择'}</option>
                )}
                {options.map((opt: { label: string; value: any }) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>
          );
          break;

        case 'SmartSwitch':
          component = (
            <div className="flex items-center justify-between py-1">
              <span className="text-xs font-medium text-gray-600">{fieldConfig.props.label}</span>
              <button
                type="button"
                role="switch"
                aria-checked={!!fieldValue}
                disabled={isDisabled}
                onClick={() => handleFieldChange(fieldConfig.field, !fieldValue)}
                className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${fieldValue ? 'bg-blue-600' : 'bg-gray-200'} ${isDisabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'} `}
              >
                <span
                  className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${fieldValue ? 'translate-x-4' : 'translate-x-0.5'} `}
                />
              </button>
            </div>
          );
          break;

        default:
          component = null;
      }

      return (
        <div key={fieldConfig.field} style={{ gridColumn: `span ${colSpan}` }}>
          {component}
        </div>
      );
    },
    [fieldData, evalVisible, handleFieldChange, readonly, getComponentOptions],
  );

  // Render a section
  const renderSection = useCallback(
    (section: SectionConfig) => {
      // Check section visibility
      if (!evalVisible(section.visible)) {
        return null;
      }

      const isExpanded = expandedSections.has(section.code);
      const visibleFields = section.fields.filter((f) => evalVisible(f.visible));

      if (visibleFields.length === 0) {
        return null;
      }

      return (
        <div key={section.code} className="mb-3">
          <button
            className="flex w-full items-center justify-between rounded-t border border-gray-200 bg-gray-50 p-2 transition-colors hover:bg-gray-100"
            onClick={() => toggleSection(section.code)}
          >
            <span className="text-sm font-medium text-gray-700">{section.title}</span>
            <svg
              className={`h-4 w-4 transform text-gray-400 transition-transform ${
                isExpanded ? 'rotate-180' : ''
              }`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 9l-7 7-7-7"
              />
            </svg>
          </button>
          {isExpanded && (
            <div className="rounded-b border border-t-0 border-gray-200 bg-white p-3">
              <div
                className="grid gap-3"
                style={{ gridTemplateColumns: `repeat(${section.layout.columns}, 1fr)` }}
              >
                {visibleFields.map(renderField)}
              </div>
            </div>
          )}
        </div>
      );
    },
    [expandedSections, evalVisible, renderField, toggleSection],
  );

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="border-b border-gray-200 px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-lg">📝</span>
            <div>
              <h3 className="text-sm font-medium text-gray-900">字段属性</h3>
              <p className="text-xs text-gray-400">{fieldData.field}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-xs text-blue-600 hover:text-blue-800 hover:underline"
          >
            返回 Block
          </button>
        </div>
      </div>

      {/* Field badge */}
      <div className="border-b border-blue-100 bg-blue-50 px-4 py-2">
        <div className="flex items-center gap-2 text-sm">
          <span className="font-mono text-blue-700">{fieldData.field}</span>
          {dataType && (
            <span className="rounded bg-blue-100 px-1.5 py-0.5 text-xs text-blue-600">
              {dataType}
            </span>
          )}
          <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600">
            {blockType}
          </span>
        </div>
      </div>

      {/* Sections */}
      <div className="flex-1 overflow-auto p-3">
        {(fieldPropertyConfig.sections as SectionConfig[]).map(renderSection)}

        {/* Field-level role permissions (custom section — not schema-driven because it
            requires async role loading + checkbox UI not expressible as a PropertySchema widget) */}
        <FieldPermissionSection
          value={currentFieldPermission}
          onChange={handleFieldPermissionChange}
          disabled={readonly}
        />
      </div>
    </div>
  );
};

export default FieldPropertyEditor;
