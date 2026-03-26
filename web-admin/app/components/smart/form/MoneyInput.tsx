/**
 * MoneyInput Component
 *
 * Currency-aware number input with symbol prefix, thousand separators,
 * and optional base currency equivalent display.
 *
 * Activated by `extension.renderComponent: "money"` or `dataType: "money"`.
 *
 * @since 3.3.0
 */

import React, { useRef, useCallback, useMemo } from 'react';
import type { FormFieldProps } from '~/studio/domain/schema/smart-components';
import { useSmartField } from '~/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';
import {
  fieldInputHeightStyles,
  fieldSizeStyles,
  fieldVariantStyles,
  fieldErrorFocusStyles,
} from '~/components/ui/field-styles';

export interface MoneyInputProps extends FormFieldProps {
  min?: number;
  max?: number;
  precision?: number;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  readOnly?: boolean;
  /** ISO 4217 currency code, e.g. "usd" */
  currencyCode?: string;
  /** Display symbol, e.g. "$" */
  currencySymbol?: string;
  /** Base (home) currency symbol, e.g. "¥" */
  baseCurrencySymbol?: string;
  /** Exchange rate from this currency to base currency */
  exchangeRate?: number;
  /** Whether to show base currency equivalent line (default true) */
  showBaseEquivalent?: boolean;
  className?: string;
}

const MoneyInput: React.FC<MoneyInputProps> = ({
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
  precision = 2,
  size = 'medium',
  variant = 'default',
  currencyCode,
  currencySymbol = '',
  baseCurrencySymbol = '¥',
  exchangeRate,
  showBaseEquivalent = true,
  className = '',
  validationRules = [],
  context,
  expressions = {},
  visible,
  onChange,
  onBlur,
  onFocus,
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

  const isReadOnly = readOnly || disabledValue;

  // Intl formatter for display (with thousand separators)
  const displayFormatter = useMemo(
    () =>
      new Intl.NumberFormat('en-US', {
        minimumFractionDigits: precision,
        maximumFractionDigits: precision,
      }),
    [precision],
  );

  // Format number for display with thousand separators
  const formatDisplay = useCallback(
    (val: number | undefined): string => {
      if (val === undefined || val === null || isNaN(val)) return '';
      return displayFormatter.format(val);
    },
    [displayFormatter],
  );

  // Format value for edit mode (no thousand separators, just precision)
  const formatEdit = useCallback(
    (val: number | undefined): string => {
      if (val === undefined || val === null || isNaN(val)) return '';
      return precision > 0 ? val.toFixed(precision) : String(val);
    },
    [precision],
  );

  // Parse input string to number
  const parseValue = useCallback(
    (str: string): number | undefined => {
      // Strip thousand separators and whitespace
      const cleaned = str.replace(/[,\s]/g, '');
      if (cleaned === '' || cleaned === '-') return undefined;
      const num = parseFloat(cleaned);
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
    const clamped = clampValue(field.value);
    if (clamped !== field.value) {
      field.setValue(clamped);
    }
    field.onBlur();
  };

  // Compute base currency equivalent
  const baseEquivalent = useMemo(() => {
    if (!showBaseEquivalent || !exchangeRate || exchangeRate === 1) return null;
    if (field.value === undefined || field.value === null || isNaN(field.value)) return null;
    const converted = field.value * exchangeRate;
    return {
      amount: displayFormatter.format(converted),
      rate: exchangeRate.toFixed(4),
    };
  }, [field.value, exchangeRate, showBaseEquivalent, displayFormatter]);

  if (!isVisible) {
    return null;
  }

  const inputClasses = [
    fieldSizeStyles[size],
    fieldInputHeightStyles[size],
    fieldVariantStyles[variant],
    className,
    meta.showError ? `border-red-500 ${fieldErrorFocusStyles}` : '',
  ]
    .filter(Boolean)
    .join(' ');

  // Read-only display
  if (readOnly) {
    const displayText =
      field.value !== undefined && field.value !== null
        ? `${currencySymbol}${formatDisplay(field.value)}`
        : '-';

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        helpText={helpTextText}
        className="mb-4"
      >
        <div className="py-2 text-sm text-gray-900">
          <span>{displayText}</span>
          {baseEquivalent && (
            <span className="ml-2 text-xs text-gray-400">
              ≈ {baseCurrencySymbol}
              {baseEquivalent.amount} @ {baseEquivalent.rate}
            </span>
          )}
        </div>
      </FieldBase>
    );
  }

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
        {/* Currency symbol prefix */}
        {currencySymbol && (
          <span className="pointer-events-none absolute left-3 text-sm text-gray-500 select-none">
            {currencySymbol}
          </span>
        )}

        <input
          ref={inputRef}
          id={name}
          name={name}
          type="text"
          inputMode="decimal"
          value={formatEdit(field.value)}
          placeholder={placeholderText || '0.00'}
          disabled={disabledValue}
          className={`w-full ${inputClasses} ${currencySymbol ? 'pl-8' : ''} text-right`}
          onChange={handleChange}
          onBlur={handleBlur}
          onFocus={onFocus}
          aria-required={requiredValue}
          aria-valuemin={min}
          aria-valuemax={max}
          aria-valuenow={field.value}
        />
      </div>

      {/* Base currency equivalent */}
      {baseEquivalent && (
        <div className="mt-1 text-xs text-gray-400">
          ≈ {baseCurrencySymbol}
          {baseEquivalent.amount} @ {baseEquivalent.rate}
        </div>
      )}

      {/* Range indicator */}
      {(min !== undefined || max !== undefined) && (
        <div className="mt-1 text-xs text-gray-500">
          {min !== undefined && max !== undefined
            ? `${currencySymbol}${displayFormatter.format(min)} - ${currencySymbol}${displayFormatter.format(max)}`
            : min !== undefined
              ? `Min: ${currencySymbol}${displayFormatter.format(min)}`
              : `Max: ${currencySymbol}${displayFormatter.format(max!)}`}
        </div>
      )}
    </FieldBase>
  );
};

export { MoneyInput };
export default MoneyInput;
