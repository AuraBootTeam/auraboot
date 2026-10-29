import type { TenantMemberSearchRecord } from '~/shared/services/tenantMemberSearchService';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import FormDialog from '../FormDialog';
import { promptInputForm } from '../ActionRegistry';
import { validRecordList, type RecordListConfig } from '../RecordListField';

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

  it('keeps focus and numeric drafts in the field the user chooses immediately after opening', () => {
    vi.useFakeTimers();
    try {
      const onSubmit = vi.fn();
      renderDialog({
        fields: [
          { field: 'quantity', label: 'Quantity', type: 'number' },
          { field: 'multiplier', label: 'Multiplier', type: 'number' },
        ],
        defaults: { quantity: 2 },
        onSubmit,
      });
      const quantity = screen.getByTestId('form-dialog-field-quantity');
      const multiplier = screen.getByTestId('form-dialog-field-multiplier');
      multiplier.focus();
      act(() => vi.advanceTimersByTime(50));

      expect(multiplier).toHaveFocus();
      fireEvent.change(multiplier, { target: { value: '105' } });
      expect(quantity).toHaveValue(2);
      expect(multiplier).toHaveValue(105);
      fireEvent.click(screen.getByTestId('form-dialog-submit'));
      expect(onSubmit).toHaveBeenCalledWith({ quantity: 2, multiplier: 105 });
    } finally {
      vi.useRealTimers();
    }
  });

  it('names a required command textarea by its business label and submits its value', () => {
    const onSubmit = vi.fn();
    renderDialog({
      title: '登记交付跟进',
      submitLabel: '保存跟进',
      fields: [
        { field: 'tpm_pj_next_action', label: '下一步行动', type: 'textarea', required: true },
      ],
      defaults: {},
      onSubmit,
    });
    const input = screen.getByRole('textbox', { name: '下一步行动', exact: true });
    fireEvent.change(input, { target: { value: '林晓于11月8日前完成范围确认。' } });
    fireEvent.click(screen.getByRole('button', { name: '保存跟进', exact: true }));
    expect(onSubmit).toHaveBeenCalledWith({ tpm_pj_next_action: '林晓于11月8日前完成范围确认。' });
  });

  it('renders a MemberPicker command input and submits the selected user pid', async () => {
    const onSubmit = vi.fn();
    const member = {
      pid: 'member-sales-1',
      status: 'active',
      user: {
        pid: 'user-sales-1',
        username: null,
        realName: 'Sales One',
        email: 'sales@example.com',
        avatar: null,
      },
    } satisfies TenantMemberSearchRecord;
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async (input: string | URL) => {
        const path = new URL(String(input), window.location.origin).pathname;
        if (!path.startsWith('/api/i18n/') && path !== '/api/tenant/members/search') {
          throw new Error(`Unexpected command picker request: ${path}`);
        }
        const data = path.startsWith('/api/i18n/') ? { common: {} } : { records: [member] };
        return new Response(JSON.stringify({ code: '0', data }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
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
    expect(globalThis.fetch).toHaveBeenCalledWith(
      `${window.location.origin}/api/tenant/members/search`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ pageNum: 1, pageSize: 20, status: 'active' }),
      }),
    );
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

  it('previews the first BOM rows and submits the selected provider projection', async () => {
    const onSubmit = vi.fn();
    renderDialog({
      title: '上传并确认 BOM 识别列',
      submitLabel: '按所选列上传并查价',
      fields: [
        {
          field: 'corrected_bom_file_id',
          label: 'BOM 文件与渠道识别列',
          type: 'bom-upload-review',
          required: true,
          accept: '.xlsx,.xls,.csv',
          props: { previewRowCount: 1 },
        },
      ],
      fieldOptions: {},
      defaults: {},
      onSubmit,
    });

    const csv = 'Customer Note,Specification,Quantity,Brand\nDo not upload,100nF 50V 0603,4,Murata';
    const bytes = new TextEncoder().encode(csv);
    const file = new File([bytes], 'customer-bom.csv', { type: 'text/csv' });
    Object.defineProperty(file, 'arrayBuffer', {
      value: vi.fn().mockResolvedValue(bytes.buffer),
    });

    fireEvent.change(screen.getByTestId('bom-upload-review-file-corrected_bom_file_id'), {
      target: { files: [file] },
    });

    await waitFor(() => expect(screen.getByText('100nF 50V 0603')).toBeInTheDocument());
    expect(screen.getByText('原始 BOM 前 1 行')).toBeInTheDocument();
    expect(screen.getByText('将发送给云汉的列')).toBeInTheDocument();
    expect(screen.getByText('已选 3 列')).toBeInTheDocument();
    expect(screen.getByTestId('form-dialog').firstElementChild?.nextElementSibling).toHaveClass(
      'max-w-6xl',
    );

    fireEvent.click(screen.getByTestId('form-dialog-submit'));

    expect(onSubmit).toHaveBeenCalledWith({
      corrected_bom_file_id: expect.objectContaining({
        file,
        valid: true,
        payload: {
          bom_sheet_name: 'Sheet1',
          bom_header_row_index: 0,
          bom_selected_columns: [
            { index: 1, header: 'Specification', role: 'spec' },
            { index: 2, header: 'Quantity', role: 'quantity' },
            { index: 3, header: 'Brand', role: 'manufacturer' },
          ],
        },
      }),
    });
  });

  it('blocks a reviewed BOM until exactly one quantity column is selected', async () => {
    const onSubmit = vi.fn();
    renderDialog({
      title: '上传并确认 BOM 识别列',
      fields: [
        {
          field: 'corrected_bom_file_id',
          label: 'BOM 文件与渠道识别列',
          type: 'bom-upload-review',
          required: true,
          accept: '.csv',
        },
      ],
      fieldOptions: {},
      defaults: {},
      onSubmit,
    });

    const bytes = new TextEncoder().encode('MPN,Brand\nRC0603FR-0710KL,Yageo');
    const file = new File([bytes], 'bom-without-quantity.csv', { type: 'text/csv' });
    Object.defineProperty(file, 'arrayBuffer', {
      value: vi.fn().mockResolvedValue(bytes.buffer),
    });

    fireEvent.change(screen.getByTestId('bom-upload-review-file-corrected_bom_file_id'), {
      target: { files: [file] },
    });

    await waitFor(() =>
      expect(screen.getByTestId('bom-upload-review-validation')).toHaveTextContent(
        '必须且只能选择一个“单套用量”列。',
      ),
    );
    fireEvent.click(screen.getByTestId('form-dialog-submit'));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getAllByText('必须且只能选择一个“单套用量”列。')).toHaveLength(2);
  });
});

