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
import { FieldBase } from '~/ui/ui/field-base';
import {
  fieldInputHeightStyles,
  fieldSizeStyles,
  fieldVariantStyles,
  fieldErrorFocusStyles,
} from '~/ui/ui/field-styles';

function toFiniteNumber(value: unknown): number | undefined {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : undefined;
  }
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return undefined;
    const parsed = Number(trimmed);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}

const NumberInput: React.FC<NumberInputProps> = ({
  name,
  label,
  placeholder,
  value,
  defaultValue,
  required = false,
  disabled = false,
  readOnly = false,
  min,
  max,
  step = 1,
  precision = 0,
  size = 'medium',
  variant = 'default',
  showButtons = true,
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
    (val: unknown): string => {
      const normalized = toFiniteNumber(val);
      if (normalized === undefined) return '';
      return precision > 0 ? normalized.toFixed(precision) : String(normalized);
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
    const currentValue = toFiniteNumber(field.value);
    const clamped = clampValue(currentValue);
    if (clamped !== currentValue) {
      field.setValue(clamped);
    }
    field.onBlur();
  };

  const increment = () => {
    if (disabledValue || readOnly) return;
    const currentValue = toFiniteNumber(field.value) ?? 0;
    const newValue = clampValue(currentValue + step);
    field.setValue(newValue);
  };

  const decrement = () => {
    if (disabledValue || readOnly) return;
    const currentValue = toFiniteNumber(field.value) ?? 0;
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
  const numericValue = toFiniteNumber(field.value);

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
            disabled={
              disabledValue || readOnly || (min !== undefined && (numericValue ?? 0) <= min)
            }
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
          readOnly={readOnly}
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
            disabled={
              disabledValue || readOnly || (max !== undefined && (numericValue ?? 0) >= max)
            }
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
