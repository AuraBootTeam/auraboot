import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
    vi.unstubAllGlobals();
  });

  it('renders a MemberPicker command input and submits the selected user pid', async () => {
    const onSubmit = vi.fn();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0',
        data: {
          records: [{
            displayName: 'Sales One',
            user: { pid: 'user-sales-1', email: 'sales@example.com' },
          }],
        },
      }),
    }));
    renderDialog({
      title: '分配线索',
      fields: [{
        field: 'crm_lpi_claimed_by',
        label: '分配给',
        type: 'reference',
        component: 'MemberPicker',
        required: true,
        props: { multiple: false },
      }],
      fieldOptions: {},
      defaults: {},
      onSubmit,
    });

    expect(screen.getByRole('dialog', { name: '分配线索' })).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('member-picker-add'));
    fireEvent.click(await screen.findByTestId('member-picker-option-user-sales-1'));
    fireEvent.click(screen.getByTestId('form-dialog-submit'));

    expect(onSubmit).toHaveBeenCalledWith({ crm_lpi_claimed_by: 'user-sales-1' });
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

  it('renders localized business help text below an action input', () => {
    renderDialog({
      fields: [{
        field: 'quantity',
        label: { 'zh-CN': '本次上架数量', en: 'Putaway Quantity' },
        placeholder: { 'zh-CN': '例如：20', en: 'e.g. 20' },
        helpText: {
          'zh-CN': '不得超过当前剩余量；确认后才移动库存。',
          en: 'Must not exceed the remainder; inventory moves only after confirmation.',
        },
        type: 'number',
      }],
      fieldOptions: {},
      defaults: {},
    });

    expect(screen.getByPlaceholderText('例如：20')).toBeInTheDocument();
    expect(screen.getByTestId('form-dialog-help-quantity')).toHaveTextContent(
      '不得超过当前剩余量；确认后才移动库存。',
    );
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

  it('reads a selected CSV file into the command payload and carries its filename', async () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [{
        field: 'csvText',
        label: 'CSV 文件',
        type: 'file',
        required: true,
        accept: '.csv,text/csv',
        maxBytes: 1024,
        fileNameField: 'sourceName',
      }],
      fieldOptions: {},
      defaults: {},
      onSubmit,
    });
    const file = new File(['deviceCode,sn\nDPS-001,SN-001'], 'devices.csv', { type: 'text/csv' });
    Object.defineProperty(file, 'text', {
      value: vi.fn().mockResolvedValue('deviceCode,sn\nDPS-001,SN-001'),
    });

    fireEvent.change(screen.getByTestId('form-dialog-field-csvText'), {
      target: { files: [file] },
    });
    await waitFor(() => expect(file.text).toHaveBeenCalled());
    fireEvent.click(screen.getByTestId('form-dialog-submit'));

    expect(onSubmit).toHaveBeenCalledWith({
      csvText: 'deviceCode,sn\nDPS-001,SN-001',
      sourceName: 'devices.csv',
    });
  });
});
