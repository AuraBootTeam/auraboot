import * as React from 'react';
import * as SwitchPrimitive from '@radix-ui/react-switch';
import { cn } from '~/utils/cn';

const Switch = React.forwardRef<
  React.ComponentRef<typeof SwitchPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof SwitchPrimitive.Root>
>(({ className, ...props }, ref) => (
  <SwitchPrimitive.Root
    className={cn(
      'peer inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent shadow-sm transition-colors',
      'focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 focus-visible:outline-none',
      'disabled:cursor-not-allowed disabled:opacity-50',
      'data-[state=checked]:bg-blue-600 data-[state=unchecked]:bg-gray-200',
      'dark:data-[state=checked]:bg-blue-500 dark:data-[state=unchecked]:bg-gray-600',
      className,
    )}
    {...props}
    ref={ref}
  >
    <SwitchPrimitive.Thumb
      className={cn(
        'pointer-events-none block h-4 w-4 rounded-full bg-white shadow-lg ring-0 transition-transform',
        'data-[state=checked]:translate-x-4 data-[state=unchecked]:translate-x-0',
      )}
    />
  </SwitchPrimitive.Root>
));

Switch.displayName = SwitchPrimitive.Root.displayName;

export { Switch };
