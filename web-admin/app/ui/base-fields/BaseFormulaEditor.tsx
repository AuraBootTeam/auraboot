/**
 * BaseFormulaEditor - Formula expression editor field component.
 * Wraps FormulaEditor to consume the FieldAdapter interface,
 * consistent with BaseInput / BaseTextarea / BaseSelect patterns.
 */

import React from 'react';
import type { FieldAdapter } from '~/ui/field-adapter';
import { FormulaEditor } from '~/framework/smart/components/formula/FormulaEditor';
import type { FormulaFunction } from '~/framework/smart/components/formula/FormulaEditor';
import { cn } from '~/utils/cn';

export interface BaseFormulaEditorProps {
  adapter: FieldAdapter<any>;
  name: string;
  label?: string;
  placeholder?: string;
  helpText?: string;
  className?: string;
  /** Available fields for autocomplete */
  fields?: { code: string; name: string }[];
  /** Fetch available formula functions */
  fetchFunctions?: () => Promise<FormulaFunction[]>;
}

export function BaseFormulaEditor({
  adapter,
  name,
  label,
  placeholder,
  helpText,
  className,
  fields,
  fetchFunctions,
}: BaseFormulaEditorProps) {
  const hasError = !!adapter.error;

  return (
    <div className={cn('mb-4', className)}>
      {label && (
        <label htmlFor={name} className="text-text-2 mb-1 block text-sm font-medium">
          {label}
          {adapter.required && <span className="text-status-red ml-1">*</span>}
        </label>
      )}
      <FormulaEditor
        value={(adapter.value as string) ?? ''}
        onChange={(val) => adapter.setValue(val)}
        placeholder={placeholder}
        disabled={adapter.disabled}
        error={hasError ? adapter.error : undefined}
        fields={fields}
        fetchFunctions={fetchFunctions}
      />
      {!hasError && helpText && <p className="text-text-2 mt-1 text-xs">{helpText}</p>}
    </div>
  );
}

export default BaseFormulaEditor;
