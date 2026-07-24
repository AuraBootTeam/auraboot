import * as React from 'react';
import { cn } from '~/utils/cn';
import { Label } from '~/ui/ui/label';
import { FieldError, FieldHelp } from '~/ui/ui/field-meta';

/**
 * Set by a field wrapper that renders the field-level error message itself (today:
 * `ControlledFieldRenderer`, which owns the label / error / help chrome so every control
 * gets identical layout). When true, `FieldBase` suppresses its own `<FieldError>` so the
 * same message is not painted twice — once by the wrapper from the form-context error and
 * once by the control from its own `validationRules` run over the same value.
 *
 * The wrapper sets this only while it actually has an error to show, so a control's own
 * on-blur validation feedback (which the wrapper knows nothing about) still renders.
 */
export const FieldErrorOwnedByWrapperContext = React.createContext(false);

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
    // De-duplicate the validation message: when a wrapper already renders this field's
    // error (ControlledFieldRenderer), drop our copy instead of showing it twice.
    const errorOwnedByWrapper = React.useContext(FieldErrorOwnedByWrapperContext);
    const visibleError = errorOwnedByWrapper ? undefined : error;

    // When there is no label, no error, and no helpText, render children
    // directly without a wrapper div to avoid empty spacing/margin artifacts
    // (e.g. when used inside ControlledFieldRenderer which provides its own label)
    if (!label && !visibleError && !helpText) {
      return <>{children}</>;
    }

    const labelElement = label ? (
      <Label
        htmlFor={id}
        className={cn(labelClassName, inline ? 'min-w-20 whitespace-nowrap' : 'mb-1')}
        onClick={onLabelClick}
      >
        {label}
        {required && <span className="text-status-red ml-1">*</span>}
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

        <FieldError message={visibleError} />
        <FieldHelp message={helpText} />
      </div>
    );
  },
);

FieldBase.displayName = 'FieldBase';
