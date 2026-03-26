import * as React from 'react';
import { cn } from '~/utils/cn';
import { fieldContainerBase } from '~/components/ui/field-styles';

export interface FieldControlProps extends React.ComponentPropsWithoutRef<'div'> {
  inline?: boolean;
  rightSlot?: React.ReactNode;
  error?: React.ReactNode;
}

export const FieldControl = React.forwardRef<HTMLDivElement, FieldControlProps>(
  ({ inline = false, rightSlot, error: _error, className, children, ...props }, ref) => (
    <div ref={ref} className={cn(fieldContainerBase, inline && 'flex-1', className)} {...props}>
      {children}
      {rightSlot ? (
        <div className="absolute top-1/2 right-2 flex -translate-y-1/2 items-center gap-1">
          {rightSlot}
        </div>
      ) : null}
    </div>
  ),
);

FieldControl.displayName = 'FieldControl';
