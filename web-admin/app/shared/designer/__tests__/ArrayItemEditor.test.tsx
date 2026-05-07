import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ArrayItemEditor } from '../ArrayItemEditor';
import type { PropertySchema } from '../types';

const itemSchema: PropertySchema<string>[] = [
  { key: 'name', label: 'Name', type: 'text' },
  { key: 'count', label: 'Count', type: 'number' },
];

describe('ArrayItemEditor', () => {
  it('renders the itemLabel in the header', () => {
    render(
      <ArrayItemEditor
        itemSchema={itemSchema}
        value={{ name: 'foo', count: 1 }}
        onChange={() => {}}
        onRemove={() => {}}
        itemLabel="Item alpha"
      />,
    );
    expect(screen.getByText('Item alpha')).toBeInTheDocument();
  });

  it('calls onRemove when the remove button is clicked', () => {
    const onRemove = vi.fn();
    render(
      <ArrayItemEditor
        itemSchema={itemSchema}
        value={{ name: 'foo', count: 1 }}
        onChange={() => {}}
        onRemove={onRemove}
        itemLabel="x"
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /remove|删除/i }));
    expect(onRemove).toHaveBeenCalledOnce();
  });

  it('propagates child field changes to onChange with updated item', () => {
    const onChange = vi.fn();
    render(
      <ArrayItemEditor
        itemSchema={itemSchema}
        value={{ name: 'foo', count: 1 }}
        onChange={onChange}
        onRemove={() => {}}
        itemLabel="x"
      />,
    );
    const nameInput = screen.getByDisplayValue('foo');
    fireEvent.change(nameInput, { target: { value: 'bar' } });
    expect(onChange).toHaveBeenCalledWith({ name: 'bar', count: 1 });
  });
});
