import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import NumberInput from '../NumberInput';

describe('NumberInput default control contract', () => {
  it('uses shared input chrome and preserves a partial decimal until blur', () => {
    function Harness() {
      const [value, setValue] = React.useState<number | undefined>(12);
      return (
        <NumberInput
          name="quantity"
          label="数量"
          value={value}
          precision={2}
          onChange={(next) => setValue(next as number | undefined)}
        />
      );
    }
    render(<Harness />);
    const input = screen.getByRole('textbox', { name: '数量' });
    expect(input).toHaveClass('border', 'rounded-control');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: '12.' } });
    expect(input).toHaveValue('12.');
    fireEvent.change(input, { target: { value: '12.50' } });
    expect(input).toHaveValue('12.50');
    fireEvent.blur(input);
    expect(input).toHaveValue('12.50');
  });
  it('does not mutate read-only numbers through increment controls', () => {
    const changes: unknown[] = [];
    render(
      <NumberInput name="quantity" value={5} readOnly onChange={(value) => changes.push(value)} />,
    );
    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled();
      fireEvent.click(button);
    }
    expect(changes).toEqual([]);
  });
});
