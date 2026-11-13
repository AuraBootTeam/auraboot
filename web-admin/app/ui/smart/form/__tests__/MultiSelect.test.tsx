import React, { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MultiSelect } from '../MultiSelect';
vi.mock('react-router', async () => ({
  ...(await vi.importActual<typeof import('react-router')>('react-router')),
  useActionData: () => null,
}));
const options = [
  { key: 'model', value: 'model', label: 'Data models' },
  { key: 'rules', value: 'rules', label: 'Business rules' },
];
describe('MultiSelect runtime empty values', () => {
  it('renders null from a blank DSL record and selects the first option', () => {
    const changed = vi.fn();
    render(
      <MultiSelect
        name="modules"
        value={null}
        options={options}
        placeholder="Choose modules"
        onChange={changed}
      />,
    );
    fireEvent.click(screen.getByText('Choose modules'));
    fireEvent.click(screen.getByText('Data models'));
    expect(changed).toHaveBeenLastCalledWith(['model']);
  });
  it('keeps null resets controlled instead of reviving a prior selection', () => {
    const changed = vi.fn();
    const { rerender } = render(
      <MultiSelect
        name="modules"
        value={['model']}
        options={options}
        onChange={changed}
        placeholder="Choose modules"
      />,
    );
    expect(screen.getByText('Data models')).toBeInTheDocument();
    rerender(
      <MultiSelect
        name="modules"
        value={null}
        options={options}
        onChange={changed}
        placeholder="Choose modules"
      />,
    );
    expect(screen.getByText('Choose modules')).toBeInTheDocument();
    expect(screen.queryByText('Data models')).not.toBeInTheDocument();
    expect(changed).not.toHaveBeenCalled();
  });
  it('can add and remove values after starting with null', () => {
    function Harness() {
      const [value, setValue] = useState<string[] | null>(null);
      return (
        <MultiSelect
          name="modules"
          value={value}
          options={options}
          onChange={setValue}
          placeholder="Choose modules"
        />
      );
    }
    render(<Harness />);
    fireEvent.click(screen.getByText('Choose modules'));
    fireEvent.click(screen.getByText('Business rules'));
    expect(screen.getAllByText('Business rules').length).toBeGreaterThan(0);
    fireEvent.click(screen.getAllByText('Business rules').at(-1)!);
    expect(screen.getByText('Choose modules')).toBeInTheDocument();
  });
});
