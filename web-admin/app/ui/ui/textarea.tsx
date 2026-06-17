import * as React from 'react';
import { cn } from '~/utils/cn';
import { fieldControlBase, fieldVariantStyles, fieldFocusStyles } from '~/ui/ui/field-styles';

export interface TextareaProps extends React.ComponentPropsWithoutRef<'textarea'> {}

const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, ...props }, ref) => (
    <textarea
      ref={ref}
      className={cn(
        'text-body flex min-h-[80px] px-3 py-2',
        fieldControlBase,
        fieldVariantStyles.default,
        'placeholder:text-text-3 dark:placeholder:text-gray-400',
        fieldFocusStyles,
        'disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  ),
);

Textarea.displayName = 'Textarea';

export { Textarea };
