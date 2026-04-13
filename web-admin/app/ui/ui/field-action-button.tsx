import * as React from 'react';
import { cn } from '~/utils/cn';

export interface FieldActionButtonProps extends React.ComponentPropsWithoutRef<'button'> {
  size?: 'sm' | 'md';
  variant?: 'ghost' | 'solid';
  iconOnly?: boolean;
}

export const FieldActionButton = React.forwardRef<HTMLButtonElement, FieldActionButtonProps>(
  ({ size = 'sm', variant = 'ghost', iconOnly = false, className, ...props }, ref) => {
    const sizeClass = iconOnly
      ? size === 'md'
        ? 'h-8 w-8'
        : 'h-7 w-7'
      : size === 'md'
        ? 'px-2 py-1 text-sm'
        : 'p-1';
    const iconOnlyClass = iconOnly ? 'p-0 inline-flex items-center justify-center' : '';
    const variantClass =
      variant === 'solid'
        ? 'bg-blue-500 text-white hover:bg-blue-600'
        : 'text-gray-400 hover:text-gray-600 hover:bg-gray-100 dark:hover:text-gray-300 dark:hover:bg-gray-700';

    return (
      <button
        ref={ref}
        className={cn(
          'rounded transition-colors disabled:cursor-not-allowed disabled:opacity-50',
          sizeClass,
          iconOnlyClass,
          variantClass,
          className,
        )}
        {...props}
      />
    );
  },
);

FieldActionButton.displayName = 'FieldActionButton';
