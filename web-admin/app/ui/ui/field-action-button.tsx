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
        ? 'h-[var(--ds-control-md)] w-[var(--ds-control-md)]'
        : 'h-[var(--ds-control-sm)] w-[var(--ds-control-sm)]'
      : size === 'md'
        ? 'px-2 py-1 text-body'
        : 'p-1';
    const iconOnlyClass = iconOnly ? 'p-0 inline-flex items-center justify-center' : '';
    const variantClass =
      variant === 'solid'
        ? 'bg-accent text-white hover:bg-accent-hover'
        : 'text-text-3 hover:text-text-2 hover:bg-hover dark:hover:text-gray-300 dark:hover:bg-gray-700';

    return (
      <button
        ref={ref}
        className={cn(
          'rounded-control focus-visible:shadow-focus transition-colors focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50',
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
