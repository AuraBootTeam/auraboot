import React, { useState } from 'react';

export interface FormSectionProps {
  title: string;
  surface?: 'card' | 'plain';
  density?: 'comfortable' | 'compact';
  index?: string;
  description?: string;
  collapsible?: boolean;
  defaultCollapsed?: boolean;
  hasError?: boolean;
  testId?: string;
  style?: React.CSSProperties;
  children: React.ReactNode;
}

/** Shared DSL section presentation. Collapsed fields stay mounted and retain their values. */
export function FormSection({
  title,
  surface = 'card',
  density = 'comfortable',
  index,
  description,
  collapsible = false,
  defaultCollapsed = false,
  hasError = false,
  testId,
  style,
  children,
}: FormSectionProps) {
  const [expanded, setExpanded] = useState(!defaultCollapsed);
  const surfaceClass =
    surface === 'plain' ? '' : 'border-border bg-panel rounded-card border shadow-sm';
  const padding = density === 'compact' ? 'p-4' : 'p-5';
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
        style={style}
        open={hasError || expanded}
        onToggle={(event) => setExpanded(event.currentTarget.open)}
        className={`form-section @container/form ${surfaceClass}`}
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
      style={style}
      className={`form-section @container/form ${surfaceClass} ${padding}`}
    >
      {title && (
        <div className="mb-5 flex items-start justify-between">
          <div>{heading}</div>
          {index && <span className="text-text-3 text-[11px]">{index}</span>}
        </div>
      )}
      {children}
    </section>
  );
}
