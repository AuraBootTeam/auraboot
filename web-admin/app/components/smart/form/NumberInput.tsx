/**
 * NumberInput Component
 *
 * Number input with increment/decrement controls, precision, and range support.
 *
 * @since 3.2.0
 */

import React, { useRef, useCallback } from 'react';
import type { NumberInputProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import {
  fieldInputHeightStyles,
  fieldSizeStyles,
  fieldVariantStyles,
  fieldErrorFocusStyles,
} from '~/components/ui/field-styles';

const NumberInput: React.FC<NumberInputProps> = ({
  name,
  label,
  placeholder,
  value,
  defaultValue,
  required = false,
  disabled = false,
  min,
  max,
  step = 1,
  precision = 0,
  size = 'medium',
  variant = 'default',
  showButtons = true,
  buttonLayout = 'stacked',
  prefix,
  suffix,
  className = '',
  validationRules = [],
  context,
  expressions = {},
  visible,
  onChange,
  onBlur,
  onFocus,
  keyboard = true,
  ...restProps
}) => {
  const st = useSmartText();
  const inputRef = useRef<HTMLInputElement>(null);

  const {
    labelText,
    placeholderText,
    helpText: helpTextText,
    required: requiredValue,
    disabled: disabledValue,
    visible: isVisible,
  } = useSmartFieldContract({
    label,
    placeholder,
    required,
    disabled,
    expressions,
    context,
    visible,
  });

  const field = useSmartField<number | undefined>({
    name,
    value,
    defaultValue,
    required: requiredValue,
    validationRules,
    context,
    onChange,
    onBlur,
  });

  const meta = useSmartFieldMeta({ field });
  const errorText = meta.meta.error ? st(meta.meta.error) : undefined;

  const inputClasses = [
    fieldSizeStyles[size],
    fieldInputHeightStyles[size],
    fieldVariantStyles[variant],
    className,
    meta.showError ? `border-red-500 ${fieldErrorFocusStyles}` : '',
    showButtons ? 'text-center' : '',
  ]
    .filter(Boolean)
    .join(' ');

  // Format number with precision
  const formatValue = useCallback(
    (val: number | undefined): string => {
      if (val === undefined || val === null || isNaN(val)) return '';
      return precision > 0 ? val.toFixed(precision) : String(val);
    },
    [precision],
  );

  // Parse string to number
  const parseValue = useCallback(
    (str: string): number | undefined => {
      if (str === '' || str === '-') return undefined;
      const num = parseFloat(str);
      if (isNaN(num)) return undefined;
      return precision > 0 ? parseFloat(num.toFixed(precision)) : num;
    },
    [precision],
  );

  // Clamp value to min/max
  const clampValue = useCallback(
    (val: number | undefined): number | undefined => {
      if (val === undefined) return undefined;
      let result = val;
      if (min !== undefined && result < min) result = min;
      if (max !== undefined && result > max) result = max;
      return result;
    },
    [min, max],
  );

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const parsed = parseValue(e.target.value);
    field.setValue(parsed);
  };

  const handleBlur = () => {
    // Clamp on blur
    const clamped = clampValue(field.value);
    if (clamped !== field.value) {
      field.setValue(clamped);
    }
    field.onBlur();
  };

  const increment = () => {
    if (disabledValue) return;
    const currentValue = field.value ?? 0;
    const newValue = clampValue(currentValue + step);
    field.setValue(newValue);
  };

  const decrement = () => {
    if (disabledValue) return;
    const currentValue = field.value ?? 0;
    const newValue = clampValue(currentValue - step);
    field.setValue(newValue);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (!keyboard) return;
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      increment();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      decrement();
    }
  };

  if (!isVisible) {
    return null;
  }

  const buttonClass = `
    flex items-center justify-center h-7 w-7 rounded text-gray-400 hover:text-gray-600 hover:bg-gray-100
    disabled:opacity-50 disabled:cursor-not-allowed transition-colors
  `;

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      helpText={helpTextText}
      error={meta.showError ? errorText : undefined}
      className="mb-4"
    >
      <div className="relative flex items-center">
        {/* Prefix */}
        {prefix && (
          <span className="pointer-events-none absolute left-3 text-sm text-gray-500">
            {prefix}
          </span>
        )}

        {/* Decrement button */}
        {showButtons && (
          <button
            type="button"
            onClick={decrement}
            disabled={disabledValue || (min !== undefined && (field.value ?? 0) <= min)}
            className={`${buttonClass} absolute top-1/2 left-1 -translate-y-1/2`}
            tabIndex={-1}
          >
            <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 12H4" />
            </svg>
          </button>
        )}

        <input
          ref={inputRef}
          id={name}
          name={name}
          type="text"
          inputMode="decimal"
          value={formatValue(field.value)}
          placeholder={placeholderText}
          disabled={disabledValue}
          className={`w-full ${inputClasses} ${showButtons ? 'px-9' : ''} ${prefix ? 'pl-8' : ''} ${suffix ? 'pr-8' : ''}`}
          onChange={handleChange}
          onBlur={handleBlur}
          onFocus={onFocus}
          onKeyDown={handleKeyDown}
          aria-required={requiredValue}
          aria-valuemin={min}
          aria-valuemax={max}
          aria-valuenow={field.value}
        />

        {/* Increment button */}
        {showButtons && (
          <button
            type="button"
            onClick={increment}
            disabled={disabledValue || (max !== undefined && (field.value ?? 0) >= max)}
            className={`${buttonClass} absolute top-1/2 right-1 -translate-y-1/2`}
            tabIndex={-1}
          >
            <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 4v16m8-8H4"
              />
            </svg>
          </button>
        )}

        {/* Suffix */}
        {suffix && !showButtons && (
          <span className="pointer-events-none absolute right-3 text-sm text-gray-500">
            {suffix}
          </span>
        )}
      </div>

      {/* Range indicator */}
      {(min !== undefined || max !== undefined) && (
        <div className="mt-1 text-xs text-gray-500">
          {min !== undefined && max !== undefined
            ? `范围: ${min} - ${max}`
            : min !== undefined
              ? `最小值: ${min}`
              : `最大值: ${max}`}
        </div>
      )}
    </FieldBase>
  );
};

export { NumberInput };
export default NumberInput;