describe('FormDialog calendar and instant fields', () => {
  it('submits a calendar day unchanged and converts a selected local time into an instant', () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [
        { field: 'deliveryDate', label: '交付日期', type: 'date', required: true },
        { field: 'cutoffAt', label: '报价截止时间', type: 'datetime', required: true },
      ],
      defaults: {},
      onSubmit,
    });
    const delivery = screen.getByLabelText(/交付日期/);
    const cutoff = screen.getByLabelText(/报价截止时间/);
    expect(delivery).toHaveAttribute('type', 'date');
    expect(cutoff).toHaveAttribute('type', 'datetime-local');
    fireEvent.change(delivery, { target: { value: '2026-11-01' } });
    fireEvent.change(cutoff, { target: { value: '2026-10-30T15:45' } });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).toHaveBeenCalledWith({
      deliveryDate: '2026-11-01',
      cutoffAt: new Date('2026-10-30T15:45').toISOString(),
    });
  });

  it('preserves the original instant when the user leaves its default unchanged', () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [{ field: 'cutoffAt', label: '截止时间', type: 'datetime' }],
      defaults: { cutoffAt: '2026-10-30T07:45:32.123Z' },
      onSubmit,
    });
    const value = (screen.getByLabelText('截止时间') as HTMLInputElement).value;
    const local = new Date('2026-10-30T07:45:32.123Z');
    const pad = (v: number) => String(v).padStart(2, '0');
    expect(value).toBe(
      `${local.getFullYear()}-${pad(local.getMonth() + 1)}-${pad(local.getDate())}T${pad(local.getHours())}:${pad(local.getMinutes())}`,
    );
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).toHaveBeenCalledWith({ cutoffAt: '2026-10-30T07:45:32.123Z' });
  });

  it('blocks submission when a required calendar field is cleared', () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [{ field: 'deliveryDate', label: '交付日期', type: 'date', required: true }],
      defaults: { deliveryDate: '2026-11-01' },
      onSubmit,
    });
    fireEvent.change(screen.getByLabelText(/交付日期/), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByLabelText(/交付日期/)).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('FormDialog grouped choices', () => {
  const groups = [
    {
      value: 'resistors',
      label: '电阻',
      options: [
        { value: 'supplier-a', label: '供应商甲' },
        { value: 'supplier-b', label: '供应商乙' },
      ],
    },
    {
      value: 'capacitors',
      label: '电容',
      options: [
        { value: 'supplier-a', label: '供应商甲' },
        { value: 'supplier-c', label: '停用供应商', disabled: true },
      ],
    },
  ];
  const field = {
    field: 'categorySuppliers',
    type: 'grouped-multiselect',
    label: '各品类供应商',
    required: true,
    props: { groupField: 'category', valuesField: 'supplierPids' },
  };
  it('requires explicit selection in every group and submits independent multi selections', () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [field],
      fieldOptions: { categorySuppliers: groups },
      defaults: {},
      onSubmit,
    });
    const resistors = screen.getByRole('group', { name: '电阻' });
    const capacitors = screen.getByRole('group', { name: '电容' });
    expect(
      screen
        .getAllByRole('checkbox')
        .every((input: HTMLElement) => !(input as HTMLInputElement).checked),
    ).toBe(true);
    fireEvent.click(screen.getAllByRole('checkbox', { name: '供应商甲' })[0]);
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('checkbox', { name: '供应商乙' }));
    fireEvent.click(screen.getAllByRole('checkbox', { name: '供应商甲' })[1]);
    expect(resistors).toBeInTheDocument();
    expect(capacitors).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '停用供应商' })).toBeDisabled();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).toHaveBeenCalledWith({
      categorySuppliers: [
        { category: 'resistors', supplierPids: ['supplier-a', 'supplier-b'] },
        { category: 'capacitors', supplierPids: ['supplier-a'] },
      ],
    });
  });
  it.each(
    [
      [],
      [{ category: 'resistors', supplierPids: ['supplier-a'] }],
      [
        { category: 'resistors', supplierPids: ['supplier-a', 'supplier-a'] },
        { category: 'capacitors', supplierPids: ['supplier-a'] },
      ],
      [
        { category: 'resistors', supplierPids: ['supplier-a'] },
        { category: 'capacitors', supplierPids: ['supplier-c'] },
      ],
      [
        { category: 'resistors', supplierPids: ['supplier-a'] },
        { category: 'capacitors', supplierPids: ['forged'] },
      ],
    ].map((value) => ({ value })),
  )('rejects incomplete or invalid submitted defaults', ({ value }) => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [field],
      fieldOptions: { categorySuppliers: groups },
      defaults: { categorySuppliers: value },
      onSubmit,
    });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
  });
});

