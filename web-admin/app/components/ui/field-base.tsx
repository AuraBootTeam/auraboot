import * as React from 'react';
import { cn } from '~/utils/cn';
import { Label } from '~/components/ui/label';
import { FieldError, FieldHelp } from '~/components/ui/field-meta';

export interface FieldBaseProps {
  id?: string;
  label?: React.ReactNode;
  required?: boolean;
  helpText?: React.ReactNode;
  error?: React.ReactNode;
  inline?: boolean;
  labelPosition?: 'top' | 'left' | 'right';
  className?: string;
  labelClassName?: string;
  controlClassName?: string;
  onLabelClick?: React.MouseEventHandler<HTMLLabelElement>;
  children: React.ReactNode;
}

export const FieldBase = React.forwardRef<HTMLDivElement, FieldBaseProps>(
  (
    {
      id,
      label,
      required,
      helpText,
      error,
      inline = false,
      labelPosition = 'top',
      className,
      labelClassName,
      controlClassName,
      onLabelClick,
      children,
    },
    ref,
  ) => {
    // When there is no label, no error, and no helpText, render children
    // directly without a wrapper div to avoid empty spacing/margin artifacts
    // (e.g. when used inside ControlledFieldRenderer which provides its own label)
    if (!label && !error && !helpText) {
      return <>{children}</>;
    }

    const labelElement = label ? (
      <Label
        htmlFor={id}
        className={cn(labelClassName, inline ? 'min-w-20 whitespace-nowrap' : 'mb-1')}
        onClick={onLabelClick}
      >
        {label}
        {required && <span className="ml-1 text-red-500">*</span>}
      </Label>
    ) : null;

    return (
      <div ref={ref} className={cn('w-full', className)}>
        {labelPosition === 'left' || inline ? (
          <div className="flex items-start gap-3">
            {labelElement}
            <div className={cn('flex-1', controlClassName)}>{children}</div>
          </div>
        ) : labelPosition === 'right' ? (
          <div className="flex items-center gap-2">
            <div className={controlClassName}>{children}</div>
            {labelElement}
          </div>
        ) : (
          <>
            {labelElement}
            <div className={controlClassName}>{children}</div>
          </>
        )}

        <FieldError message={error} />
        <FieldHelp message={helpText} />
      </div>
    );
  },
);

FieldBase.displayName = 'FieldBase';
