import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { GridLayoutRenderer, resolveResponsiveGrid } from '../GridLayoutRenderer';

const originalWidth = Object.getOwnPropertyDescriptor(window, 'innerWidth');
afterEach(() => {
  cleanup();
  if (originalWidth) Object.defineProperty(window, 'innerWidth', originalWidth);
});

describe('explicit compact grid layout', () => {
  const layout = { type: 'grid' as const, cols: 4, compactCols: 2, compactBreakpoint: 700 };
  const flowBlocks = [
    { id: 'intro', layout: { colSpan: 4, order: 0 } },
    ...[1, 2, 3, 4].map((order) => ({ id: `metric-${order}`, layout: { colSpan: 1, order } })),
  ];
  const positionedBlocks = flowBlocks.map((block, index) => ({
    ...block,
    layout: { ...block.layout, col: Math.max(0, index - 1), row: index, rowSpan: 2 },
  }));
  it('retains desktop coordinates and a declared desktop template above the compact breakpoint', () => {
    const desktop = resolveResponsiveGrid(
      { ...layout, columnTemplate: 'repeat(4, 1fr)' },
      positionedBlocks,
      1440,
    );
    expect(desktop.compact).toBe(false);
    expect(desktop.cols).toBe(4);
    expect(desktop.blocks).toBe(positionedBlocks);
    expect(desktop.columnTemplate).toBe('repeat(4, 1fr)');
  });
  it('renders a real full-width intro and four unpositioned cards as compact auto-flow tracks', () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 700 });
    render(
      <GridLayoutRenderer
        layout={{ ...layout, columnTemplate: 'repeat(4, 1fr)' }}
        blocks={flowBlocks}
        renderBlock={(block) => <span>{block.id}</span>}
      />,
    );
    expect(screen.getByTestId('grid-layout')).toHaveStyle({
      gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
      gridAutoFlow: 'row',
    });
    expect(screen.getByTestId('grid-item-intro')).toHaveStyle({
      gridColumn: 'span 2',
      gridRow: 'auto',
    });
    for (const order of [1, 2, 3, 4]) {
      const card = screen.getByTestId(`grid-item-metric-${order}`);
      expect(card).toHaveStyle({ gridColumn: 'span 1', gridRow: 'auto' });
      expect(card.style.gridColumn).not.toMatch(/^1\s*\//);
    }
  });
  it('discards explicit desktop coordinates in compact mode and clamps only the spans', () => {
    const result = resolveResponsiveGrid(layout, positionedBlocks, 700);
    expect(result.blocks.map((block) => block.layout?.colSpan)).toEqual([2, 1, 1, 1, 1]);
    expect(result.blocks.map((block) => block.layout?.order)).toEqual([0, 1, 2, 3, 4]);
    for (const block of result.blocks) {
      expect(block.layout).not.toHaveProperty('col');
      expect(block.layout).not.toHaveProperty('row');
      expect(block.layout).not.toHaveProperty('rowSpan');
    }
    expect(positionedBlocks[0].layout).toMatchObject({ col: 0, row: 0, colSpan: 4, rowSpan: 2 });
  });
  it('preserves legacy arrays, objects, coordinates and templates without compact parameters', () => {
    const result = resolveResponsiveGrid(
      { type: 'grid', cols: 12, gridTemplateColumns: 'repeat(4, 1fr)' },
      positionedBlocks,
      390,
    );
    expect(result.compact).toBe(false);
    expect(result.cols).toBe(12);
    expect(result.columnTemplate).toBe('repeat(4, 1fr)');
    expect(result.blocks).toBe(positionedBlocks);
    result.blocks.forEach((block, index) => expect(block).toBe(positionedBlocks[index]));
  });
});
