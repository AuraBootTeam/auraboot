/**
 * FieldPropertyEditor - V4 field property editor
 *
 * Based on JSON configuration, renders property editing UI for DSL fields.
 * Supports conditional visibility based on block type and data type.
 *
 * Uses simple native form components to avoid complex hook dependencies.
 */

import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText, type TranslatableText } from '~/utils/i18n';
import React, { useState, useCallback, useEffect, useMemo } from 'react';
import type { DslFieldOverride, BlockType } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { parseFieldShorthand } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { useDslRegistry } from '~/contexts/DslRegistryContext';
import { evaluateScopedExpression } from '~/framework/meta/runtime/expression/scopedEval';
import fieldPropertyConfig from '../configs/field-property-panel.json';
import { LocalizedTextInput, type LocalizedTextValue } from '~/shared/designer';
import {
  FieldPermissionSection,
  type FieldPermissionValue,
} from './FieldPermissionSection';
import { WidgetRegistry } from '~/plugins/core-designer/components/studio/registry/widget-registry';
import { PropertyFieldRenderer } from '~/shared/designer/PropertyFieldRenderer';
import type { PropertySchema } from '~/shared/designer/types';
import type { FieldAdapter } from '~/ui/field-adapter';

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
 *
 * Keys are normalised to UPPERCASE so they match the FALLBACK map convention
 * and the `.toUpperCase()` lookup in `getComponentOptions`. Without this
 * normalisation, a server registry that ships lowercase dataType strings
 * (e.g. "string") produces a map keyed `{"string": [...]}` while the lookup
 * uses `"STRING"`, yielding `undefined` → `options.map(...)` crash (B15).
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
        const key = String(dt).toUpperCase(); // normalise to uppercase
        if (!map[key]) map[key] = [];
        map[key].push(option);
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
  title: TranslatableText;
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
  const { locale, t } = useI18n();
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
    new Set(['basic', 'validation', 'widget-specific']),
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
        // Sandbox interpreter; blockType/dataType provided as context variables
        // (was: token substitution into a new Function string).
        const result = evaluateScopedExpression(expr, { blockType, dataType });
        return !!result;
      } catch (error) {
        // Designer tool surface: a broken expression keeps the field visible
        // but is logged (observable), never silent.
        console.error('[expression] field visibility eval failed:', expr, error);
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

  // Get component options based on data type.
  // Both DATATYPE_COMPONENT_OPTIONS_FALLBACK and registry-derived maps are keyed
  // by uppercase canonical names (buildComponentOptionsFromRegistry normalises).
  // The final `|| []` guard ensures we never return undefined even if the registry
  // ships an entirely unknown dataType with no fallback entry.
  const getComponentOptions = useCallback((): Array<{ label: string; value: string }> => {
    const key = String(dataType || 'string').toUpperCase();
    return DATATYPE_COMPONENT_OPTIONS[key] || DATATYPE_COMPONENT_OPTIONS['STRING'] || [];
  }, [dataType, DATATYPE_COMPONENT_OPTIONS]);

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
      const controlTestId = `field-property-${fieldConfig.field}`;

      // Get options for select
      let options: Array<{ label: TranslatableText; value: string | number }> = fieldConfig.props?.options || [];
      if (fieldConfig.optionsKey === 'componentOptions') {
        // getComponentOptions() is guaranteed non-undefined via FALLBACK map, but
        // guard with `?? []` as defence-in-depth against unexpected registry shapes.
        options = getComponentOptions() ?? [];
      }

      const label = getLocalizedText(fieldConfig.props?.label, locale, t);
      const placeholder = getLocalizedText(fieldConfig.props?.placeholder, locale, t);

      // Render based on component type
      let component: React.ReactNode;
      switch (fieldConfig.component) {
        case 'SmartInput':
          component = (
            <div>
              <label htmlFor={`${controlTestId}-input`} className="mb-1 block text-xs font-medium text-gray-600">
                {label}
              </label>
              <input
                id={`${controlTestId}-input`}
                data-testid={`${controlTestId}-input`}
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
                placeholder={placeholder}
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
              <label htmlFor={`${controlTestId}-select`} className="mb-1 block text-xs font-medium text-gray-600">
                {label}
              </label>
              <select
                id={`${controlTestId}-select`}
                data-testid={`${controlTestId}-select`}
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
                  <option value="">{placeholder || getLocalizedText({ 'zh-CN': '请选择', 'en-US': 'Select an option' }, locale)}</option>
                )}
                {options.map((opt: { label: TranslatableText; value: any }) => (
                  <option key={opt.value} value={opt.value}>
                    {getLocalizedText(opt.label, locale, t)}
                  </option>
                ))}
              </select>
            </div>
          );
          break;

        case 'LocalizedTextInput':
          component = (
            <LocalizedTextInput
              value={fieldValue as LocalizedTextValue}
              onChange={(next) => handleFieldChange(fieldConfig.field, next ?? undefined)}
              label={label}
              placeholder={placeholder}
              disabled={isDisabled}
              testId={`field-${fieldConfig.field}`}
            />
          );
          break;

        case 'SmartSwitch':
          component = (
            <div className="flex items-center justify-between py-1">
              <span className="text-xs font-medium text-gray-600">{label}</span>
              <button
                type="button"
                role="switch"
                data-testid={`${controlTestId}-switch`}
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
        <div
          key={fieldConfig.field}
          data-testid={controlTestId}
          style={{ gridColumn: `span ${colSpan}` }}
        >
          {component}
        </div>
      );
    },
    [fieldData, evalVisible, handleFieldChange, readonly, getComponentOptions, locale, t],
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
            <span className="text-sm font-medium text-gray-700">{getLocalizedText(section.title, locale, t)}</span>
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
    [expandedSections, evalVisible, renderField, toggleSection, locale, t],
  );

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="border-b border-gray-200 px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-lg">📝</span>
            <div>
              <h3 className="text-sm font-medium text-gray-900">{getLocalizedText(fieldPropertyConfig.meta.title, locale, t)}</h3>
              <p className="text-xs text-gray-400">{fieldData.field}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-xs text-blue-600 hover:text-blue-800 hover:underline"
          >
            {getLocalizedText({ 'zh-CN': '返回 Block', 'en-US': 'Back to block' }, locale)}
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

        {/* Widget-specific properties — schema-driven panel rendered from
            WidgetRegistry[component].schema. Writes into field.props.* namespace. */}
        <WidgetSpecificPanel
          component={fieldData.component}
          props={(fieldData as DslFieldOverride).props ?? {}}
          onPropsChange={(next) =>
            onChange({ props: Object.keys(next).length > 0 ? next : undefined } as Partial<DslFieldOverride>)
          }
          readonly={readonly}
          expanded={expandedSections.has('widget-specific')}
          onToggle={() => toggleSection('widget-specific')}
        />

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

