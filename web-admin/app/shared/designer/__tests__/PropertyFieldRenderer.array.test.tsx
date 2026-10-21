import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { PropertyFieldRenderer } from '../PropertyFieldRenderer';
import type { PropertySchema } from '../types';

function makeAdapter<T>(value: T, setValue: (v: unknown) => void) {
  return { value: value as unknown, setValue, error: undefined, required: false, disabled: false };
}

const arraySchema: PropertySchema<string> = {
  key: 'rules',
  label: 'Rules',
  type: 'array',
  addButtonLabel: '+ Add',
  placeholder: 'No rules',
  itemLabel: (_item: any, idx: number) => `Rule ${idx + 1}`,
  itemSchema: [
    { key: 'name', label: 'Name', type: 'text', defaultValue: '' },
    { key: 'count', label: 'Count', type: 'number', defaultValue: 0 },
  ],
};

describe('PropertyFieldRenderer / array', () => {
  it('reorders complete items without mutating siblings and disables boundary moves', () => {
    const items=[{name:'alpha',count:1},{name:'beta',count:2}];const setValue=vi.fn();
    render(<PropertyFieldRenderer schema={arraySchema} adapter={makeAdapter(items,setValue)} />);
    const first=within(screen.getByRole('group',{name:'Rule 1'}));
    const second=within(screen.getByRole('group',{name:'Rule 2'}));
    expect(first.getByRole('button',{name:/Move up|上移/})).toBeDisabled();
    expect(second.getByRole('button',{name:/Move down|下移/})).toBeDisabled();
    fireEvent.click(second.getByRole('button',{name:/Move up|上移/}));
    expect(setValue).toHaveBeenCalledWith([{name:'beta',count:2},{name:'alpha',count:1}]);
    expect(items[0].name).toBe('alpha');
  });
  it('renders empty placeholder when value is empty', () => {
    render(
      <PropertyFieldRenderer schema={arraySchema} adapter={makeAdapter([], () => {})} />,
    );
    expect(screen.getByText('No rules')).toBeInTheDocument();
  });

  it('renders one ArrayItemEditor header per item', () => {
    const items = [
      { name: 'alpha', count: 1 },
      { name: 'beta', count: 2 },
    ];
    render(
      <PropertyFieldRenderer schema={arraySchema} adapter={makeAdapter(items, () => {})} />,
    );
    expect(screen.getByText('Rule 1')).toBeInTheDocument();
    expect(screen.getByText('Rule 2')).toBeInTheDocument();
  });

  it('calls setValue with one extra default item when Add button is clicked', () => {
    const setValue = vi.fn();
    render(
      <PropertyFieldRenderer schema={arraySchema} adapter={makeAdapter([], setValue)} />,
    );
    fireEvent.click(screen.getByText('+ Add'));
    expect(setValue).toHaveBeenCalledOnce();
    const result = setValue.mock.calls[0][0] as any[];
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ name: '', count: 0 });
  });

  it('calls setValue with second item only when first remove button is clicked', () => {
    const setValue = vi.fn();
    const items = [
      { name: 'alpha', count: 1 },
      { name: 'beta', count: 2 },
    ];
    render(
      <PropertyFieldRenderer schema={arraySchema} adapter={makeAdapter(items, setValue)} />,
    );
    // There are two remove buttons — click the first one
    const removeButtons = screen.getAllByRole('button', { name: /remove|删除/i });
    fireEvent.click(removeButtons[0]);
    expect(setValue).toHaveBeenCalledOnce();
    expect(setValue.mock.calls[0][0]).toEqual([{ name: 'beta', count: 2 }]);
  });

  it('calls setValue with updated item when a child field changes', () => {
    const setValue = vi.fn();
    const items = [{ name: 'alpha', count: 1 }];
    render(
      <PropertyFieldRenderer schema={arraySchema} adapter={makeAdapter(items, setValue)} />,
    );
    const nameInput = screen.getByDisplayValue('alpha');
    fireEvent.change(nameInput, { target: { value: 'gamma' } });
    expect(setValue).toHaveBeenCalledOnce();
    expect(setValue.mock.calls[0][0]).toEqual([{ name: 'gamma', count: 1 }]);
  });
});

it('keeps nested array controls read only, including adding and removing items', () => {
  const setValue=vi.fn();
  render(<PropertyFieldRenderer schema={arraySchema} adapter={{...makeAdapter([{name:'locked',count:0}],setValue),disabled:true}} />);
  expect(screen.getByDisplayValue('locked')).toBeDisabled();
  const add=screen.getByRole('button',{name:'+ Add'});
  const remove=screen.getByRole('button',{name:'remove'});
  expect(add).toBeDisabled(); expect(remove).toBeDisabled();
  fireEvent.click(add); fireEvent.click(remove);
  expect(setValue).not.toHaveBeenCalled();
});

it('resolves localized array prompts and labels instead of showing English defaults', () => {
  render(<PropertyFieldRenderer schema={{...arraySchema, addButtonLabel:{'zh-CN':'+ 添加列',en:'+ Add column'}, placeholder:{'zh-CN':'暂无列',en:'No columns'},itemLabel:undefined}} adapter={makeAdapter([],()=>{})} />);
  expect(screen.getByText('暂无列')).toBeVisible();
  expect(screen.getByRole('button',{name:'+ 添加列'})).toBeVisible();
});

it('leaves optional numeric properties unset instead of inventing an invalid zero', () => {
  const changed = vi.fn();
  render(<PropertyFieldRenderer schema={{...arraySchema,itemSchema:[{key:'width',label:'Width',type:'number',min:1}]}} adapter={makeAdapter([],changed)} />);
  fireEvent.click(screen.getByRole('button',{name:'+ Add'}));
  expect(changed).toHaveBeenCalledOnce();
  expect(changed.mock.calls[0][0][0].width).toBeUndefined();
});
