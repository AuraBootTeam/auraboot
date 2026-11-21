import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Display } from '../Display';

describe('Display declared typography', () => {
  it('keeps default labels and values at the existing body size', () => {
    render(<Display label="Reference" value="ABC-001" appearance="plain" />);
    expect(screen.getByText('Reference')).toHaveClass('text-sm');
    expect(screen.getByText('ABC-001')).toHaveClass('text-sm');
    expect(screen.getByText('ABC-001')).not.toHaveClass('font-normal');
  });
  it('applies the declared emphasis without modifying the business value', () => {
    const { container } = render(<Display label="Quoted unit price" value="CNY 1.13 / Pcs · Tax included" appearance="plain" size="large" weight="bold" align="right" />);
    expect(screen.getByText('Quoted unit price')).toHaveClass('text-xs');
    expect(screen.getByText('CNY 1.13 / Pcs · Tax included')).toHaveClass('text-xl', 'font-semibold');
    expect(container.firstElementChild).toHaveClass('text-right');
  });
  it('retains empty, mapped and zero values with explicit typography', () => {
    const { rerender } = render(<Display value={null} size="small" />);
    expect(screen.getByText('-')).toHaveClass('text-xs');
    rerender(<Display value={0} size="medium" />);
    expect(screen.getByText('0')).toHaveClass('text-sm');
    rerender(<Display value="quoted" valueMap={{ quoted: 'Formally quoted' }} size="xlarge" weight="light" />);
    expect(screen.getByText('Formally quoted')).toHaveClass('text-2xl', 'font-light');
  });
});
