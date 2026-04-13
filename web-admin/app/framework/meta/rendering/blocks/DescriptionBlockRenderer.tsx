/**
 * DescriptionBlockRenderer - 描述文本块渲染器
 */

import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';

export interface DescriptionBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const DescriptionBlockRenderer: React.FC<DescriptionBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  const content = block.content ? getLocalizedText(block.content, locale, t) : '';

  return (
    <div className="description-block rounded-md border border-blue-200 bg-blue-50 p-4">
      <div
        className="text-sm text-gray-700"
        dangerouslySetInnerHTML={{ __html: sanitizeHtml(content) }}
      />
    </div>
  );
};

export default DescriptionBlockRenderer;
