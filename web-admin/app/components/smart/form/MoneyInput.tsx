/**
 * MoneyInput Component
 *
 * Currency-aware number input with symbol prefix pill, thousand separators,
 * and optional base currency equivalent display.
 *
 * Features:
 * - Currency symbol shown as a gray background pill prefix inside the input
 * - Thousand separator formatting in display/readOnly mode (e.g., 12,345.67)
 * - Input mode: plain number input without separators, auto-format on blur
 * - Precision enforcement: truncate to N decimal places on blur
 * - ReadOnly mode: formatted value with currency symbol, no input border
 * - Range indicator below input when min/max are set
 *
 * Activated by `extension.renderComponent: "money"` or `dataType: "money"`.
 *
 * @since 3.3.0
 */

import React, { useRef, useCallback, useMemo, useState } from 'react';
import type { FormFieldProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';

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
  /** Base (home) currency symbol, e.g. "Y" */
  baseCurrencySymbol?: string;
  /** Exchange rate from this currency to base currency */
  exchangeRate?: number;
  /** Whether to show base currency equivalent line (default true) */
  showBaseEquivalent?: boolean;
  className?: string;
}

const SIZE_CONFIG = {
  small: { height: 'h-8', text: 'text-sm', pill: 'px-2 py-0.5 text-xs', input: 'px-2' },
  medium: { height: 'h-10', text: 'text-sm', pill: 'px-2.5 py-1 text-sm', input: 'px-3' },
  large: { height: 'h-12', text: 'text-base', pill: 'px-3 py-1.5 text-base', input: 'px-4' },
} as const;

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
  baseCurrencySymbol = '\u00a5',
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
  const [isFocused, setIsFocused] = useState(false);

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
  const sizeConfig = SIZE_CONFIG[size];

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
    setIsFocused(false);
    const clamped = clampValue(field.value);
    if (clamped !== field.value) {
      field.setValue(clamped);
    }
    field.onBlur();
  };

  const handleFocus = () => {
    setIsFocused(true);
    onFocus?.();
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

  // Determine the display value: formatted with separators when not focused, plain when editing
  const displayValue = isFocused ? formatEdit(field.value) : formatDisplay(field.value);

  const hasError = meta.showError;

  // Read-only display
  if (readOnly) {
    const hasValue = field.value !== undefined && field.value !== null && !isNaN(field.value);

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        helpText={helpTextText}
        className="mb-4"
      >
        <div className="flex items-baseline gap-1 py-2">
          {hasValue ? (
            <>
              {currencySymbol && (
                <span className="text-sm font-medium text-gray-500">{currencySymbol}</span>
              )}
              <span className="text-lg font-semibold tabular-nums text-gray-900">
                {formatDisplay(field.value)}
              </span>
            </>
          ) : (
            <span className="text-sm text-gray-400">-</span>
          )}
          {baseEquivalent && (
            <span className="ml-2 text-xs text-gray-400">
              \u2248 {baseCurrencySymbol}
              {baseEquivalent.amount} @ {baseEquivalent.rate}
            </span>
          )}
        </div>
      </FieldBase>
    );
  }

  // Variant-based border colors
  const borderColor = hasError
    ? 'border-red-400'
    : isFocused
      ? 'border-blue-500 ring-2 ring-blue-500/20'
      : 'border-gray-300 hover:border-gray-400';

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      helpText={helpTextText}
      error={hasError ? errorText : undefined}
      className="mb-4"
    >
      <div
        className={`flex items-center overflow-hidden rounded-lg border bg-white shadow-sm transition-all duration-150 ${borderColor} ${sizeConfig.height} ${className}`}
      >
        {/* Currency symbol pill prefix */}
        {currencySymbol && (
          <div
            className={`flex h-full shrink-0 items-center border-r border-gray-200 bg-gray-50 font-medium text-gray-500 select-none ${sizeConfig.pill}`}
          >
            {currencySymbol}
          </div>
        )}

        <input
          ref={inputRef}
          id={name}
          name={name}
          type="text"
          inputMode="decimal"
          value={displayValue}
          placeholder={placeholderText || '0.00'}
          disabled={disabledValue}
          className={`h-full w-full border-none bg-transparent text-right tabular-nums text-gray-900 outline-none placeholder:text-gray-400 ${sizeConfig.input} ${sizeConfig.text}`}
          onChange={handleChange}
          onBlur={handleBlur}
          onFocus={handleFocus}
          aria-required={requiredValue}
          aria-valuemin={min}
          aria-valuemax={max}
          aria-valuenow={field.value}
        />
      </div>

      {/* Base currency equivalent */}
      {baseEquivalent && (
        <div className="mt-1.5 flex items-center gap-1 text-xs text-gray-400">
          <svg className="h-3 w-3" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M1 8L4 4L7 6L11 2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <span>
            \u2248 {baseCurrencySymbol}
            {baseEquivalent.amount}
          </span>
          <span className="text-gray-300">|</span>
          <span>Rate: {baseEquivalent.rate}</span>
        </div>
      )}

      {/* Range indicator */}
      {(min !== undefined || max !== undefined) && (
        <div className="mt-1.5 flex items-center gap-1 text-xs text-gray-400">
          <svg className="h-3 w-3" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M2 6h8M2 6l2-2M2 6l2 2M10 6l-2-2M10 6l-2 2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <span>
            {min !== undefined && max !== undefined
              ? `Range: ${currencySymbol}${displayFormatter.format(min)} \u2013 ${currencySymbol}${displayFormatter.format(max)}`
              : min !== undefined
                ? `Min: ${currencySymbol}${displayFormatter.format(min)}`
                : `Max: ${currencySymbol}${displayFormatter.format(max!)}`}
          </span>
        </div>
      )}
    </FieldBase>
  );
};

export { MoneyInput };
export default MoneyInput;
