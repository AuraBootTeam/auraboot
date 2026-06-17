import * as React from 'react';
import * as SwitchPrimitive from '@radix-ui/react-switch';
import { cn } from '~/utils/cn';

const Switch = React.forwardRef<
  React.ComponentRef<typeof SwitchPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SwitchPrimitive.Root>
>(({ className, ...props }, ref) => (
  <SwitchPrimitive.Root
    className={cn(
      'peer rounded-pill shadow-card inline-flex h-5 w-9 shrink-0 cursor-pointer items-center border-2 border-transparent transition-colors',
      'focus-visible:shadow-focus focus-visible:outline-none',
      'disabled:cursor-not-allowed disabled:opacity-50',
      'data-[state=checked]:bg-accent data-[state=unchecked]:bg-border-strong',
      'dark:data-[state=checked]:bg-blue-500 dark:data-[state=unchecked]:bg-gray-600',
      className,
    )}
    {...props}
    ref={ref}
  >
    <SwitchPrimitive.Thumb
      className={cn(
        'rounded-pill bg-panel shadow-card pointer-events-none block h-4 w-4 ring-0 transition-transform',
        'data-[state=checked]:translate-x-4 data-[state=unchecked]:translate-x-0',
      )}
    />
  </SwitchPrimitive.Root>
));

Switch.displayName = SwitchPrimitive.Root.displayName;

export { Switch };
