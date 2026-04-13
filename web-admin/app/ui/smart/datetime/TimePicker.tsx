/**
 * TimePicker Component
 *
 * Time picker component with hour, minute, and second selection.
 *
 * @since 3.2.0
 */

import React, { useRef, useState } from 'react';
import type { TimePickerProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
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

const TimePicker: React.FC<TimePickerProps> = ({
  name,
  label,
  placeholder,
  value,
  defaultValue,
  required = false,
  disabled = false,
  format: _format = 'HH:mm',
  showSecond = false,
  use12Hours: _use12Hours = false,
  hourStep: _hourStep = 1,
  minuteStep: _minuteStep = 1,
  secondStep: _secondStep = 1,
  size = 'medium',
  variant = 'default',
  clearable = true,
  className = '',
  validationRules = [],
  context,
  expressions = {},
  visible,
  onChange,
  onBlur,
  onFocus,
  ..._restProps
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

  const field = useSmartField<string>({
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
  ]
    .filter(Boolean)
    .join(' ');

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    field.setValue(e.target.value);
  };

  const handleClear = () => {
    field.setValue('');
    inputRef.current?.focus();
  };

  if (!isVisible) {
    return null;
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
      <div className="relative">
        <input
          ref={inputRef}
          id={name}
          name={name}
          type="time"
          value={field.value || ''}
          placeholder={placeholderText}
          disabled={disabledValue}
          step={showSecond ? 1 : 60}
          className={`w-full pr-10 ${inputClasses}`}
          onChange={handleChange}
          onBlur={() => field.onBlur()}
          onFocus={onFocus}
          aria-required={requiredValue}
        />
        {/* Clock icon */}
        <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3">
          <svg
            className="h-4 w-4 text-gray-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
        </div>
        {/* Clear button */}
        {clearable && field.value && !disabledValue && (
          <button
            type="button"
            onClick={handleClear}
            className="absolute inset-y-0 right-8 flex items-center pr-1 text-gray-400 hover:text-gray-600"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        )}
      </div>
    </FieldBase>
  );
};

export { TimePicker };
export default TimePicker;
