import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ProgressField } from '../ProgressField';
import { RatingField } from '../RatingField';
describe('Business field labels', () => {
  it('names the editable progress control and clamps edits to its percentage range', () => {
    const onChange = vi.fn();
    render(
      <ProgressField name="progress" label="Delivery progress" value={68} onChange={onChange} />,
    );
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Delivery progress' }), {
      target: { value: '140' },
    });
    expect(onChange).toHaveBeenLastCalledWith(100);
  });
  it('labels the rating group and retains read-only behavior', () => {
    const onChange = vi.fn();
    render(<RatingField label="Solution maturity" value={4} readOnly onChange={onChange} />);
    expect(screen.getByRole('group', { name: 'Solution maturity' })).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '5 stars' }));
    expect(onChange).not.toHaveBeenCalled();
  });
});
