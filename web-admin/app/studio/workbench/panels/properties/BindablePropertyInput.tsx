/**
 * Bindable Property Input Component
 *
 * Enhanced property input that supports switching between static value and expression mode.
 *
 * @since 3.2.0
 */

import React, { useCallback, useState, useMemo } from 'react';
import { PropertyInput, type PropertyInputProps } from './PropertyEditor/PropertyInput';
import { BindingToggle, type BindingMode, BindingIndicator } from './BindingToggle';
import { ExpressionInput } from '~/studio/workbench/components/expression-editor';
import type { ExpressionContext } from '~/studio/workbench/components/expression-editor/types';

/**
 * Property value that may be static or expression-bound
 */
export interface BindableValue {
  mode: BindingMode;
  value: any;
  expression?: string;
}

/**
 * Check if a value is a bindable value object
 */
function isBindableValue(value: any): value is BindableValue {
  return (
    value !== null &&
    typeof value === 'object' &&
    'mode' in value &&
    'value' in value &&
    (value.mode === 'static' || value.mode === 'expression')
  );
}

/**
 * Convert any value to BindableValue format
 */
function toBindableValue(value: any): BindableValue {
  if (isBindableValue(value)) {
    return value;
  }
  // Check if it looks like an expression (starts with {{ )
  if (typeof value === 'string' && value.trim().startsWith('{{')) {
    return {
      mode: 'expression',
      value: undefined,
      expression: value,
    };
  }
  return {
    mode: 'static',
    value,
  };
}

/**
 * Get the effective value from a bindable value
 */
function getEffectiveValue(bindableValue: BindableValue): any {
  if (bindableValue.mode === 'expression') {
    return bindableValue.expression;
  }
  return bindableValue.value;
}

export interface BindablePropertyInputProps extends Omit<PropertyInputProps, 'value' | 'onChange'> {
  /** Current value (can be static or bindable) */
  value: any;
  /** Change handler */
  onChange: (value: any) => void;
  /** Whether this property supports expression binding */
  supportExpression?: boolean;
  /** Expression context for variables */
  expressionContext?: ExpressionContext;
  /** Compact mode (smaller toggle) */
  compact?: boolean;
}

/**
 * Bindable Property Input Component
 */
export const BindablePropertyInput: React.FC<BindablePropertyInputProps> = ({
  property,
  value,
  onChange,
  error,
  supportExpression = true,
  expressionContext,
  compact = true,
}) => {
  // Parse value into bindable format
  const bindableValue = useMemo(() => toBindableValue(value), [value]);
  const [mode, setMode] = useState<BindingMode>(bindableValue.mode);

  // Handle mode change
  const handleModeChange = useCallback(
    (newMode: BindingMode) => {
      setMode(newMode);
      if (newMode === 'expression') {
        // Switch to expression mode - preserve static value for potential conversion
        onChange({
          mode: 'expression',
          value: bindableValue.value,
          expression: bindableValue.expression || '',
        });
      } else {
        // Switch to static mode - use the static value
        onChange(bindableValue.value);
      }
    },
    [bindableValue, onChange],
  );

  // Handle static value change
  const handleStaticChange = useCallback(
    (newValue: any) => {
      if (mode === 'static') {
        onChange(newValue);
      } else {
        onChange({
          mode: 'expression',
          value: newValue,
          expression: bindableValue.expression,
        });
      }
    },
    [mode, bindableValue.expression, onChange],
  );

  // Handle expression change
  const handleExpressionChange = useCallback(
    (expression: string) => {
      onChange({
        mode: 'expression',
        value: bindableValue.value,
        expression,
      });
    },
    [bindableValue.value, onChange],
  );

  // If expression binding not supported, render normal input
  if (!supportExpression) {
    return <PropertyInput property={property} value={value} onChange={onChange} error={error} />;
  }

  return (
    <div className="bindable-property-input space-y-1.5">
      {/* Label with binding toggle */}
      <div className="flex items-center justify-between">
        <label
          htmlFor={property.key}
          className="flex items-center gap-1.5 text-sm font-medium text-gray-700"
        >
          {property.label}
          {property.required && <span className="text-red-500">*</span>}
          {mode === 'expression' && <BindingIndicator mode={mode} />}
        </label>

        <BindingToggle mode={mode} onModeChange={handleModeChange} size={compact ? 'sm' : 'md'} />
      </div>

      {/* Input area */}
      {mode === 'static' ? (
        // Static value input
        <div className="relative">
          <PropertyInput
            property={{ ...property, label: undefined }}
            value={bindableValue.value}
            onChange={handleStaticChange}
            error={error}
          />
        </div>
      ) : (
        // Expression input
        <div className="relative">
          <ExpressionInput
            value={bindableValue.expression || ''}
            onChange={handleExpressionChange}
            context={expressionContext}
            placeholder={`{{ ${property.key} }}`}
            expandable={true}
          />
        </div>
      )}

      {/* Description */}
      {property.description && !error && (
        <p className="text-xs text-gray-500">{property.description}</p>
      )}

      {/* Error */}
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  );
};

export default BindablePropertyInput;
