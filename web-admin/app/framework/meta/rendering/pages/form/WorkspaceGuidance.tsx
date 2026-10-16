import React from 'react';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { LocalizedText } from '~/framework/meta/schemas/types';

type Text = string | LocalizedText;
export interface FormGuidanceConfig {
  title: Text;
  description?: Text;
  steps: { title: Text; description?: Text }[];
  note?: Text;
}
/** Declarative contextual guidance shared by DSL forms. */
export function WorkspaceGuidance({ config, locale, t }: { config: FormGuidanceConfig; locale: string; t: (key: string) => string }) {
  const text = (value?: Text) => value ? getLocalizedText(value, locale, t) : '';
  return <aside className="min-w-0" data-testid="form-guidance">
    <section className="border-border bg-panel rounded-card border p-5">
      <h3 className="text-text text-[13px] font-semibold">{text(config.title)}</h3>
      {config.description && <p className="text-text-2 mt-3 text-xs leading-6">{text(config.description)}</p>}
      <ol className="border-border mt-5 space-y-5 border-t pt-5">
        {config.steps.map((step, index) => <li key={index} className="flex gap-3">
          <span aria-hidden="true" className="border-border bg-subtle text-text-2 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border text-[10px]">{index + 1}</span>
          <div><strong className="text-text text-xs font-medium">{text(step.title)}</strong>{step.description && <p className="text-text-3 mt-1 text-[11px] leading-5">{text(step.description)}</p>}</div>
        </li>)}
      </ol>
    </section>
    {config.note && <p className="text-text-3 mt-4 px-2 text-[11px] leading-5">{text(config.note)}</p>}
  </aside>;
}
