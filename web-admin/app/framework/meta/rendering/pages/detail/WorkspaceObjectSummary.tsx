import React from 'react';
import { WorkspaceCell, type WorkspaceCellPresentation } from '~/framework/meta/runtime/renderers/WorkspaceCells';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { LocalizedText } from '~/framework/meta/schemas/types';

export interface ObjectSummaryItem { field: string; label: string | LocalizedText; presentation: WorkspaceCellPresentation; }
/** Object summary is field-driven; business pages only declare their information order. */
export function WorkspaceObjectSummary({ items, record, locale, timezone, t }: { items: ObjectSummaryItem[]; record: Record<string, any>; locale: string; timezone: string; t: (key: string) => string }) {
  return <div className="border-border bg-panel mb-5 grid grid-cols-2 rounded-card border px-1 py-3 md:grid-cols-4" data-testid="object-summary">
    {items.map((item, index) => <div key={item.field} className={`min-w-0 px-5 ${index ? 'md:border-border md:border-l' : ''}`}>
      <p className="text-text-3 mb-3 text-[11px]">{getLocalizedText(item.label, locale, t)}</p>
      <div data-testid={`object-summary-${item.field}`}><WorkspaceCell value={record[item.field]} record={record} column={{ field: item.field, presentation: { ...item.presentation, size: 'summary' }, timezone }} locale={locale} /></div>
    </div>)}
  </div>;
}
