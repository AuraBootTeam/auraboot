import * as React from 'react';
import { cn } from '~/utils/cn';
import {
  fieldControlBase,
  fieldVariantStyles,
  fieldInputHeightStyles,
  fieldSizeStyles,
  fieldFocusStyles,
} from '~/ui/ui/field-styles';

export interface InputProps extends React.ComponentPropsWithoutRef<'input'> {}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type = 'text', ...props }, ref) => (
    <input
      ref={ref}
      type={type}
      className={cn(
        'flex',
        fieldControlBase,
        fieldVariantStyles.default,
        fieldInputHeightStyles.medium,
        fieldSizeStyles.medium,
        'placeholder:text-text-3 dark:placeholder:text-gray-400',
        fieldFocusStyles,
        'disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  ),
);

Input.displayName = 'Input';

export { Input };
