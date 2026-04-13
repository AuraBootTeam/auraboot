// web-admin/app/components/base-fields/BaseSelect.tsx
import * as React from 'react';
import type { FieldAdapter } from '~/ui/field-adapter';
import { FieldBase } from '~/ui/ui/field-base';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '~/ui/ui/select';
import { fieldErrorFocusStyles } from '~/ui/ui/field-styles';
import { cn } from '~/utils/cn';

export interface SelectOption {
  label: string;
  value: string;
  disabled?: boolean;
}

export interface BaseSelectProps {
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
  /** Select options */
  options: SelectOption[];
  /** Label position */
  labelPosition?: 'top' | 'left' | 'right';
  /** Display inline */
  inline?: boolean;
  /** Custom className */
  className?: string;
  /** Custom trigger className */
  triggerClassName?: string;
}

export function BaseSelect({
  adapter,
  name,
  label,
  placeholder,
  helpText,
  options,
  labelPosition = 'top',
  inline = false,
  className,
  triggerClassName,
}: BaseSelectProps) {
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
      <Select
        value={adapter.value ?? ''}
        onValueChange={adapter.setValue}
        disabled={adapter.disabled}
      >
        <SelectTrigger
          id={name}
          onBlur={adapter.onBlur}
          onFocus={adapter.onFocus}
          className={cn(adapter.error && fieldErrorFocusStyles, triggerClassName)}
        >
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
        <SelectContent>
          {options.map((opt) => (
            <SelectItem key={opt.value} value={opt.value} disabled={opt.disabled}>
              {opt.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </FieldBase>
  );
}

export default BaseSelect;
