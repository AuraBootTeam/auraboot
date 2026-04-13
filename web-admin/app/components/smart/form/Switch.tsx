/**
 * Switch Component
 *
 * Toggle switch component for boolean values.
 *
 * @since 3.2.0
 */

import React from 'react';
import type { SwitchProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';

const sizeClasses = {
  small: 'w-8 h-4',
  default: 'w-11 h-6',
  large: 'w-14 h-7',
};

const toggleClasses = {
  small: 'h-3 w-3',
  default: 'h-5 w-5',
  large: 'h-6 w-6',
};

const translateClasses = {
  small: 'translate-x-4',
  default: 'translate-x-5',
  large: 'translate-x-7',
};

const Switch: React.FC<SwitchProps> = ({
  name,
  label,
  value,
  defaultValue,
  checked,
  defaultChecked,
  required = false,
  disabled = false,
  loading = false,
  size = 'default',
  checkedText = '',
  uncheckedText = '',
  checkedValue = true,
  uncheckedValue = false,
  className = '',
  validationRules = [],
  context,
  expressions = {},
  visible,
  onChange,
  onBlur,
  ...restProps
}) => {
  const st = useSmartText();

  const {
    labelText,
    helpText: helpTextText,
    required: requiredValue,
    disabled: disabledValue,
    visible: isVisible,
  } = useSmartFieldContract({
    label,
    required,
    disabled,
    expressions,
    context,
    visible,
  });

  // Determine initial value
  const initialValue =
    value !== undefined
      ? value
      : defaultValue !== undefined
        ? defaultValue
        : checked !== undefined
          ? checked
          : defaultChecked;

  const field = useSmartField<boolean>({
    name,
    value: initialValue,
    defaultValue: false,
    required: requiredValue,
    validationRules,
    context,
    onChange,
    onBlur,
  });

  const meta = useSmartFieldMeta({ field });
  const errorText = meta.meta.error ? st(meta.meta.error) : undefined;

  const isChecked = field.value === true || field.value === checkedValue;
  const isDisabled = disabledValue || loading;

  const handleToggle = () => {
    if (isDisabled) return;
    const newValue = isChecked ? uncheckedValue : checkedValue;
    field.setValue(newValue);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      handleToggle();
    }
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
      <div className={`flex items-center gap-3 ${className}`}>
        <button
          type="button"
          role="switch"
          aria-checked={isChecked}
          aria-disabled={isDisabled}
          disabled={isDisabled}
          onClick={handleToggle}
          onKeyDown={handleKeyDown}
          onBlur={() => field.onBlur()}
          className={`relative inline-flex items-center rounded-full transition-colors duration-200 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none ${sizeClasses[size]} ${isChecked ? 'bg-blue-600' : 'bg-gray-200'} ${isDisabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'} `}
        >
          {/* Loading spinner */}
          {loading && (
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="h-3 w-3 animate-spin rounded-full border-2 border-white border-t-transparent" />
            </div>
          )}

          {/* Toggle button */}
          <span
            className={`inline-block transform rounded-full bg-white shadow transition-transform duration-200 ${toggleClasses[size]} ${isChecked ? translateClasses[size] : 'translate-x-0.5'} `}
          />

          {/* Text inside switch (optional) */}
          {(checkedText || uncheckedText) && size !== 'small' && (
            <span
              className={`absolute inset-0 flex items-center ${isChecked ? 'justify-start pl-1' : 'justify-end pr-1'}`}
            >
              <span className="text-xs font-medium text-white">
                {isChecked ? checkedText : uncheckedText}
              </span>
            </span>
          )}
        </button>

        {/* External label text */}
        {(checkedText || uncheckedText) && (
          <span className="text-sm text-gray-600 dark:text-gray-400">
            {isChecked ? checkedText : uncheckedText}
          </span>
        )}
      </div>
    </FieldBase>
  );
};

export { Switch };
export default Switch;
