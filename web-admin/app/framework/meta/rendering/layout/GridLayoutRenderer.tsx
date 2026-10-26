import React from 'react';
import type { LayoutConfig } from '~/framework/meta/schemas/types';
import { useViewportWidth } from '~/shared/layout/workspaceShell';

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

export function resolveResponsiveGrid(
  layout: LayoutConfig,
  blocks: GridLayoutRendererProps['blocks'],
  width: number,
) {
  const baseCols = layout.cols ?? DEFAULT_GRID_COLS;
  const compact =
    typeof layout.compactCols === 'number' &&
    Number.isInteger(layout.compactCols) &&
    layout.compactCols > 0 &&
    width <= (layout.compactBreakpoint ?? 700);
  const cols = compact ? Math.min(baseCols, layout.compactCols!) : baseCols;
  // Compact layout is row-major flow in declared order. Desktop coordinates
  // must not pin each unpositioned card to column one or preserve stale rows.
  const resolvedBlocks: GridLayoutRendererProps['blocks'] = compact
    ? blocks.map((block) => {
        const flowLayout: Record<string, any> = { ...block.layout };
        delete flowLayout.col;
        delete flowLayout.row;
        delete flowLayout.rowSpan;
        const span =
          typeof flowLayout.colSpan === 'number' &&
          Number.isInteger(flowLayout.colSpan) &&
          flowLayout.colSpan > 0
            ? flowLayout.colSpan
            : cols;
        flowLayout.colSpan = Math.min(span, cols);
        return { ...block, layout: flowLayout };
      })
    : blocks;
  const declaredTemplate = layout.gridTemplateColumns || layout.columnTemplate;
  const columnTemplate = compact
    ? `repeat(${cols}, minmax(0, 1fr))`
    : typeof declaredTemplate === 'string' && declaredTemplate.trim().length > 0
      ? declaredTemplate
      : `repeat(${cols}, minmax(0, 1fr))`;
  return { cols, compact, blocks: resolvedBlocks, columnTemplate };
}

export function GridLayoutRenderer({ layout, blocks, renderBlock }: GridLayoutRendererProps) {
  const width = useViewportWidth();
  const resolved = resolveResponsiveGrid(layout, blocks, width);
  const cols = resolved.cols;
  const colGap = layout.colGap ?? DEFAULT_COL_GAP;
  const rowGap = layout.rowGap ?? DEFAULT_ROW_GAP;

  const sorted = [...resolved.blocks].sort(
    (a, b) => (a.layout?.order ?? 0) - (b.layout?.order ?? 0),
  );

  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: resolved.columnTemplate,
        gridAutoFlow: resolved.compact ? 'row' : undefined,
        gap: `${rowGap}px ${colGap}px`,
        width: '100%',
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
              gridColumn: resolved.compact ? `span ${colSpan}` : `${col + 1} / span ${colSpan}`,
              gridRow: resolved.compact ? 'auto' : `span ${rowSpan}`,
              minWidth: 0,
              maxWidth: '100%',
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
