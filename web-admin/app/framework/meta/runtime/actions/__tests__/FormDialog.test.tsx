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
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          code: '0',
          data: {
            records: [
              {
                displayName: 'Sales One',
                user: { pid: 'user-sales-1', email: 'sales@example.com' },
              },
            ],
          },
        }),
      }),
    );
    renderDialog({
      title: '分配线索',
      fields: [
        {
          field: 'crm_lpi_claimed_by',
          label: '分配给',
          type: 'reference',
          component: 'MemberPicker',
          required: true,
          props: { multiple: false },
        },
      ],
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
      fields: [
        {
          field: 'quantity',
          label: { 'zh-CN': '本次上架数量', en: 'Putaway Quantity' },
          placeholder: { 'zh-CN': '例如：20', en: 'e.g. 20' },
          helpText: {
            'zh-CN': '不得超过当前剩余量；确认后才移动库存。',
            en: 'Must not exceed the remainder; inventory moves only after confirmation.',
          },
          type: 'number',
        },
      ],
      fieldOptions: {},
      defaults: {},
    });

    expect(screen.getByPlaceholderText('例如：20')).toBeInTheDocument();
    expect(screen.getByTestId('form-dialog-help-quantity')).toHaveTextContent(
      '不得超过当前剩余量；确认后才移动库存。',
    );
  });

  it('renders localized business section headings and widens grouped dialogs', () => {
    renderDialog({
      title: '记录包装箱',
      fields: [
        {
          field: 'packageCode',
          group: { 'zh-CN': '箱号与数量', en: 'Carton and Quantity' },
          label: '包装箱号',
          type: 'text',
        },
        {
          field: 'quantity',
          group: { 'zh-CN': '箱号与数量', en: 'Carton and Quantity' },
          label: '本箱数量',
          type: 'number',
        },
        {
          field: 'grossWeight',
          group: { 'zh-CN': '称重结果', en: 'Weight Results' },
          label: '毛重',
          type: 'number',
        },
      ],
      fieldOptions: {},
      defaults: {},
    });

    expect(screen.getByText('箱号与数量')).toBeInTheDocument();
    expect(screen.getByText('称重结果')).toBeInTheDocument();
    expect(screen.getAllByTestId(/form-dialog-group-/)).toHaveLength(2);
    expect(screen.getByRole('group', { name: '箱号与数量' })).toContainElement(
      screen.getByTestId('form-dialog-field-quantity'),
    );
    expect(screen.getByTestId('form-dialog').firstElementChild?.nextElementSibling).toHaveClass(
      'max-w-2xl',
    );
  });

  it('clears a stale value when conditional variants share one payload field', () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [
        { field: 'step', label: '校验步骤', type: 'segmented', required: true },
        {
          field: 'actualValue',
          label: '清线结果',
          type: 'segmented',
          required: true,
          visibleWhen: { field: 'step', operator: 'equals', value: 'clearance' },
        },
        {
          field: 'actualValue',
          label: '扫码实际值',
          type: 'text',
          required: true,
          visibleWhen: { field: 'step', operator: 'equals', value: 'tooling' },
        },
      ],
      fieldOptions: {
        step: [
          { value: 'clearance', label: '清线' },
          { value: 'tooling', label: '工装' },
        ],
        actualValue: [{ value: 'cleared', label: '清线已完成' }],
      },
      defaults: {},
      onSubmit,
    });

    fireEvent.click(screen.getByRole('radio', { name: '清线' }));
    fireEvent.click(screen.getByRole('radio', { name: '清线已完成' }));
    fireEvent.click(screen.getByRole('radio', { name: '工装' }));
    expect(screen.getByTestId('form-dialog-field-actualValue')).toHaveValue('');
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('keeps a later business section mounted when an earlier section becomes hidden', () => {
    renderDialog({
      fields: [
        { field: 'mode', label: '执行模式', type: 'segmented', required: true },
        {
          field: 'program',
          group: '程序准备',
          label: '程序版本',
          type: 'text',
          visibleWhen: { field: 'mode', operator: 'equals', value: 'advanced' },
        },
        {
          field: 'tooling',
          group: '工装确认',
          label: '工装编号',
          type: 'text',
        },
      ],
      fieldOptions: {
        mode: [
          { value: 'advanced', label: '完整准备' },
          { value: 'basic', label: '基础准备' },
        ],
      },
      defaults: { mode: 'advanced' },
    });

    const toolingSection = screen.getByRole('group', { name: '工装确认' });
    fireEvent.click(screen.getByRole('radio', { name: '基础准备' }));
    expect(screen.queryByRole('group', { name: '程序准备' })).not.toBeInTheDocument();
    expect(screen.getByRole('group', { name: '工装确认' })).toBe(toolingSection);
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
      fields: [
        {
          field: 'csvText',
          label: 'CSV 文件',
          type: 'file',
          required: true,
          accept: '.csv,text/csv',
          maxBytes: 1024,
          fileNameField: 'sourceName',
        },
      ],
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
    const submitButton = screen.getByTestId('form-dialog-submit');
    await waitFor(() => expect(submitButton).toBeEnabled());
    fireEvent.click(submitButton);

    expect(onSubmit).toHaveBeenCalledWith({
      csvText: 'deviceCode,sn\nDPS-001,SN-001',
      sourceName: 'devices.csv',
    });
  });

  it('returns the original File for an inline command upload field', async () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [
        {
          field: 'importFileId',
          label: '客户导入文件',
          type: 'file',
          fileValueMode: 'file',
          fileNameField: 'importFilename',
          required: true,
          accept: '.xlsx',
          maxBytes: 50 * 1024 * 1024,
        },
      ],
      fieldOptions: {},
      defaults: {},
      onSubmit,
    });
    const file = new File(['xlsx-bytes'], 'customer-pool.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });

    fireEvent.change(screen.getByTestId('form-dialog-field-importFileId'), {
      target: { files: [file] },
    });
    await waitFor(() => expect(screen.getByText('customer-pool.xlsx')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('form-dialog-submit'));

    expect(onSubmit).toHaveBeenCalledWith({
      importFileId: file,
      importFilename: 'customer-pool.xlsx',
    });
  });
});
