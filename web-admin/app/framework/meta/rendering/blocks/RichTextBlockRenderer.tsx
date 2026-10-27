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
      className={[
        'rich-text-block prose prose-sm max-w-none text-sm leading-6 text-text-2',
        '[&_h1]:mb-3 [&_h1]:mt-6 [&_h1]:text-xl [&_h1]:font-semibold [&_h1]:text-text [&_h1:first-child]:mt-0',
        '[&_h2]:mb-2 [&_h2]:mt-5 [&_h2]:text-lg [&_h2]:font-semibold [&_h2]:text-text [&_h2:first-child]:mt-0',
        '[&_h3]:mb-2 [&_h3]:mt-4 [&_h3]:text-base [&_h3]:font-semibold [&_h3]:text-text [&_h3:first-child]:mt-0',
        '[&_p]:my-2 [&_ol]:my-3 [&_ol]:list-decimal [&_ol]:space-y-2 [&_ol]:pl-5',
        '[&_ul]:my-3 [&_ul]:list-disc [&_ul]:space-y-2 [&_ul]:pl-5',
        block.className || '',
      ].join(' ')}
      data-testid="rich-text-block"
      data-block-type="rich-text"
      dangerouslySetInnerHTML={{ __html: sanitizeHtml(content || '') }}
    />
  );
};

export default RichTextBlockRenderer;
