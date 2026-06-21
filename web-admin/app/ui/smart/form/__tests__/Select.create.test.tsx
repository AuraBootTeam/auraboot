import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { Select, CREATE_NEW_VALUE } from '../Select';

// Radix Select renders into a portal; jsdom needs scrollIntoView/pointer shims.
beforeAll(() => {
  // @ts-expect-error jsdom shim
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  // @ts-expect-error jsdom shim
  window.HTMLElement.prototype.hasPointerCapture = vi.fn();
  // @ts-expect-error jsdom shim
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
});
