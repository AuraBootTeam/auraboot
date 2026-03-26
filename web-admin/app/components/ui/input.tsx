import * as React from 'react';
import { cn } from '~/utils/cn';
import { fieldControlBase } from '~/components/ui/field-styles';

export interface InputProps extends React.ComponentPropsWithoutRef<'input'> {}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type = 'text', ...props }, ref) => (
    <input
      ref={ref}
      type={type}
      className={cn(
        `flex ${fieldControlBase} border-gray-300 bg-white text-sm text-gray-900`,
        'placeholder:text-gray-400 focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:outline-none',
        'focus-visible:border-blue-500 disabled:cursor-not-allowed disabled:opacity-50',
        'dark:border-gray-600 dark:bg-gray-700 dark:text-white dark:placeholder:text-gray-400',
        className,
      )}
      {...props}
    />
  ),
);

Input.displayName = 'Input';

export { Input };
