/**
 * GridOverlay — 12-column background grid lines for the canvas
 *
 * Renders faint dashed column dividers. During drag/resize,
 * highlights the target column range in blue.
 *
 * Position: absolute overlay behind canvas content, pointer-events: none.
 */

import React from 'react';
import { DEFAULT_GRID_COLS } from '~/plugins/core-designer/components/studio/core/layout/layout-constants';

export interface GridOverlayProps {
  /** Set of column indices (0-11) to highlight during drag/resize */
  highlightCols?: Set<number>;
}

export const GridOverlay: React.FC<GridOverlayProps> = ({ highlightCols }) => {
  const cols = Array.from({ length: DEFAULT_GRID_COLS }, (_, i) => i);

  return (
    <div
      className="pointer-events-none absolute inset-0"
      style={{
        display: 'grid',
        gridTemplateColumns: `repeat(${DEFAULT_GRID_COLS}, 1fr)`,
      }}
      data-testid="canvas-grid-overlay"
    >
      {cols.map((i) => {
        const isHighlighted = highlightCols?.has(i) ?? false;
        const isLast = i === DEFAULT_GRID_COLS - 1;
        return (
          <div
            key={i}
            className="transition-colors duration-150"
            style={{
              borderRight: isLast ? 'none' : '1px dashed rgba(0,0,0,0.08)',
              backgroundColor: isHighlighted ? 'rgba(99,102,241,0.06)' : 'transparent',
            }}
            data-testid={`grid-col-${i}`}
          />
        );
      })}
    </div>
  );
};
