/**
 * DividerBlockRenderer - Renders a horizontal divider.
 *
 * DSL config:
 *   { "blockType": "divider" }
 *   { "blockType": "divider", "title": "Section name" }  // optional label divider
 */

import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface DividerBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const DividerBlockRenderer: React.FC<DividerBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  const label = block.title ? getLocalizedText(block.title, locale, t) : null;

  if (label) {
    return (
      <div
        className={`divider-block my-4 flex items-center gap-3 ${block.className || ''}`}
        data-testid="divider-block"
        data-block-type="divider"
        role="separator"
      >
        <span className="bg-border h-px flex-1" />
        <span className="text-text-2 text-xs font-medium tracking-wider uppercase">{label}</span>
        <span className="bg-border h-px flex-1" />
      </div>
    );
  }

  return (
    <hr
      className={`divider-block border-border my-4 border-t ${block.className || ''}`}
      data-testid="divider-block"
      data-block-type="divider"
      role="separator"
    />
  );
};

export default DividerBlockRenderer;
