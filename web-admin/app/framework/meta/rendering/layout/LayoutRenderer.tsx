import React from 'react';
import type { LayoutConfig } from '~/framework/meta/schemas/types';
import { GridLayoutRenderer } from './GridLayoutRenderer';
import { StackLayoutRenderer } from './StackLayoutRenderer';

interface LayoutRendererProps {
  layout?: LayoutConfig;
  blocks: Array<{ id?: string; layout?: any; [key: string]: any }>;
  renderBlock: (block: any) => React.ReactNode;
}

export function LayoutRenderer({ layout, blocks, renderBlock }: LayoutRendererProps) {
  if (layout?.type === 'grid') {
    return <GridLayoutRenderer layout={layout} blocks={blocks} renderBlock={renderBlock} />;
  }
  return <StackLayoutRenderer gap={layout?.gap} blocks={blocks} renderBlock={renderBlock} />;
}
