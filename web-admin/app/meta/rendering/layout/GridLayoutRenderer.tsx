import React from 'react';
import type { LayoutConfig } from '~/meta/schemas/types';

interface GridLayoutRendererProps {
  layout: LayoutConfig;
  blocks: Array<{
    id?: string;
    layout?: { col?: number; colSpan?: number; rowSpan?: number; order?: number };
    [key: string]: any;
  }>;
  renderBlock: (block: any) => React.ReactNode;
}

const DEFAULT_GRID_COLS = 12;
const DEFAULT_COL_GAP = 16;
const DEFAULT_ROW_GAP = 16;

export function GridLayoutRenderer({ layout, blocks, renderBlock }: GridLayoutRendererProps) {
  const cols = layout.cols ?? DEFAULT_GRID_COLS;
  const colGap = layout.colGap ?? DEFAULT_COL_GAP;
  const rowGap = layout.rowGap ?? DEFAULT_ROW_GAP;

  const sorted = [...blocks].sort(
    (a, b) => (a.layout?.order ?? 0) - (b.layout?.order ?? 0),
  );

  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: `repeat(${cols}, 1fr)`,
        gap: `${rowGap}px ${colGap}px`,
      }}
      data-testid="grid-layout"
    >
      {sorted.map((block) => {
        const blockLayout = block.layout ?? {};
        const col = blockLayout.col ?? 0;
        const colSpan = blockLayout.colSpan ?? cols;
        const rowSpan = blockLayout.rowSpan ?? 1;

        return (
          <div
            key={block.id ?? `block-${col}-${colSpan}`}
            style={{
              gridColumn: `${col + 1} / span ${colSpan}`,
              gridRow: `span ${rowSpan}`,
            }}
            data-testid={`grid-item-${block.id ?? ''}`}
          >
            {renderBlock(block)}
          </div>
        );
      })}
    </div>
  );
}
