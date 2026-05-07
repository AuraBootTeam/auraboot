import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { PropertyFieldRenderer } from '../PropertyFieldRenderer';
import type { PropertySchema } from '../types';
import { dictService } from '~/shared/services/dictService';

vi.mock('~/shared/services/dictService', () => ({
  dictService: { findAll: vi.fn() },
}));

function makeAdapter(value: unknown, setValue: (v: unknown) => void) {
  return { value, setValue, error: undefined, required: false, disabled: false };
}

const dictSchema: PropertySchema<string> = {
  key: 'dictCode',
  label: 'Dict',
  type: 'dict-select',
  placeholder: 'No dict',
};

describe('PropertyFieldRenderer / dict-select', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows fetched options after async load', async () => {
    (dictService.findAll as any).mockResolvedValue([
      { code: 'gender', name: '性别' },
      { code: 'role',   name: '角色' },
    ]);
    render(<PropertyFieldRenderer schema={dictSchema} adapter={makeAdapter(undefined, () => {})} />);
    await waitFor(() => expect(screen.getByText(/性别/)).toBeInTheDocument());
    expect(screen.getByText(/角色/)).toBeInTheDocument();
  });

  it('propagates selected dict code via adapter.setValue', async () => {
    (dictService.findAll as any).mockResolvedValue([{ code: 'gender', name: '性别' }]);
    const setValue = vi.fn();
    render(<PropertyFieldRenderer schema={dictSchema} adapter={makeAdapter(undefined, setValue)} />);
    await waitFor(() => screen.getByText(/性别/));
    const select = screen.getByRole('combobox');
    fireEvent.change(select, { target: { value: 'gender' } });
    expect(setValue).toHaveBeenCalledWith('gender');
  });

  it('respects dictCodeFilter whitelist', async () => {
    (dictService.findAll as any).mockResolvedValue([
      { code: 'a', name: 'A' }, { code: 'b', name: 'B' }, { code: 'c', name: 'C' },
    ]);
    render(
      <PropertyFieldRenderer
        schema={{ ...dictSchema, dictCodeFilter: ['a', 'c'] }}
        adapter={makeAdapter(undefined, () => {})}
      />,
    );
    await waitFor(() => screen.getByText(/^A$/));
    expect(screen.queryByText(/^B$/)).not.toBeInTheDocument();
    expect(screen.getByText(/^C$/)).toBeInTheDocument();
  });
});
