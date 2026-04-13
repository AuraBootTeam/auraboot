import React from 'react';

interface StackLayoutRendererProps {
  gap?: number;
  blocks: Array<{ id?: string; [key: string]: any }>;
  renderBlock: (block: any) => React.ReactNode;
}

export function StackLayoutRenderer({ gap = 16, blocks, renderBlock }: StackLayoutRendererProps) {
  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', gap }}
      data-testid="stack-layout"
    >
      {blocks.map((block, i) => (
        <div key={block.id ?? `block-${i}`}>
          {renderBlock(block)}
        </div>
      ))}
    </div>
  );
}
