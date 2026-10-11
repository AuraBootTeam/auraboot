import { cloneElement, useId, type ReactElement } from 'react';

export type LabeledControl = ReactElement<{ id?: string; 'aria-describedby'?: string }>;
export function PublicationField({ name, child, help }: { name: string; child: LabeledControl; help?: string }) {
  const id = useId();
  const describedBy = [child.props['aria-describedby'], help ? `${id}-help` : undefined].filter(Boolean).join(' ') || undefined;
  return <div className="space-y-1 text-sm text-text"><label htmlFor={id} className="block">{name}</label>
    {cloneElement(child, { id, 'aria-describedby': describedBy })}
    {help && <p id={`${id}-help`} className="text-xs text-text-2">{help}</p>}</div>;
}

