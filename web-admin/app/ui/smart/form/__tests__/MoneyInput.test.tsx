import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import MoneyInput from '../MoneyInput';

describe('MoneyInput', () => {
  it('preserves typed decimal text while editing and formats the new value on blur', () => {
    function Harness() {
      const [value, setValue] = React.useState<number | undefined>(8000.5);
      return (
        <MoneyInput
          name="sc_budget"
          value={value}
          precision={2}
          currencySymbol="¥"
          onChange={(next) => setValue(next as number | undefined)}
        />
      );
    }

    render(<Harness />);

    const input = screen.getByRole('textbox');
    expect(input).toHaveValue('8,000.50');

    fireEvent.focus(input);
    expect(input).toHaveValue('8000.50');

    fireEvent.change(input, { target: { value: '9001.25' } });
    expect(input).toHaveValue('9001.25');

    fireEvent.blur(input);
    expect(input).toHaveValue('9,001.25');
  });
});
