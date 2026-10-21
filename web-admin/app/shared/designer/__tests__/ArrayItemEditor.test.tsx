import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
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

it('labels repeated column fields once and edits the intended item without duplicate IDs', () => {
  const changed = vi.fn();
  const { container } = render(<>
    <ArrayItemEditor itemSchema={itemSchema} value={{name:'budget',count:1}} onChange={() => {}} onRemove={() => {}} itemLabel="Budget column" />
    <ArrayItemEditor itemSchema={itemSchema} value={{name:'amount',count:2}} onChange={changed} onRemove={() => {}} itemLabel="Amount column" />
  </>);
  expect(screen.getAllByText('Name')).toHaveLength(2);
  const inputs = [...container.querySelectorAll('input')];
  expect(new Set(inputs.map(input => input.id)).size).toBe(inputs.length);
  const second = within(screen.getByRole('group',{name:'Amount column'})).getByRole('textbox',{name:'Name',exact:true});
  fireEvent.change(second,{target:{value:'paid_amount'}});
  expect(changed).toHaveBeenCalledWith({name:'paid_amount',count:2});
});
