import React from 'react';
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { DraggableWrapper } from '~/studio/workbench/canvas/drag/DraggableWrapper';

describe('DraggableWrapper (studio)', () => {
  it('renders children', () => {
    const { getByText } = render(
      <DraggableWrapper
        component={{
          id: 'c1',
          type: 'input',
          name: 'Input',
          position: { row: 0, column: 0 },
          props: {},
        }}
        data={{
          type: 'existing-component',
          component: {
            id: 'c1',
            type: 'input',
            name: 'Input',
            position: { row: 0, column: 0 },
            props: {},
          },
        }}
      >
        <div>child</div>
      </DraggableWrapper>,
    );

    expect(getByText('child')).toBeInTheDocument();
  });
});
