import React, { useState } from 'react';

export interface FormSectionProps {
  title: string;
  description?: string;
  collapsible?: boolean;
  defaultCollapsed?: boolean;
  hasError?: boolean;
  testId?: string;
  children: React.ReactNode;
}

/** Shared DSL section presentation. Collapsed fields stay mounted and retain their values. */
export function FormSection({
  title,
  description,
  collapsible = false,
  defaultCollapsed = false,
  hasError = false,
  testId,
  children,
}: FormSectionProps) {
  const [expanded, setExpanded] = useState(!defaultCollapsed);
  const heading = (
    <>
      <span className="text-text text-sm font-semibold">{title}</span>
      {description && <span className="text-text-2 mt-1 block text-xs">{description}</span>}
    </>
  );
  if (collapsible && title) {
    return (
      <details
        data-testid={testId}
        open={hasError || expanded}
        onToggle={(event) => setExpanded(event.currentTarget.open)}
        className="form-section border-border bg-panel rounded-card border shadow-sm"
      >
        <summary className="text-text marker:text-text-3 cursor-pointer px-5 py-4">
          {heading}
        </summary>
        <div className="px-5 pt-1 pb-5">{children}</div>
      </details>
    );
  }
  return (
    <section
      data-testid={testId}
      className="form-section border-border bg-panel rounded-card border p-5 shadow-sm"
    >
      {title && <div className="mb-4">{heading}</div>}
      {children}
    </section>
  );
}
