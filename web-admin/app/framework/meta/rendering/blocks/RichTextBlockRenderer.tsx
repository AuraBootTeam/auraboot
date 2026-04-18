/**
 * RichTextBlockRenderer - Renders a rich-text / markdown (HTML) content block.
 *
 * DSL config:
 *   { "blockType": "rich-text", "content": "<p>Hello</p>" }
 *
 * Content is sanitized via sanitizeHtml before injection.
 */

import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';

export interface RichTextBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const RichTextBlockRenderer: React.FC<RichTextBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  const raw = (block as any).content ?? '';
  const content =
    typeof raw === 'string' ? raw : getLocalizedText(raw, locale, t);

  return (
    <div
      className={`rich-text-block prose prose-sm max-w-none ${block.className || ''}`}
      data-testid="rich-text-block"
      data-block-type="rich-text"
      dangerouslySetInnerHTML={{ __html: sanitizeHtml(content || '') }}
    />
  );
};

export default RichTextBlockRenderer;
