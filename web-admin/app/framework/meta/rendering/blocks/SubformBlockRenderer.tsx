/**
 * SubformBlockRenderer — renders a nested form (subform) block.
 *
 * A subform is a self-contained form section embedded within a parent form.
 * Child fields render via the shared FieldRenderer.
 */

import React, { useMemo } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { FieldRenderer } from '~/framework/meta/rendering/FieldRenderer';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface SubformBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const SubformBlockRenderer: React.FC<SubformBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const childFields = (block as any).fields as BlockConfig[] | undefined;
  const fieldDefs = Array.isArray(childFields) ? childFields : [];

  const gridStyle = useMemo(() => {
    const columns = typeof (block as any).columns === 'number' ? (block as any).columns : 1;
    return {
      display: 'grid',
      gridTemplateColumns: `repeat(${columns}, 1fr)`,
      gap: '0.75rem',
    };
  }, [block]);

  const title = block.title ? getLocalizedText(block.title, locale, t) : null;
  const description = (block as any).description
    ? getLocalizedText((block as any).description, locale, t)
    : null;

  return (
    <section
      className="rounded-lg border border-slate-100 bg-slate-50 p-4"
      data-testid={`runtime-subform-${block.id}`}
    >
      {title ? <h3 className="mb-1 text-sm font-medium text-slate-800">{title}</h3> : null}
      {description ? <p className="mb-3 text-xs text-slate-500">{description}</p> : null}
      <div style={gridStyle}>
        {fieldDefs.map((fieldDef, index) => {
          const fieldKey =
            (fieldDef as any).field || (fieldDef as any).id || `subfield_${index}`;
          return (
            <div key={fieldKey} data-testid={`subform-field-${fieldKey}`}>
              <FieldRenderer field={fieldDef as any} runtime={runtime} />
            </div>
          );
        })}
      </div>
      {fieldDefs.length === 0 ? (
        <p className="text-center text-xs text-slate-400" data-testid={`subform-empty-${block.id}`}>
          No fields configured
        </p>
      ) : null}
    </section>
  );
};

export default SubformBlockRenderer;