describe('FormDialog grouped default selections', () => {
  it('submits manual changes to a suggested selection without restoring the original default', () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [
        { field: 'selection', type: 'grouped-multiselect', label: 'Selection', required: true },
      ],
      fieldOptions: {
        selection: [
          {
            value: 'materials',
            label: 'Materials',
            options: [
              { value: 'a', label: 'A' },
              { value: 'b', label: 'B' },
            ],
          },
        ],
      },
      defaults: { selection: [{ group: 'materials', values: ['a'] }] },
      onSubmit,
    });
    fireEvent.click(screen.getByRole('checkbox', { name: 'A' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'B' }));
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).toHaveBeenCalledWith({ selection: [{ group: 'materials', values: ['b'] }] });
  });
  it('preserves an explicitly supplied valid selection without adding suppliers', () => {
    const onSubmit = vi.fn();
    const value = [{ group: 'materials', values: ['a'] }];
    renderDialog({
      fields: [{ field: 'selection', type: 'grouped-multiselect', label: 'Selection' }],
      fieldOptions: {
        selection: [
          {
            value: 'materials',
            label: 'Materials',
            options: [
              { value: 'a', label: 'A' },
              { value: 'b', label: 'B' },
            ],
          },
        ],
      },
      defaults: { selection: value },
      onSubmit,
    });
    expect(screen.getByRole('checkbox', { name: 'A' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'B' })).not.toBeChecked();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).toHaveBeenCalledWith({ selection: value });
  });
});

