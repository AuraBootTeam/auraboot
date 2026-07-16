import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import FormDialog from '../FormDialog';

function renderDialog(detail: Record<string, any>) {
  render(
    <I18nProvider initialData={{}} initialLocale="zh-CN">
      <FormDialog />
    </I18nProvider>,
  );
  act(() => {
    window.dispatchEvent(new CustomEvent('dialog:form', { detail }));
  });
}

describe('FormDialog choice fields', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('uses localized fallbacks instead of leaking missing i18n keys', () => {
    renderDialog({
      fields: [{ field: 'strategy', label: '处理策略', type: 'select' }],
      fieldOptions: { strategy: [] },
      defaults: {},
    });

    expect(screen.getByRole('option', { name: '请选择...' })).toBeInTheDocument();
    expect(screen.getByTestId('form-dialog-cancel')).toHaveTextContent('取消');
    expect(screen.getByTestId('form-dialog-submit')).toHaveTextContent('确认');
    expect(screen.queryByText(/common\.(?:select|cancel|confirm)/)).not.toBeInTheDocument();
  });

  it('switches mode-specific fields and submits only visible values', () => {
    const onSubmit = vi.fn();
    renderDialog({
      title: '调整字段来源',
      fields: [
        {
          field: 'mode',
          label: '解析方式',
          type: 'segmented',
          required: true,
        },
        {
          field: 'sourceColumns',
          label: '来源列',
          type: 'multiselect',
          required: true,
          searchable: true,
          placeholder: '搜索列',
          visibleWhen: { field: 'mode', operator: 'in', values: ['single', 'merge'] },
        },
        {
          field: 'strategy',
          label: '处理策略',
          type: 'select',
          required: true,
          visibleWhen: { field: 'mode', operator: 'equals', value: 'merge' },
        },
        {
          field: 'constantValue',
          label: '固定值',
          type: 'text',
          defaultValue: 'stale hidden value',
          visibleWhen: { field: 'mode', operator: 'equals', value: 'constant' },
        },
        {
          field: 'confirmedByUser',
          label: '显式确认',
          placeholder: '我确认此映射',
          type: 'checkbox',
          required: true,
          mustBeTrue: true,
        },
      ],
      fieldOptions: {
        mode: [
          { value: 'single', label: '单列读取' },
          { value: 'merge', label: '多列合并' },
          { value: 'constant', label: '固定值' },
        ],
        sourceColumns: [
          { value: 's0-h0-c0', label: 'A · 规格', description: '候选角色=spec' },
          { value: 's0-h0-c1', label: 'B · 型号', description: '候选角色=mpn' },
        ],
        strategy: [
          { value: 'join_non_blank', label: '合并非空值' },
          {
            value: 'mpn_token',
            label: '提取 MPN',
            visibleWhen: { field: 'mode', operator: 'equals', value: 'extract' },
          },
        ],
      },
      defaults: {
        mode: 'single',
        sourceColumns: ['s0-h0-c0'],
        strategy: 'join_non_blank',
        constantValue: 'stale hidden value',
        confirmedByUser: false,
      },
      onSubmit,
    });

    expect(screen.getByTestId('form-dialog-field-sourceColumns')).toBeInTheDocument();
    expect(screen.queryByTestId('form-dialog-field-strategy')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: '多列合并' }));
    expect(screen.getByTestId('form-dialog-field-strategy')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: /B · 型号/ }));
    fireEvent.change(screen.getByTestId('form-dialog-field-strategy'), {
      target: { value: 'join_non_blank' },
    });
    fireEvent.click(screen.getByRole('checkbox', { name: '我确认此映射' }));
    fireEvent.click(screen.getByTestId('form-dialog-submit'));

    expect(onSubmit).toHaveBeenCalledWith({
      mode: 'merge',
      sourceColumns: ['s0-h0-c0', 's0-h0-c1'],
      strategy: 'join_non_blank',
      confirmedByUser: true,
    });
  });

  it('does not submit an empty required multiselect or unchecked confirmation', () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [
        { field: 'sourceColumns', label: '来源列', type: 'multiselect', required: true },
        {
          field: 'confirmedByUser',
          label: '显式确认',
          placeholder: '我确认此映射',
          type: 'checkbox',
          required: true,
          mustBeTrue: true,
        },
      ],
      fieldOptions: { sourceColumns: [{ value: 'c0', label: 'A · 规格' }] },
      defaults: { sourceColumns: [], confirmedByUser: false },
      onSubmit,
    });

    fireEvent.click(screen.getByTestId('form-dialog-submit'));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getAllByText(/来源列|显式确认/).length).toBeGreaterThan(1);
  });
});