// ──────────────────────────────────────────────────────────────────────────────
// WidgetSpecificPanel — schema-driven config panel for widget-specific props.
//
// Reads WidgetRegistry[component].schema (PropertySchema[]) and renders each
// entry via the unified PropertyFieldRenderer. Values live inside the field's
// `props.*` namespace (NOT top-level field properties).
//
// dependsOn is evaluated against the current `props` object so conditional
// fields (e.g. dictCode visible when optionsSource === 'dict') work out of the box.
// ──────────────────────────────────────────────────────────────────────────────

interface WidgetSpecificPanelProps {
  component: string | undefined;
  props: Record<string, unknown>;
  onPropsChange: (next: Record<string, unknown>) => void;
  readonly?: boolean;
  expanded: boolean;
  onToggle: () => void;
}

const WidgetSpecificPanel: React.FC<WidgetSpecificPanelProps> = ({
  component,
  props,
  onPropsChange,
  readonly,
  expanded,
  onToggle,
}) => {
  const { locale } = useI18n();
  const schema: PropertySchema<string>[] = useMemo(() => {
    if (!component) return [];
    return WidgetRegistry.getSchema(component);
  }, [component]);

  if (!component || schema.length === 0) {
    return null;
  }

  const widgetName = WidgetRegistry.getName(component);

  // Build a per-schema FieldAdapter that read/writes into props[key].
  const makeAdapter = (s: PropertySchema<string>): FieldAdapter<unknown> => ({
    value: props[s.key] ?? s.defaultValue,
    setValue: (v: unknown) => {
      const next = { ...props };
      if (v === undefined || v === null || v === '') {
        delete next[s.key];
      } else {
        next[s.key] = v;
      }
      onPropsChange(next);
    },
    disabled: readonly,
  });

  // Evaluate dependsOn against current props bag.
  const isVisible = (s: PropertySchema<string>): boolean => {
    if (!s.dependsOn) return true;
    const current = props[s.dependsOn.field];
    if (s.dependsOn.value === undefined) {
      return current !== undefined && current !== null && current !== '';
    }
    return current === s.dependsOn.value;
  };

  const visibleSchemas = schema.filter(isVisible);

  return (
    <div className="mb-3" data-testid="widget-specific-panel" data-component={component}>
      <button
        className="flex w-full items-center justify-between rounded-t border border-gray-200 bg-gray-50 p-2 transition-colors hover:bg-gray-100"
        onClick={onToggle}
      >
        <span className="text-sm font-medium text-gray-700">
          {widgetName} {getLocalizedText({ 'zh-CN': '属性', 'en-US': 'Properties' }, locale)}
        </span>
        <svg
          className={`h-4 w-4 transform text-gray-400 transition-transform ${
            expanded ? 'rotate-180' : ''
          }`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      {expanded && (
        <div className="rounded-b border border-t-0 border-gray-200 bg-white p-3">
          <div className="grid grid-cols-1 gap-3">
            {visibleSchemas.map((s) => (
              <div key={s.key} data-testid={`widget-prop-${s.key}`}>
                <PropertyFieldRenderer schema={s} adapter={makeAdapter(s)} />
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default FieldPropertyEditor;
