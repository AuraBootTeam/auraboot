/**
 * BlockRenderer — registry-driven dispatcher.
 *
 * Looks up `block.blockType` in BlockRegistry and renders the registered
 * component. Unknown types render a visible placeholder (not silent null) so
 * missing registrations surface immediately in dev.
 */

import React, { Suspense } from 'react';
import { BlockRegistry } from './BlockRegistry';

export interface BlockRendererProps {
  block: { blockType: string; [k: string]: unknown };
  [k: string]: unknown;
}

const UnknownBlockFallback: React.FC<{ type: string }> = ({ type }) => (
  <div className="rounded border border-yellow-300 bg-yellow-50 p-4 text-sm text-yellow-800">
    Unknown blockType: <code>{type}</code>. Register it via <code>initBlockRegistry()</code>.
  </div>
);

export const BlockRenderer: React.FC<BlockRendererProps> = ({ block, ...rest }) => {
  const spec = BlockRegistry.get(block.blockType);

  if (!spec) {
    console.warn(`[BlockRenderer] unknown blockType: ${block.blockType}`);
    return <UnknownBlockFallback type={block.blockType} />;
  }

  const Component = spec.component;
  return (
    <Suspense fallback={<div className="bg-muted h-24 animate-pulse rounded" />}>
      <Component block={block} {...rest} />
    </Suspense>
  );
};

export default BlockRenderer;
