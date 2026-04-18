/**
 * PropertyFieldRenderer - Unified property field renderer for all designers.
 *
 * Accepts a FieldAdapter (value/setValue/error/required) and a PropertySchema,
 * then renders the appropriate base-field component. This eliminates ~95%
 * duplication between DashboardPropertyField and Flow PropertyField.
 */

import React from 'react';
import {
  BaseInput,
  BaseSelect,
  BaseSwitch,
  BaseTextarea,
  BaseFormulaEditor,
  BaseResourceSelect,
} from '~/ui/base-fields';
import {
  fetchPageOptions,
  fetchDashboardOptions,
  fetchProcessOptions,
  fetchAutomationOptions,
  fetchCommandOptions,
  fetchModelOptions,
} from '~/shared/services/resourceSelectService';
import { ExpressionEditor } from './expression';
import { DependentFieldSelect } from './DependentFieldSelect';
import { DependentMultiSelect } from './DependentMultiSelect';
import type { FieldAdapter } from '~/ui/field-adapter';
import type { PropertySchema, PropertyType } from './types';

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export interface PropertyFieldRendererProps {
  /** Schema that describes the field type, label, options, etc. */
  schema: PropertySchema<string>;
  /** FieldAdapter that bridges the designer store with base-field components. */
  adapter: FieldAdapter<unknown>;
}

/**
 * Render a single property field based on its PropertySchema.
 *
 * The caller is responsible for:
 *  - Resolving i18n labels to plain strings before passing `schema`
 *  - Creating the appropriate FieldAdapter (flow, dashboard, etc.)
 *  - Evaluating `dependsOn` visibility (keep at panel level)
 */
export function PropertyFieldRenderer({ schema, adapter }: PropertyFieldRendererProps) {
  const label = schema.label as string;
  const placeholder = schema.placeholder as string | undefined;
  const helpText = schema.description as string | undefined;

  switch (schema.type) {
    // ---- Text-like inputs ----
    case 'text':
    case 'model':
    case 'namedQuery':
      return (
        <BaseInput
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={
            placeholder ||
            (schema.type === 'model'
              ? 'Enter model code'
              : schema.type === 'namedQuery'
                ? 'Enter query code'
                : undefined)
          }
          helpText={helpText}
        />
      );

    case 'number': {
      // BaseInput stores `e.target.value` (always a string) in the adapter.
      // For `type: 'number'` schemas the persisted DSL must carry a real
      // number, otherwise downstream consumers (column.width, table.props
      // .pageSize, etc.) silently coerce types or fail Number comparisons.
      // Wrap the adapter so reads expose strings (for the input's `value`
      // prop) while writes coerce back to number | undefined.
      const baseAdapter = adapter as unknown as {
        value: unknown;
        setValue: (v: unknown) => void;
      } & Record<string, unknown>;
      const numericAdapter = {
        ...baseAdapter,
        value:
          baseAdapter.value === undefined || baseAdapter.value === null
            ? ''
            : String(baseAdapter.value),
        setValue: (v: unknown) => {
          if (v === '' || v === null || v === undefined) {
            baseAdapter.setValue(undefined);
            return;
          }
          const n = typeof v === 'number' ? v : Number(v);
          baseAdapter.setValue(Number.isNaN(n) ? undefined : n);
        },
      };
      return (
        <BaseInput
          adapter={numericAdapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder}
          helpText={helpText}
          type="number"
        />
      );
    }

    case 'textarea':
      return (
        <BaseTextarea
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder}
          helpText={helpText}
          rows={4}
        />
      );

    case 'json':
      return <JsonField adapter={adapter} name={schema.key} label={label} helpText={helpText} />;

    // ---- Selection fields ----
    case 'select':
      return (
        <BaseSelect
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder}
          helpText={helpText}
          options={(schema.options || []).map((opt) => ({
            label: opt.label as string,
            value: opt.value,
          }))}
        />
      );

    case 'multiselect':
      if (schema.options && schema.options.length > 0) {
        return (
          <BaseSelect
            adapter={adapter as any}
            name={schema.key}
            label={label}
            placeholder={placeholder}
            helpText={helpText}
            options={schema.options.map((opt) => ({
              label: opt.label as string,
              value: opt.value,
            }))}
          />
        );
      }
      return (
        <DependentMultiSelect
          adapter={adapter}
          label={label}
          helpText={helpText}
          placeholder={placeholder}
        />
      );

    case 'model-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select model...'}
          helpText={helpText}
          fetchOptions={fetchModelOptions}
        />
      );

    case 'field-select':
      return (
        <DependentFieldSelect
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select field...'}
          helpText={helpText}
        />
      );

    case 'boolean':
      return (
        <BaseSwitch adapter={adapter as any} name={schema.key} label={label} helpText={helpText} />
      );

    // ---- Expression / formula ----
    case 'expression':
      return (
        <ExpressionEditor
          adapter={adapter as any}
          name={schema.key}
          label={label}
          helpText={helpText}
        />
      );

    case 'formula':
      return (
        <BaseFormulaEditor
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder || 'Enter expression...'}
          helpText={helpText}
        />
      );

    // ---- Resource selects ----
    case 'page-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select page...'}
          helpText={helpText}
          fetchOptions={fetchPageOptions}
        />
      );

    case 'dashboard-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select dashboard...'}
          helpText={helpText}
          fetchOptions={fetchDashboardOptions}
        />
      );

    case 'process-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select process...'}
          helpText={helpText}
          fetchOptions={fetchProcessOptions}
        />
      );

    case 'automation-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select automation...'}
          helpText={helpText}
          fetchOptions={fetchAutomationOptions}
        />
      );

    case 'command-select':
      return (
        <ResourceSelectField
          adapter={adapter}
          label={label}
          placeholder={placeholder || 'Select command...'}
          helpText={helpText}
          fetchOptions={fetchCommandOptions}
        />
      );

    default:
      return (
        <BaseInput
          adapter={adapter as any}
          name={schema.key}
          label={label}
          placeholder={placeholder}
          helpText={helpText}
        />
      );
  }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/** Wraps BaseResourceSelect with a label / helpText chrome. */
function ResourceSelectField({
  adapter,
  label,
  placeholder,
  helpText,
  fetchOptions,
}: {
  adapter: FieldAdapter<unknown>;
  label?: string;
  placeholder: string;
  helpText?: string;
  fetchOptions: () => Promise<{ label: string; value: string }[]>;
}) {
  return (
    <div>
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}
      <BaseResourceSelect
        value={(adapter.value as string) || ''}
        onChange={adapter.setValue as any}
        fetchOptions={fetchOptions}
        placeholder={placeholder}
      />
      {helpText && <p className="mt-1 text-xs text-gray-500">{helpText}</p>}
    </div>
  );
}

/** JSON field - handles serialization/deserialization for object values. */
function JsonField({
  adapter,
  name,
  label,
  helpText,
}: {
  adapter: FieldAdapter<unknown>;
  name: string;
  label?: string;
  helpText?: string;
}) {
  const displayValue =
    typeof adapter.value === 'string' ? adapter.value : JSON.stringify(adapter.value, null, 2);

  const jsonAdapter = {
    ...adapter,
    value: displayValue,
    setValue: (val: string) => {
      try {
        adapter.setValue(JSON.parse(val));
      } catch {
        // Keep raw string if not valid JSON
        adapter.setValue(val);
      }
    },
  };

  return (
    <BaseTextarea
      adapter={jsonAdapter as any}
      name={name}
      label={label}
      placeholder="{}"
      helpText={helpText}
      rows={4}
      className="font-mono"
    />
  );
}

export default PropertyFieldRenderer;
