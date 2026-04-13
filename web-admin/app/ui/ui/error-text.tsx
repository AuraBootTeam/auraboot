import * as React from 'react';
import { cn } from '~/utils/cn';

export interface ErrorTextProps extends React.ComponentPropsWithoutRef<'p'> {}

const ErrorText = React.forwardRef<HTMLParagraphElement, ErrorTextProps>(
  ({ className, ...props }, ref) => (
    <p ref={ref} className={cn('text-sm text-red-600 dark:text-red-400', className)} {...props} />
  ),
);

ErrorText.displayName = 'ErrorText';

export { ErrorText };
