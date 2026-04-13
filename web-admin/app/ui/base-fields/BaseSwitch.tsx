// web-admin/app/components/base-fields/BaseSwitch.tsx
import * as React from 'react';
import type { FieldAdapter } from '~/ui/field-adapter';
import { FieldBase } from '~/ui/ui/field-base';
import { Switch } from '~/ui/ui/switch';
import { cn } from '~/utils/cn';

export interface BaseSwitchProps {
  /** Field Adapter */
  adapter: FieldAdapter<boolean>;
  /** Field name */
  name: string;
  /** Label text */
  label?: string;
  /** Help text */
  helpText?: string;
  /** Label position - for switch, default is 'right' */
  labelPosition?: 'top' | 'left' | 'right';
  /** Display inline */
  inline?: boolean;
  /** Custom className */
  className?: string;
  /** Custom switch className */
  switchClassName?: string;
}

export function BaseSwitch({
  adapter,
  name,
  label,
  helpText,
  labelPosition = 'right',
  inline = false,
  className,
  switchClassName,
}: BaseSwitchProps) {
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
      <Switch
        id={name}
        checked={adapter.value ?? false}
        onCheckedChange={adapter.setValue}
        disabled={adapter.disabled}
        className={cn(switchClassName)}
      />
    </FieldBase>
  );
}

export default BaseSwitch;
