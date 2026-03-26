import React from 'react';
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ComponentPalette } from '~/studio/workbench/palette/ComponentPalette';

describe('ComponentPalette (studio implementation)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders search input and updates query', () => {
    render(<ComponentPalette />);

    const input = screen.getByPlaceholderText('搜索组件...');
    expect(input).toBeInTheDocument();

    fireEvent.change(input, { target: { value: 'form' } });
    expect((input as HTMLInputElement).value).toBe('form');
  });

  it('renders categories toggle buttons', () => {
    render(<ComponentPalette />);
    expect(screen.getByText('全部')).toBeInTheDocument();
  });
});
