import React, { useState } from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ModelFieldMapEditor } from '../ModelFieldMapEditor';
import { fetchFieldOptions } from '~/shared/services/resourceSelectService';

vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));
vi.mock('~/shared/services/resourceSelectService', () => ({ fetchFieldOptions: vi.fn() }));
const change = vi.fn();
function Harness({ initial = {} as unknown, readOnly = false }) {
  const [value, setValue] = useState(initial);
  return (
    <ModelFieldMapEditor
      adapter={{
        value,
        context: { modelCode: 'tpm_task' },
        readOnly,
        setValue: (next) => {
          change(next);
          setValue(next);
        },
      }}
    />
  );
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchFieldOptions).mockResolvedValue([
    { value: 'description', label: '交付说明', description: 'text' },
    { value: 'amount', label: '交付金额', description: 'decimal' },
    { value: 'internal', label: '内部字段', readOnly: true },
  ]);
});
describe('metadata-backed model field map', () => {
  it('selects a real field, preserves numeric values and does not offer read-only fields', async () => {
    render(<Harness />);
    await vi.waitFor(() => expect(screen.getByTestId('model-map-add')).toBeEnabled());
    fireEvent.click(screen.getByTestId('model-map-add'));
    expect(screen.queryByRole('option', { name: '内部字段' })).toBeNull();
    fireEvent.change(screen.getByTestId('model-map-new-field'), { target: { value: 'amount' } });
    fireEvent.change(screen.getByTestId('model-map-value-0'), { target: { value: '68000.01' } });
    expect(change).toHaveBeenLastCalledWith({ amount: 68000.01 });
    fireEvent.change(screen.getByTestId('model-map-value-0'), { target: { value: '' } });
    expect(change).toHaveBeenLastCalledWith({ amount: '' });
  });
  it('round-trips an existing JSON object string without exposing a raw JSON input', async () => {
    render(<Harness initial='{"description":"交付材料已确认"}' />);
    const input = await screen.findByTestId('model-map-value-0');
    await vi.waitFor(() => expect(input).toBeEnabled());
    expect(screen.getByRole('option', { name: '交付说明' })).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '材料归档完成' } });
    expect(change).toHaveBeenLastCalledWith({ description: '材料归档完成' });
    expect(document.querySelector('textarea')).toBeNull();
  });
  it('preserves malformed persisted input and prevents editing instead of silently replacing it', async () => {
    render(<Harness initial="{invalid" />);
    expect(screen.getByRole('alert')).toHaveTextContent('不是有效对象');
    expect(screen.getByTestId('model-map-add')).toBeDisabled();
    expect(change).not.toHaveBeenCalled();
  });
  it('keeps read-only mappings readable while denying mutation', async () => {
    render(<Harness readOnly initial={{ description: '交付材料已确认' }} />);
    expect(await screen.findByTestId('model-map-value-0')).toBeDisabled();
    expect(screen.queryByTestId('model-map-add')).toBeNull();
    expect(screen.getByTestId('model-map-remove-0')).toBeDisabled();
  });
  it('shows metadata failure and explicitly reloads without erasing the current mapping', async () => {
    vi.mocked(fetchFieldOptions).mockRejectedValueOnce(new Error('Unavailable'));
    render(<Harness initial={{ description: '现有说明' }} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('读取失败');
    expect(change).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '刷新属性' }));
    await vi.waitFor(() => expect(screen.getByTestId('model-map-value-0')).toBeEnabled());
    expect(screen.getByTestId('model-map-value-0')).toHaveValue('现有说明');
    expect(fetchFieldOptions).toHaveBeenCalledTimes(2);
  });
});
