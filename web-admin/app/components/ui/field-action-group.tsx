import * as React from 'react';
import { cn } from '~/utils/cn';

export interface FieldActionGroupProps extends React.ComponentPropsWithoutRef<'div'> {}

export const FieldActionGroup = React.forwardRef<HTMLDivElement, FieldActionGroupProps>(
  ({ className, ...props }, ref) => (
    <div ref={ref} className={cn('flex items-center gap-1', className)} {...props} />
  ),
);

FieldActionGroup.displayName = 'FieldActionGroup';
