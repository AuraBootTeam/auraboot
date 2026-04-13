// web-admin/app/components/base-fields/BaseInput.tsx
import * as React from 'react';
import type { FieldAdapter } from '~/ui/field-adapter';
import { Input } from '~/ui/ui/input';
import { FieldBase } from '~/ui/ui/field-base';
import { fieldErrorFocusStyles } from '~/ui/ui/field-styles';
import { cn } from '~/utils/cn';

export interface BaseInputProps {
  /** Field Adapter */
  adapter: FieldAdapter<string>;
  /** Field name */
  name: string;
  /** Label text */
  label?: string;
  /** Placeholder text */
  placeholder?: string;
  /** Help text */
  helpText?: string;
  /** Input type */
  type?: 'text' | 'email' | 'password' | 'url' | 'number';
  /** Maximum length */
  maxLength?: number;
  /** Label position */
  labelPosition?: 'top' | 'left' | 'right';
  /** Display inline */
  inline?: boolean;
  /** Custom className */
  className?: string;
  /** Custom input className */
  inputClassName?: string;
}

export function BaseInput({
  adapter,
  name,
  label,
  placeholder,
  helpText,
  type = 'text',
  maxLength,
  labelPosition = 'top',
  inline = false,
  className,
  inputClassName,
}: BaseInputProps) {
  return (
    <FieldBase
      id={name}
      label={label}
      required={adapter.required}
      helpText={!adapter.error ? helpText : undefined}
      error={adapter.error}
      labelPosition={labelPosition}
      inline={inline}
      className={className}
    >
      <Input
        id={name}
        name={name}
        type={type}
        value={adapter.value ?? ''}
        onChange={(e) => adapter.setValue(e.target.value)}
        onBlur={adapter.onBlur}
        onFocus={adapter.onFocus}
        placeholder={placeholder}
        disabled={adapter.disabled}
        readOnly={adapter.readOnly}
        maxLength={maxLength}
        className={cn(adapter.error && fieldErrorFocusStyles, inputClassName)}
      />
    </FieldBase>
  );
}

export default BaseInput;
