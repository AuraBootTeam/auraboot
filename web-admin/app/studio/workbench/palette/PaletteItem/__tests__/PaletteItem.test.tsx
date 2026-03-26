import React from 'react';
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { PaletteItem } from '~/studio/workbench/palette/PaletteItem/PaletteItem';

describe('PaletteItem (studio)', () => {
  it('renders name and icon', () => {
    const { getByText } = render(<PaletteItem type="input" name="输入框" icon="🧩" />);

    expect(getByText('输入框')).toBeInTheDocument();
  });
});
