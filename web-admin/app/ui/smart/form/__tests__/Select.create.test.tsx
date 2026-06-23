import { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { beforeAll, describe, it, expect, vi } from 'vitest';
import { Select, CREATE_NEW_VALUE } from '../Select';

// Radix Select renders into a portal; jsdom needs scrollIntoView/pointer shims.
beforeAll(() => {
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  window.HTMLElement.prototype.hasPointerCapture = vi.fn();
  window.HTMLElement.prototype.releasePointerCapture = vi.fn();
});

describe('SmartSelect create affordance', () => {
  it('exposes the create sentinel value', () => {
    expect(CREATE_NEW_VALUE).toBe('__aura_create_new__');
  });

  it('calls onCreateNew (not onChange) when the create item is selected', () => {
    const onCreateNew = vi.fn();
    const onChange = vi.fn();
    render(
      <Select
        name="customer_id"
        options={[{ value: '1', label: 'Acme' }]}
        canCreateNew
        onCreateNew={onCreateNew}
        onChange={onChange}
      />,
    );
    // Open the Radix listbox
    fireEvent.click(screen.getByTestId('select-trigger-customer_id'));
    fireEvent.click(screen.getByTestId('select-create-new-customer_id'));
    expect(onCreateNew).toHaveBeenCalledTimes(1);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('calls onCreateNew from the multi-select create action button', () => {
    const onCreateNew = vi.fn();
    const onChange = vi.fn();

    render(
      <Select
        name="customer_ids"
        multiple
        options={[{ value: '1', label: 'Acme' }]}
        canCreateNew
        onCreateNew={onCreateNew}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByTestId('select-create-new-customer_ids'));

    expect(onCreateNew).toHaveBeenCalledTimes(1);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('keeps a newly-created controlled value selected instead of emitting an empty change', () => {
    const changes: string[] = [];

    function Harness() {
      const [value, setValue] = useState('');
      const [options, setOptions] = useState([{ value: 'OLD', label: 'Old Customer' }]);

      return (
        <Select
          name="customer_id"
          value={value}
          options={options}
          canCreateNew
          onCreateNew={() => {
            setOptions([{ value: 'CUST-1', label: 'Acme' }, ...options]);
            setValue('CUST-1');
          }}
          onChange={(nextValue) => {
            changes.push(String(nextValue));
            setValue(String(nextValue));
          }}
        />
      );
    }

    render(<Harness />);
    fireEvent.click(screen.getByTestId('select-trigger-customer_id'));
    fireEvent.click(screen.getByTestId('select-create-new-customer_id'));

    expect(screen.getByTestId('select-trigger-customer_id')).toHaveTextContent('Acme');
    expect(changes).not.toContain('');
  });
});
