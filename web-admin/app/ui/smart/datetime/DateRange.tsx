/**
 * DateRange - Date range picker for filter forms
 *
 * Outputs { start: string, end: string } value.
 * Supports defaultRange presets like THIS_MONTH, THIS_WEEK, LAST_MONTH.
 */

import React, { forwardRef, useEffect, useRef } from 'react';
import clsx from 'clsx';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/ui/ui/field-base';
import { FieldControl } from '~/ui/ui/field-control';
import { FieldActionGroup } from '~/ui/ui/field-action-group';
import { FieldActionButton } from '~/ui/ui/field-action-button';
import {
  fieldSizeStyles,
  fieldVariantStyles,
  fieldErrorFocusStyles,
  fieldInputHeightStyles,
  fieldFocusStyles,
  fieldControlBase,
} from '~/ui/ui/field-styles';

interface DateRangeValue {
  start?: string;
  end?: string;
}

type DefaultRange =
  | 'today'
  | 'yesterday'
  | 'this_week'
  | 'last_week'
  | 'this_month'
  | 'last_month'
  | 'this_quarter'
  | 'this_year';

interface DateRangeProps {
  name: string;
  label?: string;
  placeholder?: string;
  value?: DateRangeValue;
  defaultValue?: DateRangeValue;
  onChange?: (value: DateRangeValue | undefined) => void;
  onBlur?: () => void;
  onFocus?: () => void;
  onClear?: () => void;
  disabled?: boolean;
  required?: boolean;
  visible?: boolean | string;
  clearable?: boolean;
  inline?: boolean;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  defaultRange?: DefaultRange;
  minDate?: string;
  maxDate?: string;
  validationRules?: any[];
  expressions?: any;
  context?: any;
  className?: string;
}

const baseStyles = `${fieldControlBase} focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed`;

function computeDefaultRange(preset: DefaultRange): DateRangeValue {
  const today = new Date();
  const fmt = (d: Date) => {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  };

  switch (preset) {
    case 'today':
      return { start: fmt(today), end: fmt(today) };
    case 'yesterday': {
      const yesterday = new Date(today);
      yesterday.setDate(yesterday.getDate() - 1);
      return { start: fmt(yesterday), end: fmt(yesterday) };
    }
    case 'this_week': {
      const day = today.getDay();
      const monday = new Date(today);
      monday.setDate(today.getDate() - (day === 0 ? 6 : day - 1));
      const sunday = new Date(monday);
      sunday.setDate(monday.getDate() + 6);
      return { start: fmt(monday), end: fmt(sunday) };
    }
    case 'last_week': {
      const day = today.getDay();
      const lastMonday = new Date(today);
      lastMonday.setDate(today.getDate() - (day === 0 ? 6 : day - 1) - 7);
      const lastSunday = new Date(lastMonday);
      lastSunday.setDate(lastMonday.getDate() + 6);
      return { start: fmt(lastMonday), end: fmt(lastSunday) };
    }
    case 'this_month': {
      const start = new Date(today.getFullYear(), today.getMonth(), 1);
      const end = new Date(today.getFullYear(), today.getMonth() + 1, 0);
      return { start: fmt(start), end: fmt(end) };
    }
    case 'last_month': {
      const start = new Date(today.getFullYear(), today.getMonth() - 1, 1);
      const end = new Date(today.getFullYear(), today.getMonth(), 0);
      return { start: fmt(start), end: fmt(end) };
    }
    case 'this_quarter': {
      const q = Math.floor(today.getMonth() / 3);
      const start = new Date(today.getFullYear(), q * 3, 1);
      const end = new Date(today.getFullYear(), q * 3 + 3, 0);
      return { start: fmt(start), end: fmt(end) };
    }
    case 'this_year': {
      const start = new Date(today.getFullYear(), 0, 1);
      const end = new Date(today.getFullYear(), 11, 31);
      return { start: fmt(start), end: fmt(end) };
    }
    default:
      return {};
  }
}

