// web-admin/app/components/base-fields/BaseTextarea.tsx
import * as React from 'react';
import type { FieldAdapter } from '~/components/field-adapter';
import { FieldBase } from '~/components/ui/field-base';
import { Textarea } from '~/components/ui/textarea';
import { fieldErrorFocusStyles } from '~/components/ui/field-styles';
import { cn } from '~/utils/cn';

export interface BaseTextareaProps {
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
  /** Number of rows */
  rows?: number;
  /** Maximum length */
  maxLength?: number;
  /** Label position */
  labelPosition?: 'top' | 'left' | 'right';
  /** Display inline */
  inline?: boolean;
  /** Custom className */
  className?: string;
  /** Custom textarea className */
  textareaClassName?: string;
}

export function BaseTextarea({
  adapter,
  name,
  label,
  placeholder,
  helpText,
  rows = 4,
  maxLength,
  labelPosition = 'top',
  inline = false,
  className,
  textareaClassName,
}: BaseTextareaProps) {
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
      <Textarea
        id={name}
        name={name}
        value={adapter.value ?? ''}
        onChange={(e) => adapter.setValue(e.target.value)}
        onBlur={adapter.onBlur}
        onFocus={adapter.onFocus}
        placeholder={placeholder}
        disabled={adapter.disabled}
        readOnly={adapter.readOnly}
        rows={rows}
        maxLength={maxLength}
        className={cn(
          'resize-none px-3 py-2',
          adapter.error && fieldErrorFocusStyles,
          textareaClassName,
        )}
      />
    </FieldBase>
  );
}

export default BaseTextarea;
