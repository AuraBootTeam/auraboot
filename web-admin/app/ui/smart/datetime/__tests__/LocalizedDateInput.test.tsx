import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { LocalizedDateInput } from '../LocalizedDateInput';

describe('LocalizedDateInput', () => {
  it('shows year first and keeps the last valid value while a malformed draft is present', () => {
    const submitValue = vi.fn();
    function Fixture() {
      const [value, setValue] = useState('2025-12-20T09:00');
      return <LocalizedDateInput
        inputRef={null}
        pickerType="datetime-local"
        value={value}
        aria-label="预计成交日期"
        onValueChange={next => { setValue(next); submitValue(next); }}
      />;
    }
    render(<Fixture />);
    const input = screen.getByRole('textbox', { name: '预计成交日期' });
    expect(input).toHaveValue('2025-12-20 09:00');

    fireEvent.change(input, { target: { value: '2027-02-29 09:00' } });
    fireEvent.blur(input);
    expect(input).toHaveValue('2027-02-29 09:00');
    expect(input).toHaveAttribute('data-temporal-error');
    expect(input).toHaveAccessibleDescription('请输入有效的日期和时间（YYYY-MM-DD HH:mm）。');
    expect(submitValue).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: '2028-02-29 09:30' } });
    expect(input).not.toHaveAttribute('data-temporal-error');
    expect(input).toHaveValue('2028-02-29 09:30');
    expect(submitValue).toHaveBeenLastCalledWith('2028-02-29T09:30');
  });

  it('honors the native calendar range and keeps its picker keyboard-accessible', () => {
    const onValueChange = vi.fn();
    const { container } = render(<LocalizedDateInput
      inputRef={null}
      pickerType="date"
      value=""
      min="2026-01-01"
      onValueChange={onValueChange}
    />);
    const input = screen.getByRole('textbox');
    expect(input).toHaveAttribute('placeholder', 'YYYY-MM-DD');
    fireEvent.change(input, { target: { value: '2025-12-01' } });
    expect(input).toHaveAttribute('data-temporal-error');
    expect(onValueChange).not.toHaveBeenCalled();

    const calendar = container.querySelector('input[type="date"]') as HTMLInputElement;
    calendar.showPicker = vi.fn();
    fireEvent.click(screen.getByRole('button', { name: '打开日历' }));
    expect(calendar.showPicker).toHaveBeenCalledOnce();
    fireEvent.change(calendar, { target: { value: '2026-01-02' } });
    expect(onValueChange).toHaveBeenLastCalledWith('2026-01-02');
  });
});