describe('FormDialog named native inputs', () => {
  it('associates business labels with native command input controls', () => {
    renderDialog({
      fields: [
        { field: 'title', type: 'text', label: '询价标题' },
        { field: 'notes', type: 'textarea', label: '说明' },
        { field: 'quantity', type: 'number', label: '数量' },
        { field: 'category', type: 'select', label: '采购品类' },
      ],
      defaults: {},
      fieldOptions: { category: [{ value: 'a', label: '电阻' }] },
    });
    expect(screen.getByRole('textbox', { name: '询价标题' })).toHaveAttribute('type', 'text');
    expect(screen.getByRole('textbox', { name: '说明' })).toBeInTheDocument();
    expect(screen.getByRole('spinbutton', { name: '数量' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: '采购品类' })).toBeInTheDocument();
  });
});

describe('FormDialog unavailable grouped options', () => {
  it('explains an empty group and prevents its submission', () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [
        { field: 'selection', type: 'grouped-multiselect', label: '各品类供应商', required: true },
      ],
      fieldOptions: { selection: [{ value: 'empty', label: '电阻', options: [] }] },
      defaults: {},
      onSubmit,
    });
    expect(screen.getByRole('group', { name: '电阻' })).toHaveTextContent('暂无可选项');
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
  });
});