export const DateRange = forwardRef<HTMLDivElement, DateRangeProps>(
  (
    {
      name,
      label: propLabel,
      placeholder: propPlaceholder,
      value: propValue,
      defaultValue,
      onChange,
      onBlur,
      onFocus,
      onClear,
      disabled: propDisabled,
      required: propRequired,
      visible,
      clearable = true,
      inline = true,
      size = 'medium',
      variant = 'default',
      defaultRange,
      minDate,
      maxDate,
      validationRules = [],
      expressions = {},
      context = {},
      className,
    },
    ref,
  ) => {
    const st = useSmartText();
    const initializedRef = useRef(false);

    const {
      labelText,
      placeholderText: _placeholderText,
      required: requiredValue,
      disabled: disabledValue,
      visible: isVisible,
    } = useSmartFieldContract({
      label: propLabel,
      placeholder: propPlaceholder,
      required: propRequired,
      disabled: propDisabled,
      expressions,
      context,
      visible,
    });

    const field = useSmartField<DateRangeValue>({
      name,
      value: propValue,
      defaultValue: defaultValue,
      onChange,
      onBlur,
      validationRules,
      required: requiredValue,
      context,
    });

    // Apply defaultRange on mount if no value is set
    useEffect(() => {
      if (initializedRef.current) return;
      if (defaultRange && !field.value?.start && !field.value?.end) {
        const range = computeDefaultRange(defaultRange);
        field.setValue(range);
        initializedRef.current = true;
      }
    }, [defaultRange]); // eslint-disable-line react-hooks/exhaustive-deps

    const meta = useSmartFieldMeta({ field, externalError: undefined });
    const errorText = meta.meta.error ? st(meta.meta.error) : undefined;
    const finalVariant = meta.showError ? 'error' : variant;

    if (!isVisible) return null;

    const rangeValue = (field.value as DateRangeValue) ?? {};

    const handleStartChange = (e: React.ChangeEvent<HTMLInputElement>) => {
      const newStart = e.target.value;
      field.setValue({ ...rangeValue, start: newStart });
    };

    const handleEndChange = (e: React.ChangeEvent<HTMLInputElement>) => {
      const newEnd = e.target.value;
      field.setValue({ ...rangeValue, end: newEnd });
    };

    const handleClear = () => {
      field.setValue(undefined as any);
      onClear?.();
    };

    const hasValue = rangeValue.start || rangeValue.end;

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        inline={inline}
        error={meta.showError ? errorText : undefined}
      >
        <FieldControl
          inline={inline}
          rightSlot={
            clearable && hasValue && !disabledValue ? (
              <FieldActionGroup>
                <FieldActionButton type="button" onClick={handleClear} iconOnly>
                  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </FieldActionButton>
              </FieldActionGroup>
            ) : undefined
          }
        >
          <div
            ref={ref}
            className={clsx('flex w-full items-center gap-2', className)}
            data-testid={`daterange-${name}`}
          >
            <input
              type="date"
              id={`${name}_start`}
              name={`${name}_start`}
              value={rangeValue.start || ''}
              min={minDate}
              max={rangeValue.end || maxDate}
              disabled={disabledValue}
              onChange={handleStartChange}
              onBlur={field.onBlur}
              onFocus={onFocus}
              data-testid={`daterange-${name}-start`}
              className={clsx(
                baseStyles,
                fieldSizeStyles[size],
                fieldInputHeightStyles[size],
                fieldVariantStyles[finalVariant],
                fieldFocusStyles,
                meta.showError && fieldErrorFocusStyles,
                'min-w-0 flex-1',
              )}
            />
            <span className="flex-shrink-0 text-gray-400">—</span>
            <input
              type="date"
              id={`${name}_end`}
              name={`${name}_end`}
              value={rangeValue.end || ''}
              min={rangeValue.start || minDate}
              max={maxDate}
              disabled={disabledValue}
              onChange={handleEndChange}
              onBlur={field.onBlur}
              onFocus={onFocus}
              data-testid={`daterange-${name}-end`}
              className={clsx(
                baseStyles,
                fieldSizeStyles[size],
                fieldInputHeightStyles[size],
                fieldVariantStyles[finalVariant],
                fieldFocusStyles,
                meta.showError && fieldErrorFocusStyles,
                'min-w-0 flex-1',
              )}
            />
          </div>
        </FieldControl>
      </FieldBase>
    );
  },
);

DateRange.displayName = 'DateRange';

export default DateRange;
