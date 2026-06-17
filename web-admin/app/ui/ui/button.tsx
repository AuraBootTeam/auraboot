import * as React from 'react';
import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '~/utils/cn';

const buttonVariants = cva(
  'inline-flex items-center justify-center whitespace-nowrap rounded-control text-body font-medium transition-colors focus-visible:outline-none focus-visible:shadow-focus disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        default:
          'bg-accent text-white hover:bg-accent-hover dark:bg-blue-500 dark:hover:bg-blue-600',
        destructive:
          'bg-status-red text-white hover:bg-red-700 dark:bg-red-500 dark:hover:bg-red-600',
        outline:
          'border border-border-strong bg-panel text-text hover:bg-hover dark:border-gray-600 dark:bg-gray-800 dark:hover:bg-gray-700 dark:text-gray-100',
        secondary:
          'bg-subtle text-text hover:bg-hover dark:bg-gray-700 dark:text-gray-100 dark:hover:bg-gray-600',
        ghost: 'hover:bg-hover hover:text-text dark:hover:bg-gray-800 dark:hover:text-gray-100',
        link: 'text-accent underline-offset-4 hover:underline dark:text-blue-400',
      },
      size: {
        default: 'h-[var(--ds-control-md)] px-4 py-2',
        sm: 'h-[var(--ds-control-sm)] px-3 text-aux',
        lg: 'h-[var(--ds-control-lg)] px-8 text-section',
        icon: 'h-[var(--ds-control-md)] w-[var(--ds-control-md)]',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button';
    return (
      <Comp className={cn(buttonVariants({ variant, size, className }))} ref={ref} {...props} />
    );
  },
);

Button.displayName = 'Button';

export { Button, buttonVariants };
