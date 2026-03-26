import * as React from 'react';
import { cn } from '~/utils/cn';
import { ErrorText } from '~/components/ui/error-text';
import { HelpText } from '~/components/ui/help-text';

export interface FieldErrorProps extends React.ComponentPropsWithoutRef<'p'> {
  message?: React.ReactNode;
}

export const FieldError = React.forwardRef<HTMLParagraphElement, FieldErrorProps>(
  ({ message, className, ...props }, ref) => {
    if (!message) return null;
    return (
      <ErrorText ref={ref} className={cn('mt-1', className)} {...props}>
        {message}
      </ErrorText>
    );
  },
);

FieldError.displayName = 'FieldError';

export interface FieldHelpProps extends React.ComponentPropsWithoutRef<'p'> {
  message?: React.ReactNode;
}

export const FieldHelp = React.forwardRef<HTMLParagraphElement, FieldHelpProps>(
  ({ message, className, ...props }, ref) => {
    if (!message) return null;
    return (
      <HelpText ref={ref} className={cn('mt-1', className)} {...props}>
        {message}
      </HelpText>
    );
  },
);

FieldHelp.displayName = 'FieldHelp';