describe('FormDialog fixed-key string maps', () => {
  it('shows read-only business labels while submitting the exact original values', () => {
    const axes = { color: 'blue_code', capacity: '1234567890123456.78' };
    const onSubmit = vi.fn();
    renderDialog({
      submitLabel: 'Save information',
      fields: [
        {
          field: 'axes',
          label: 'Options',
          type: 'string-map',
          required: true,
          props: {
            readOnly: true,
            keyLabels: { color: 'Colour', capacity: 'Capacity' },
            displayValues: { color: 'Blue', capacity: '1234567890123456.78 GB' },
          },
        },
      ],
      defaults: { axes },
      onSubmit,
    });
    const group = screen.getByRole('group', { name: 'Options' });
    expect(group).toHaveTextContent('Colour');
    expect(group).toHaveTextContent('Blue');
    expect(group).toHaveTextContent('1234567890123456.78 GB');
    expect(group).not.toHaveTextContent('blue_code');
    expect(group.querySelector('input,select,textarea')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Save information', exact: true }));
    expect(onSubmit).toHaveBeenCalledWith({ axes });
    expect(axes).toEqual({ color: 'blue_code', capacity: '1234567890123456.78' });
  });
  it('rejects malformed read-only values instead of trusting display labels', () => {
    const onSubmit = vi.fn();
    renderDialog({
      fields: [
        {
          field: 'axes',
          label: 'Options',
          type: 'string-map',
          required: true,
          props: {
            readOnly: true,
            displayValues: { color: 'Blue' },
            invalidMessage: 'Reload options',
          },
        },
      ],
      defaults: { axes: { color: 7 } },
      onSubmit,
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Reload options');
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
  });
  it('edits each labeled value without changing keys, coercing numbers or mutating the source', () => {
    const axes = { color: 'Red', size: 'M' },
      onSubmit = vi.fn();
    renderDialog({
      submitLabel: 'Save options',
      fields: [
        {
          field: 'axes',
          label: 'Options',
          type: 'string-map',
          required: true,
          props: { keyLabels: { color: 'Colour', size: 'Size' }, maxValueLength: 128 },
        },
      ],
      defaults: { axes },
      onSubmit,
    });
    expect(screen.getByRole('group', { name: 'Options' })).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Colour'), { target: { value: 'Wine red' } });
    fireEvent.change(screen.getByLabelText('Size'), { target: { value: '007' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save options', exact: true }));
    expect(onSubmit).toHaveBeenCalledWith({ axes: { color: 'Wine red', size: '007' } });
    expect(axes).toEqual({ color: 'Red', size: 'M' });
  });
  it('keeps empty values in the dialog and does not submit malformed maps as valid empty options', () => {
    const onSubmit = vi.fn();
    renderDialog({
      submitLabel: 'Save options',
      fields: [
        {
          field: 'axes',
          label: 'Options',
          type: 'string-map',
          props: { invalidMessage: 'Review options' },
        },
      ],
      defaults: { axes: { Colour: 'Red' } },
      onSubmit,
    });
    fireEvent.change(screen.getByLabelText('Colour'), { target: { value: ' ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save options', exact: true }));
    expect(screen.getByText('Review options')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });
  it.each([null, ['Red'], { colour: 7 }])('rejects a non-string-map source: %j', (axes) => {
    const onSubmit = vi.fn();
    renderDialog({
      submitLabel: 'Save options',
      fields: [
        {
          field: 'axes',
          label: 'Options',
          type: 'string-map',
          props: { invalidMessage: 'Reload options' },
        },
      ],
      defaults: { axes },
      onSubmit,
    });
    expect(screen.getByRole('alert').textContent).toBe('Reload options');
    fireEvent.click(screen.getByRole('button', { name: 'Save options', exact: true }));
    expect(onSubmit).not.toHaveBeenCalled();
  });
  it('accepts the explicit empty map of a single variant without inventing options', () => {
    const onSubmit = vi.fn();
    renderDialog({
      submitLabel: 'Save options',
      fields: [
        {
          field: 'axes',
          label: 'Options',
          type: 'string-map',
          required: true,
          props: { emptyMessage: 'No options needed' },
        },
      ],
      defaults: { axes: {} },
      onSubmit,
    });
    expect(screen.getByText('No options needed')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save options', exact: true }));
    expect(onSubmit).toHaveBeenCalledWith({ axes: {} });
  });
});

describe('FormDialog structured record lists', () => {
  const fields = [
    {
      field: 'bindings',
      label: '属性声明',
      type: 'record-list',
      props: {
        maxItems: 2,
        addLabel: '添加属性',
        removeLabel: '移除属性',
        itemLabel: '属性',
        fields: [
          { field: 'label', label: '名称', type: 'text', required: true, group: '属性定义' },
          {
            field: 'dictionary.version',
            label: '字典版本',
            type: 'integer',
            min: 1,
            max: 10000,
            required: true,
            group: '属性定义',
          },
          { field: 'publicReadable', label: '买家可见', type: 'checkbox', group: '用途' },
          {
            field: 'roles',
            label: '可写角色',
            type: 'multiselect',
            group: '用途',
            options: [{ value: 'editor', label: '目录编辑' }],
          },
        ],
        defaults: { label: '', dictionary: { version: 1 }, publicReadable: false, roles: [] },
      },
    },
  ];
  it('edits typed nested values without changing hidden metadata or the original defaults', () => {
    const onSubmit = vi.fn();
    const original = {
      bindings: [
        {
          key: 'capacity',
          label: '容量',
          dictionary: { code: 'capacity', version: 3 },
          publicReadable: true,
          roles: ['editor'],
          frozen: { tenant: '9007199254740993' },
        },
      ],
    };
    renderDialog({ fields, defaults: original, onSubmit });
    expect(screen.getByLabelText(/^名称/)).toHaveFocus();
    fireEvent.change(screen.getByLabelText(/^名称/), { target: { value: '存储容量' } });
    fireEvent.change(screen.getByLabelText(/^字典版本/), { target: { value: '4' } });
    fireEvent.click(screen.getByLabelText('买家可见'));
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).toHaveBeenCalledWith({
      bindings: [
        {
          ...original.bindings[0],
          label: '存储容量',
          dictionary: { code: 'capacity', version: 4 },
          publicReadable: false,
        },
      ],
    });
    expect(original.bindings[0].label).toBe('容量');
    expect(original.bindings[0].dictionary.version).toBe(3);
  });
  it('adds and removes declarations and cancels without mutating the input array', () => {
    const onSubmit = vi.fn(),
      onCancel = vi.fn();
    const original = { bindings: [] };
    renderDialog({ fields, defaults: original, onSubmit, onCancel });
    fireEvent.click(screen.getByRole('button', { name: '添加属性' }));
    fireEvent.change(screen.getByLabelText(/^名称/), { target: { value: '保修' } });
    fireEvent.click(screen.getByRole('button', { name: '移除属性 1' }));
    fireEvent.click(screen.getByRole('button', { name: '添加属性' }));
    expect(screen.getByLabelText(/^名称/)).toHaveValue('');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onSubmit).not.toHaveBeenCalled();
    expect(onCancel).toHaveBeenCalledOnce();
    expect(original.bindings).toEqual([]);
  });
  it('rejects malformed data instead of silently replacing it with an empty list', () => {
    const onSubmit = vi.fn();
    renderDialog({ fields, defaults: { bindings: { key: 'invalid' } }, onSubmit });
    expect(screen.getByRole('alert')).toBeVisible();
    expect(screen.queryByRole('button', { name: '添加属性' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
  });
  it('rejects incomplete records and out-of-range or noninteger version drafts', () => {
    const onSubmit = vi.fn();
    renderDialog({ fields, defaults: { bindings: [] }, onSubmit });
    fireEvent.click(screen.getByRole('button', { name: '添加属性' }));
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText(/^名称/), { target: { value: '颜色' } });
    fireEvent.change(screen.getByLabelText(/^字典版本/), { target: { value: '3.5' } });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText(/^字典版本/), { target: { value: '3' } });
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).toHaveBeenCalledWith({
      bindings: [{ label: '颜色', dictionary: { version: 3 }, publicReadable: false, roles: [] }],
    });
  });
});

describe('record-list trust boundary', () => {
  const config: RecordListConfig = {
    fields: [
      { field: 'version', label: '版本', type: 'integer', required: true, min: 1, max: 100 },
      { field: 'enabled', label: '启用', type: 'checkbox', required: true },
      {
        field: 'roles',
        label: '角色',
        type: 'multiselect',
        options: [
          { value: 'editor', label: '目录编辑' },
          { value: 'retired', label: '历史角色', disabled: true },
        ],
      },
    ],
    maxItems: 1,
  };
  it('accepts false and an empty optional role set without coercing business values', () => {
    expect(validRecordList([{ version: 1, enabled: false, roles: [] }], config)).toBe(true);
  });
  it.each([0, 101, 1.5, '2', '9007199254740993', 9007199254740992])(
    'rejects invalid exact version %s',
    (version) => {
      expect(validRecordList([{ version, enabled: false, roles: [] }], config)).toBe(false);
    },
  );
  it.each([['unknown'], ['retired'], ['editor', 'editor']])(
    'rejects unavailable or duplicate role choices %j',
    (...roles) => {
      expect(validRecordList([{ version: 1, enabled: true, roles }], config)).toBe(false);
    },
  );
  it('keeps the item quota and malformed row boundary in validation', () => {
    expect(
      validRecordList(
        [
          { version: 1, enabled: true, roles: [] },
          { version: 2, enabled: true, roles: [] },
        ],
        config,
      ),
    ).toBe(false);
    expect(validRecordList([null], config)).toBe(false);
    expect(validRecordList([['version', 1]], config)).toBe(false);
  });
  it('rejects unsafe field paths before editing or submitting', () => {
    expect(() =>
      validRecordList([], {
        fields: [{ field: '__proto__.polluted', label: '属性', type: 'text' }],
      }),
    ).toThrow('Invalid record-list field path');
    expect(({} as Record<string, unknown>).polluted).toBeUndefined();
  });
});

it('rejects corrupt intermediate objects instead of erasing metadata during nested editing', () => {
  const config: RecordListConfig = {
    fields: [{ field: 'dictionary.version', label: '版本', type: 'integer' }],
  };
  expect(validRecordList([{ dictionary: 'broken' }], config)).toBe(false);
  expect(() =>
    validRecordList([], {
      fields: [{ field: 'dictionary', label: '字典', type: 'text' }, ...config.fields],
    }),
  ).toThrow('Overlapping record-list field paths');
});

it('carries structured defaults through the original prompt input pipeline and returns typed declarations', async () => {
  render(
    <I18nProvider initialData={{}} initialLocale="zh-CN">
      <FormDialog />
    </I18nProvider>,
  );
  const original = [{ key: 'capacity', fieldVersion: 3, publicReadable: false }];
  let pending: Promise<Record<string, unknown>>;
  await act(async () => {
    pending = promptInputForm(
      [
        {
          field: 'bindings',
          label: '属性声明',
          type: 'record-list',
          defaultValue: '${state.type.bindings}',
          props: {
            fields: [
              { field: 'fieldVersion', label: '字段版本', type: 'integer', required: true, min: 1 },
              { field: 'publicReadable', label: '买家可见', type: 'checkbox' },
            ],
          },
        },
      ],
      '编辑属性',
      undefined,
      '保存声明',
      { state: { type: { bindings: original } } },
    );
  });
  fireEvent.change(screen.getByLabelText(/^字段版本/), { target: { value: '4' } });
  fireEvent.click(screen.getByRole('button', { name: '保存声明' }));
  await expect(pending!).resolves.toEqual({
    bindings: [{ key: 'capacity', fieldVersion: 4, publicReadable: false }],
  });
  expect(original).toEqual([{ key: 'capacity', fieldVersion: 3, publicReadable: false }]);
});

describe('FormDialog paged native references', () => {
  const scope = { storePid: 'store-a', typePid: 'type-a', revision: '7' };
  const field = (reference: Record<string, unknown>, name = 'fieldCode') => ({
    field: 'bindings',
    label: '属性',
    type: 'record-list',
    props: {
      identityField: 'key',
      defaults: { fieldCode: '', roles: [] },
      fields: [
        {
          field: name,
          label: '原生引用',
          type: 'reference',
          required: name === 'fieldCode',
          reference: {
            source: '/api/choices?expectedRevision=7',
            selectedSource: '/api/selected?expectedRevision=7',
            scope,
            ...reference,
          },
        },
      ],
    },
  });
  const response = (records: unknown[], total = records.length, current = 1) => ({
    ...scope,
    records,
    total,
    current,
    pageSize: 20,
  });
  const choice = (value: string, label: string, selection: Record<string, unknown> = {}) => ({
    value,
    label,
    selection,
  });
  afterEach(() => vi.restoreAllMocks());

  it('uses the original prompt transport, pages real totals and assigns an exact composite reference without leaking candidate metadata', async () => {
    const onSubmit = vi.fn();
    const fetchResult = vi.fn(async (url: string) =>
      url.includes('/selected')
        ? response([])
        : url.includes('page=2')
          ? response(
              [
                choice('field-v2', '容量 · v2', {
                  fieldCode: 'capacity',
                  fieldVersion: 2,
                  secret: 'discard',
                }),
              ],
              21,
              2,
            )
          : response(
              [choice('field-v1', '容量 · v1', { fieldCode: 'capacity', fieldVersion: 1 })],
              21,
            ),
    );
    render(
      <I18nProvider initialData={{}} initialLocale="zh-CN">
        <FormDialog />
      </I18nProvider>,
    );
    let result!: Promise<Record<string, any>>;
    act(() => {
      result = promptInputForm(
        [
          {
            ...field({ selectionFields: ['fieldCode', 'fieldVersion'] }),
            defaultValue: [
              {
                key: 'capacity',
                fieldCode: 'capacity',
                fieldVersion: 1,
                untouched: { tenant: '9007199254740993' },
              },
            ],
          },
        ],
        '编辑属性',
        fetchResult,
      );
    });
    fireEvent.click(screen.getByLabelText(/^原生引用/));
    await screen.findByRole('button', { name: '容量 · v1 ✓' });
    fireEvent.click(screen.getByRole('button', { name: '下一页' }));
    fireEvent.click(await screen.findByRole('button', { name: '容量 · v2' }));
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    await expect(result).resolves.toEqual({
      bindings: [
        {
          key: 'capacity',
          fieldCode: 'capacity',
          fieldVersion: 2,
          untouched: { tenant: '9007199254740993' },
        },
      ],
    });
    expect(
      fetchResult.mock.calls.some(
        ([url]) => url.includes('page=2') && url.includes('expectedRevision=7'),
      ),
    ).toBe(true);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('selects a family then an explicit dictionary version and clears the optional reference as null', async () => {
    const onSubmit = vi.fn();
    const fetchResult = vi.fn(async (url: string) =>
      url.includes('/selected')
        ? response([])
        : url.includes('/families/family-a/versions')
          ? response([
              choice('dict-v3', '颜色 · v3', { dictionary: { code: 'colours', version: 3 } }),
            ])
          : response([choice('family-a', '颜色')]),
    );
    renderDialog({
      fields: [
        field(
          {
            nextSource: '/api/families/{value}/versions?expectedRevision=7',
            selectionFields: ['dictionary'],
          },
          'dictionary',
        ),
      ],
      defaults: { bindings: [{ key: 'colour', dictionary: null }] },
      fetchResult,
      onSubmit,
    });
    fireEvent.click(screen.getByLabelText('原生引用'));
    fireEvent.click(await screen.findByRole('button', { name: '颜色' }));
    fireEvent.click(await screen.findByRole('button', { name: '颜色 · v3' }));
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).toHaveBeenCalledWith({
      bindings: [{ key: 'colour', dictionary: { code: 'colours', version: 3 } }],
    });
  });

  it('preserves unavailable selections on permission failure and retries without erasing the record', async () => {
    const onSubmit = vi.fn();
    const fetchResult = vi.fn().mockRejectedValue(new Error('forbidden'));
    const defaults = { bindings: [{ key: 'capacity', fieldCode: 'capacity', fieldVersion: 1 }] };
    renderDialog({
      fields: [field({ selectionFields: ['fieldCode', 'fieldVersion'] })],
      defaults,
      fetchResult,
      onSubmit,
    });
    await screen.findByText('无法读取已选名称，请检查权限或重试。');
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    expect(onSubmit).toHaveBeenCalledWith(defaults);
    expect(defaults.bindings[0].fieldVersion).toBe(1);
  });

  it('rejects a stale candidate scope and never exposes its rows', async () => {
    const fetchResult = vi.fn(async (url: string) =>
      url.includes('/selected')
        ? response([])
        : {
            ...response([choice('foreign', '别店字段', { fieldCode: 'foreign', fieldVersion: 1 })]),
            storePid: 'store-b',
          },
    );
    renderDialog({
      fields: [field({ selectionFields: ['fieldCode', 'fieldVersion'] })],
      defaults: { bindings: [{ key: 'a', fieldCode: 'a', fieldVersion: 1 }] },
      fetchResult,
    });
    fireEvent.click(screen.getByLabelText(/^原生引用/));
    await screen.findByText('候选读取失败，请检查权限、草稿版本或连接。');
    expect(screen.queryByRole('button', { name: '别店字段' })).not.toBeInTheDocument();
  });

  it('keeps role selections across pages, excludes disabled choices and preserves cancellation', async () => {
    const onSubmit = vi.fn(),
      onCancel = vi.fn();
    const defaults = { bindings: [{ key: 'a', roles: ['role-one'] }] };
    const fetchResult = vi.fn(async (url: string) =>
      url.includes('/selected')
        ? response([choice('role-one', '目录编辑')])
        : url.includes('page=2')
          ? response(
              [
                choice('role-two', '商品审核'),
                { ...choice('disabled', '停用角色'), disabled: true },
              ],
              22,
              2,
            )
          : response([choice('role-one', '目录编辑')], 22),
    );
    renderDialog({
      fields: [field({ multiple: true }, 'roles')],
      defaults,
      fetchResult,
      onSubmit,
      onCancel,
    });
    fireEvent.click(screen.getByLabelText('原生引用'));
    await screen.findByRole('button', { name: '目录编辑 ✓' });
    fireEvent.click(screen.getByRole('button', { name: '下一页' }));
    fireEvent.click(await screen.findByRole('button', { name: '商品审核' }));
    expect(screen.getByRole('button', { name: '停用角色' })).toBeDisabled();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledOnce();
    expect(onSubmit).not.toHaveBeenCalled();
    expect(defaults.bindings[0].roles).toEqual(['role-one']);
  });

  it('generates distinct hidden identities on add and never mutates defaults', () => {
    const onSubmit = vi.fn();
    const f = field({ multiple: true }, 'roles');
    renderDialog({
      fields: [f],
      defaults: { bindings: [] },
      onSubmit,
      fetchResult: vi.fn(async () => response([])),
    });
    fireEvent.click(screen.getByRole('button', { name: '添加记录' }));
    fireEvent.click(screen.getByRole('button', { name: '添加记录' }));
    fireEvent.click(screen.getByTestId('form-dialog-submit'));
    const saved = onSubmit.mock.calls[0][0].bindings;
    expect(saved[0].key).toMatch(/^r_[a-f0-9]{32}$/);
    expect(saved[0].key).not.toBe(saved[1].key);
    expect(f.props.defaults).toEqual({ fieldCode: '', roles: [] });
  });
});
