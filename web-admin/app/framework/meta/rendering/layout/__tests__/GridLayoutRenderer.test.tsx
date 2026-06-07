import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { GridLayoutRenderer } from '../GridLayoutRenderer';

describe('GridLayoutRenderer', () => {
  it('uses shrinkable grid tracks and grid items by default', () => {
    render(
      <GridLayoutRenderer
        layout={{ type: 'grid', cols: 12, colGap: 8, rowGap: 12 }}
        blocks={[{ id: 'left', layout: { colSpan: 8 } }]}
        renderBlock={(block) => <div>{block.id}</div>}
      />,
    );

    expect(screen.getByTestId('grid-layout')).toHaveStyle({
      gridTemplateColumns: 'repeat(12, minmax(0, 1fr))',
      gap: '12px 8px',
      width: '100%',
    });
    expect(screen.getByTestId('grid-item-left')).toHaveStyle({
      gridColumn: '1 / span 8',
      minWidth: '0',
      maxWidth: '100%',
    });
  });

  it('accepts an explicit CSS column template for advanced workbench layouts', () => {
    render(
      <GridLayoutRenderer
        layout={{
          type: 'grid',
          cols: 2,
          columnTemplate: 'minmax(0, 2fr) minmax(320px, 1fr)',
        }}
        blocks={[
          { id: 'main', layout: { col: 0, colSpan: 1 } },
          { id: 'side', layout: { col: 1, colSpan: 1 } },
        ]}
        renderBlock={(block) => <div>{block.id}</div>}
      />,
    );

    expect(screen.getByTestId('grid-layout')).toHaveStyle({
      gridTemplateColumns: 'minmax(0, 2fr) minmax(320px, 1fr)',
    });
  });
});
