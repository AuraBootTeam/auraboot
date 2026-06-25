import { fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { BaseSelect } from '../BaseSelect';
import type { FieldAdapter } from '~/ui/field-adapter';

beforeAll(() => {
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  window.HTMLElement.prototype.hasPointerCapture = vi.fn();
  window.HTMLElement.prototype.releasePointerCapture = vi.fn();
});

function adapter(overrides: Partial<FieldAdapter<string>> = {}): FieldAdapter<string> {
  return {
    value: '',
    setValue: vi.fn(),
    onBlur: vi.fn(),
    onFocus: vi.fn(),
    ...overrides,
  };
}

describe('BaseSelect', () => {
  it('does not render an empty dropdown panel when options are empty', () => {
    render(
      <BaseSelect
        name="project_id"
        label="所属项目"
        placeholder="请选择"
        adapter={adapter({ error: '此字段为必填项', required: true })}
        options={[]}
      />,
    );

    fireEvent.click(screen.getByRole('combobox', { name: /所属项目/ }));

    expect(screen.queryByRole('listbox')).toBeNull();
    expect(screen.getByText('此字段为必填项')).toBeInTheDocument();
  });

  it('renders the dropdown panel when options are present', () => {
    render(
      <BaseSelect
        name="project_id"
        label="所属项目"
        placeholder="请选择"
        adapter={adapter()}
        options={[{ value: 'P001', label: '项目 A' }]}
      />,
    );

    fireEvent.click(screen.getByRole('combobox', { name: /所属项目/ }));

    expect(screen.getByRole('option', { name: '项目 A' })).toBeInTheDocument();
  });
});
