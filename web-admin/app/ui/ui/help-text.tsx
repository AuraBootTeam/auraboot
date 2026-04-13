import * as React from 'react';
import { cn } from '~/utils/cn';

export interface HelpTextProps extends React.ComponentPropsWithoutRef<'p'> {}

const HelpText = React.forwardRef<HTMLParagraphElement, HelpTextProps>(
  ({ className, ...props }, ref) => (
    <p ref={ref} className={cn('text-sm text-gray-500 dark:text-gray-400', className)} {...props} />
  ),
);

HelpText.displayName = 'HelpText';

export { HelpText };
