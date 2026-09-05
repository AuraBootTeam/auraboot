import { describe, expect, it, vi } from 'vitest';

import { actionRegistry, promptInputForm } from '~/framework/meta/runtime/actions/ActionRegistry';

describe('ActionRegistry delete translations', () => {
  it('does not leak unresolved i18n keys into confirmation and success toasts', async () => {
    const confirm = vi.fn().mockResolvedValue(true);
    const showToast = vi.fn();
    const loadData = vi.fn().mockResolvedValue(undefined);

    await actionRegistry.execute('delete', {
      tableName: 'supplier_management_supplier',
      record: { pid: 'SUP-1' },
      button: { commandCode: 'supplier_management:delete_supplier_management_supplier' },
      t: (key) => key,
      locale: 'zh-CN',
      confirm,
      showToast,
      loadData,
      filters: {},
      buildApiEndpoint: (tableName) => `/api/dynamic/${tableName}`,
      fetchResult: vi.fn().mockResolvedValue({ code: '0', data: {} }),
    });

    expect(confirm).toHaveBeenCalledWith({
      content: '确定要删除这条记录吗？',
      variant: 'danger',
    });
    expect(showToast).toHaveBeenCalledWith('删除成功', 'success');
  });
});

describe('ActionRegistry record navigation', () => {
  it('prefers pid over id for edit routes', async () => {
    const navigate = vi.fn();

    await actionRegistry.execute('edit', {
      navigate,
      tableName: 'thr_leave_request',
      record: { id: 5, pid: '01HPID123' },
    });

    expect(navigate).toHaveBeenCalledWith('/p/thr_leave_request/edit/01HPID123');
  });

  it('prefers pid over id for detail routes', async () => {
    const navigate = vi.fn();

    await actionRegistry.execute('view', {
      navigate,
      tableName: 'thr_leave_request',
      record: { id: 5, pid: '01HPID123' },
    });

    expect(navigate).toHaveBeenCalledWith('/p/thr_leave_request/view/01HPID123');
  });

  it('executes command actions with evaluated pid target and payload', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      code: '0',
      data: { status: 'approved' },
    });
    const expressionContext = {
      record: { pid: 'PUB-APP-001' },
      form: { reviewNotes: 'Looks ready' },
    };
    const expressionEvaluator = {
      evaluateTemplate: vi.fn((template: string) =>
        template
          .replace('${record.pid}', expressionContext.record.pid)
          .replace('${form.reviewNotes}', expressionContext.form.reviewNotes),
      ),
      evaluateObject: vi.fn((value: Record<string, any>) =>
        Object.fromEntries(
          Object.entries(value).map(([key, item]) => [
            key,
            typeof item === 'string'
              ? item
                  .replace('${record.pid}', expressionContext.record.pid)
                  .replace('${form.reviewNotes}', expressionContext.form.reviewNotes)
              : item,
          ]),
        ),
      ),
    };

    await actionRegistry.execute('command.execute', {
      fetchResult,
      expressionContext,
      expressionEvaluator,
      args: {
        command: 'mkt:approve_publisher_application',
        targetRecordPid: '${record.pid}',
        operationType: 'approve',
        payload: {
          mkt_pa_review_notes: '${form.reviewNotes}',
        },
      },
    });

    expect(fetchResult).toHaveBeenCalledWith(
      '/api/meta/commands/execute/mkt:approve_publisher_application',
      {
        method: 'post',
        params: {
          targetRecordPid: 'PUB-APP-001',
          operationType: 'APPROVE',
          payload: {
            mkt_pa_review_notes: 'Looks ready',
          },
        },
        token: undefined,
      },
    );
  });
});

describe('ActionRegistry command.execute inputFields (command-form sugar)', () => {
  it('loads dictCode choices before opening a command form', async () => {
    const fetchResult = vi.fn().mockImplementation(async (endpoint: string) => {
      if (endpoint === '/api/meta/dict/by-code/inv_shipment_manual_tracking_milestone/data') {
        return {
          code: '0',
          data: {
            items: [
              { value: 'departed', label: '已发车' },
              { value: 'in_transit', label: '运输中' },
            ],
          },
        };
      }
      return { code: '0', data: { ok: true } };
    });
    window.addEventListener(
      'dialog:form',
      (event) => {
        const detail = (event as CustomEvent).detail;
        expect(detail.fieldOptions.inv_sht_milestone).toEqual([
          { value: 'departed', label: '已发车' },
          { value: 'in_transit', label: '运输中' },
        ]);
        detail.onSubmit({ inv_sht_milestone: 'in_transit' });
      },
      { once: true },
    );

    await actionRegistry.execute('command.execute', {
      fetchResult,
      args: {
        command: 'inv:record_shipment_tracking',
        targetRecordPid: 'SHIP-1',
        inputFields: [
          {
            field: 'inv_sht_milestone',
            type: 'select',
            dictCode: 'inv_shipment_manual_tracking_milestone',
            required: true,
          },
        ],
      },
    });

    expect(fetchResult).toHaveBeenNthCalledWith(
      1,
      '/api/meta/dict/by-code/inv_shipment_manual_tracking_milestone/data',
      { method: 'get' },
    );
    expect(fetchResult).toHaveBeenNthCalledWith(
      2,
      '/api/meta/commands/execute/inv:record_shipment_tracking',
      expect.objectContaining({
        method: 'post',
        params: expect.objectContaining({
          payload: { inv_sht_milestone: 'in_transit' },
        }),
      }),
    );
  });

  it('pops a form (FormDialog) and merges collected values into the command payload', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: { ok: true } });
    // Simulate the user filling + submitting the FormDialog the action pops.
    window.addEventListener(
      'dialog:form',
      (e) => {
        const detail = (e as CustomEvent).detail;
        expect(detail.fields[0].group).toEqual({ 'zh-CN': '凭据内容', en: 'Credential Data' });
        detail.onSubmit({ cookies_json: '{"sid":"1"}' });
      },
      { once: true },
    );

    await actionRegistry.execute('command.execute', {
      fetchResult,
      args: {
        command: 'cr_account:set_credential',
        targetRecordPid: 'A1',
        operationType: 'update',
        payload: { keep: 'me' },
        inputFieldsTitle: 'Set Credential',
        inputFields: [
          {
            field: 'cookies_json',
            group: { 'zh-CN': '凭据内容', en: 'Credential Data' },
            label: 'Cookies',
            type: 'textarea',
            required: true,
          },
        ],
      },
    });

    expect(fetchResult).toHaveBeenCalledWith(
      '/api/meta/commands/execute/cr_account:set_credential',
      expect.objectContaining({
        method: 'post',
        params: expect.objectContaining({
          operationType: 'UPDATE',
          payload: { keep: 'me', cookies_json: '{"sid":"1"}' },
        }),
      }),
    );
  });

  it('maps paginated API records into input field select options', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      code: '0',
      data: {
        records: [
          { pid: 'emp-001', name: '张三' },
          { pid: 'emp-002', name: '李四' },
        ],
      },
    });
    const submitted = new Promise<Record<string, any>>((resolve) => {
      window.addEventListener(
        'dialog:form',
        (event) => {
          const detail = (event as CustomEvent).detail;
          expect(detail.fieldOptions.employeePid).toEqual([
            { value: 'emp-001', label: '张三' },
            { value: 'emp-002', label: '李四' },
          ]);
          detail.onSubmit({ employeePid: 'emp-001' });
          resolve({ employeePid: 'emp-001' });
        },
        { once: true },
      );
    });

    await expect(
      promptInputForm(
        [
          {
            field: 'employeePid',
            type: 'select',
            dataSource: {
              type: 'api',
              endpoint: '/api/org/employees?pageNum=1&pageSize=50',
              valueField: 'pid',
              labelField: 'name',
            },
          },
        ],
        '从人员开通账号',
        fetchResult,
      ),
    ).resolves.toEqual(await submitted);
  });

  it('resolves record placeholders in API option endpoints', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      code: '0',
      data: [{ memberPid: 'member-002', displayName: '李四' }],
    });
    window.addEventListener(
      'dialog:form',
      (event) => {
        const detail = (event as CustomEvent).detail;
        expect(detail.fieldOptions.targetMemberPid).toEqual([
          { value: 'member-002', label: '李四' },
        ]);
        detail.onSubmit({ targetMemberPid: 'member-002' });
      },
      { once: true },
    );

    await expect(
      promptInputForm(
        [
          {
            field: 'targetMemberPid',
            type: 'select',
            dataSource: {
              type: 'api',
              endpoint: '/api/tenant/members/${record.pid}/offboarding-candidates',
              valueField: 'memberPid',
              labelField: 'displayName',
            },
          },
        ],
        '资源交接',
        fetchResult,
        undefined,
        { record: { pid: 'member-001' } },
      ),
    ).resolves.toEqual({ targetMemberPid: 'member-002' });
    expect(fetchResult).toHaveBeenCalledWith(
      '/api/tenant/members/member-001/offboarding-candidates',
      { method: 'get' },
    );
  });

  it('preserves localized labels, descriptions, and disabled state from raw plugin option APIs', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      quoteId: 'Q-1',
      items: [
        {
          value: 'resistor',
          label: { 'zh-CN': '电阻', en: 'Resistor' },
          description: { 'zh-CN': '共 2 行 · 可确认 2', en: '2 lines · 2 confirmable' },
          disabled: false,
        },
        {
          value: 'unknown',
          label: { 'zh-CN': '未分类', en: 'Unclassified' },
          description: {
            'zh-CN': '共 1 行 · 该类别不开放快速确认',
            en: '1 line · not eligible for quick confirmation',
          },
          disabled: true,
        },
      ],
    });
    window.addEventListener(
      'dialog:form',
      (event) => {
        const detail = (event as CustomEvent).detail;
        expect(detail.fieldOptions.categories).toEqual([
          {
            value: 'resistor',
            label: { 'zh-CN': '电阻', en: 'Resistor' },
            description: { 'zh-CN': '共 2 行 · 可确认 2', en: '2 lines · 2 confirmable' },
          },
          {
            value: 'unknown',
            label: { 'zh-CN': '未分类', en: 'Unclassified' },
            description: {
              'zh-CN': '共 1 行 · 该类别不开放快速确认',
              en: '1 line · not eligible for quick confirmation',
            },
            disabled: true,
          },
        ]);
        detail.onSubmit({ categories: ['resistor'] });
      },
      { once: true },
    );

    await expect(
      promptInputForm(
        [
          {
            field: 'categories',
            type: 'multiselect',
            dataSource: {
              type: 'api',
              endpoint: '/api/ext/qoe/quotes/${form.pid}/price-confirmation-categories',
              valueField: 'value',
              labelField: 'label',
              descriptionField: 'description',
            },
          },
        ],
        '价格快速确认',
        fetchResult,
        undefined,
        { form: { pid: 'Q-1' } },
      ),
    ).resolves.toEqual({ categories: ['resistor'] });
    expect(fetchResult).toHaveBeenCalledWith(
      '/api/ext/qoe/quotes/Q-1/price-confirmation-categories',
      { method: 'get' },
    );
  });

  it('preserves API option descriptions and normalizes choice defaults', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      code: '0',
      data: {
        records: [
          { key: 's0-h0-c0', label: 'A · 规格', detail: '候选角色=spec' },
          { key: 's0-h0-c1', label: 'B · 型号', detail: '候选角色=mpn' },
        ],
      },
    });
    window.addEventListener(
      'dialog:form',
      (event) => {
        const detail = (event as CustomEvent).detail;
        expect(detail.fieldOptions.sourceColumns).toEqual([
          { value: 's0-h0-c0', label: 'A · 规格', description: '候选角色=spec' },
          { value: 's0-h0-c1', label: 'B · 型号', description: '候选角色=mpn' },
        ]);
        expect(detail.defaults).toEqual({
          sourceColumns: ['s0-h0-c0', 's0-h0-c1'],
          confirmedByUser: false,
        });
        detail.onSubmit(detail.defaults);
      },
      { once: true },
    );

    await expect(
      promptInputForm(
        [
          {
            field: 'sourceColumns',
            type: 'multiselect',
            defaultValue: 's0-h0-c0,s0-h0-c1',
            dataSource: {
              type: 'api',
              endpoint: '/api/dynamic/bom_import_analysis_item/list',
              params: {
                pageSize: 200,
                filters: [{ fieldName: 'bom_iai_item_type', operator: 'EQ', value: 'column' }],
              },
              valueField: 'key',
              labelField: 'label',
              descriptionField: 'detail',
            },
          },
          { field: 'confirmedByUser', type: 'checkbox', defaultValue: 'false' },
        ],
        '调整字段来源',
        fetchResult,
      ),
    ).resolves.toEqual({
      sourceColumns: ['s0-h0-c0', 's0-h0-c1'],
      confirmedByUser: false,
    });
    expect(fetchResult).toHaveBeenCalledWith('/api/dynamic/bom_import_analysis_item/list', {
      method: 'get',
      params: {
        pageSize: 200,
        filters: [{ fieldName: 'bom_iai_item_type', operator: 'EQ', value: 'column' }],
      },
    });
  });

  it('normalizes DSL field types, loads dictionary options, and resolves row defaults', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      code: '0',
      data: {
        code: 'crm_account_rating',
        items: [
          { value: 'A', label: 'A - 重点客户', enabled: true },
          { value: 'B', label: 'B - 重要客户', enabled: true },
        ],
      },
    });
    window.addEventListener(
      'dialog:form',
      (event) => {
        const detail = (event as CustomEvent).detail;
        expect(detail.fields.map((field: Record<string, any>) => field.type)).toEqual([
          'text',
          'number',
          'select',
        ]);
        expect(detail.fieldOptions.rating).toEqual([
          { value: 'A', label: 'A - 重点客户' },
          { value: 'B', label: 'B - 重要客户' },
        ]);
        expect(detail.defaults).toEqual({ name: '华南公海', limit: 20, rating: 'A' });
        detail.onSubmit(detail.defaults);
      },
      { once: true },
    );

    await expect(
      promptInputForm(
        [
          { field: 'name', type: 'string', defaultValue: '${row.name}' },
          { field: 'limit', type: 'integer', defaultValue: '${row.limit}' },
          {
            field: 'rating',
            type: 'enum',
            dictCode: 'crm_account_rating',
            defaultValue: '${row.rating}',
          },
        ],
        '快速编辑',
        fetchResult,
        undefined,
        { row: { name: '华南公海', limit: 20, rating: 'A' } },
      ),
    ).resolves.toEqual({ name: '华南公海', limit: 20, rating: 'A' });
    expect(fetchResult).toHaveBeenCalledWith('/api/meta/dict/by-code/crm_account_rating/data', {
      method: 'get',
    });
  });

  it('aborts (does not submit the command) when the user cancels the form', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: {} });
    window.addEventListener('dialog:form', (e) => (e as CustomEvent).detail.onCancel(), {
      once: true,
    });

    await actionRegistry.execute('command.execute', {
      fetchResult,
      args: {
        command: 'cr_account:set_credential',
        targetRecordPid: 'A1',
        inputFields: [{ field: 'cookies_json', type: 'textarea', required: true }],
      },
    });

    expect(fetchResult).not.toHaveBeenCalled();
  });

  it('is unchanged for commands without inputFields (backward compatible)', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: {} });

    await actionRegistry.execute('command.execute', {
      fetchResult,
      args: { command: 'x:do', targetRecordPid: 'A1', payload: { a: 1 } },
    });

    expect(fetchResult).toHaveBeenCalledWith(
      '/api/meta/commands/execute/x:do',
      expect.objectContaining({ params: expect.objectContaining({ payload: { a: 1 } }) }),
    );
  });

  it('downloads a Base64 file artifact returned by a command', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      code: '0',
      data: {
        success: true,
        data: {
          data: {
            fileName: 'batch-failures.csv',
            contentType: 'text/csv;charset=UTF-8',
            contentBase64: btoa('row,error\n1,INVALID'),
          },
        },
      },
    });
    const createObjectURL = vi
      .spyOn(URL, 'createObjectURL')
      .mockReturnValue('blob:command-artifact');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);

    await actionRegistry.execute('command.execute', {
      fetchResult,
      args: { command: 'iot_dps_batch_onboarding_job:export_failures', targetRecordPid: 'B1' },
    });

    expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    expect(click).toHaveBeenCalledOnce();
  });
});

describe('ActionRegistry dialog.confirm', () => {
  it('resolves object-form message to zh-CN string before passing to confirm dialog', async () => {
    const confirm = vi.fn().mockResolvedValue(true);

    await actionRegistry.execute('dialog.confirm', {
      confirm,
      args: {
        message: {
          'zh-CN': '确认应用此模板？这将在当前租户创建模板包含的模型与页面。',
          'en-US':
            "Install this template? It will create the template's models and pages in your tenant.",
        },
      },
    });

    expect(confirm).toHaveBeenCalledWith({
      content: '确认应用此模板？这将在当前租户创建模板包含的模型与页面。',
    });
  });

  it('throws when user cancels (object-form message)', async () => {
    const confirm = vi.fn().mockResolvedValue(false);

    await expect(
      actionRegistry.execute('dialog.confirm', {
        confirm,
        args: {
          message: { 'zh-CN': '确认？', 'en-US': 'Confirm?' },
        },
      }),
    ).rejects.toThrow('User cancelled');
  });
});

describe('ActionRegistry refresh', () => {
  it('prefers explicit data source targets over page-level loadData', async () => {
    const loadData = vi.fn();
    const reload = vi.fn().mockResolvedValue(undefined);

    await actionRegistry.execute('refresh', {
      loadData,
      dataSourceManager: { reload } as any,
      args: { target: 'ds_orders' },
    });

    expect(reload).toHaveBeenCalledWith('ds_orders');
    expect(loadData).not.toHaveBeenCalled();
  });
});

describe('ActionRegistry navigate / new / router.push handlers', () => {
  it('navigate jumps to args.path', async () => {
    const navigate = vi.fn();
    await actionRegistry.execute('navigate', { navigate, args: { path: '/p/orders' } });
    expect(navigate).toHaveBeenCalledWith('/p/orders');
  });

  it('navigate logs and is a no-op when the navigate fn is missing', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('navigate', { args: { path: '/p/orders' } });
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });

  it('navigate logs and is a no-op when path is missing', async () => {
    const navigate = vi.fn();
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('navigate', { navigate, args: {} });
    expect(navigate).not.toHaveBeenCalled();
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });

  it('new jumps to the model new route', async () => {
    const navigate = vi.fn();
    await actionRegistry.execute('new', { navigate, tableName: 'sl_order' });
    expect(navigate).toHaveBeenCalledWith('/p/sl_order/new');
  });

  it('new logs and is a no-op without tableName', async () => {
    const navigate = vi.fn();
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('new', { navigate });
    expect(navigate).not.toHaveBeenCalled();
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });

  it('router.push navigates to args.path', async () => {
    const navigate = vi.fn();
    await actionRegistry.execute('router.push', { navigate, args: { path: '/p/x' } });
    expect(navigate).toHaveBeenCalledWith('/p/x');
  });
});

describe('ActionRegistry search / reset / setState handlers', () => {
  it('search resets to page 1 and reloads with the current filters', async () => {
    const loadData = vi.fn();
    const setPagination = vi.fn();
    await actionRegistry.execute('search', {
      loadData,
      setPagination,
      filters: { status: 'open' },
    });
    expect(loadData).toHaveBeenCalledWith({ page: 0, filters: { status: 'open' } });
    const updater = setPagination.mock.calls[0][0];
    expect(updater({ current: 5, pageSize: 10 })).toEqual({ current: 1, pageSize: 10 });
  });

  it('reset clears filters and reloads from page 1', async () => {
    const loadData = vi.fn();
    const setPagination = vi.fn();
    const setFilters = vi.fn();
    await actionRegistry.execute('reset', { loadData, setPagination, setFilters });
    expect(setFilters).toHaveBeenCalledWith({});
    expect(loadData).toHaveBeenCalledWith({ page: 0, filters: {} });
    const updater = setPagination.mock.calls[0][0];
    expect(updater({ current: 3, pageSize: 20 })).toEqual({ current: 1, pageSize: 20 });
  });

  it('setState merges args into the filter state', async () => {
    const setFilters = vi.fn();
    await actionRegistry.execute('setState', { setFilters, args: { region: 'EMEA' } });
    const updater = setFilters.mock.calls[0][0];
    expect(updater({ status: 'open' })).toEqual({ status: 'open', region: 'EMEA' });
  });

  it('search/reset/setState log and no-op when required context is missing', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('search', {});
    await actionRegistry.execute('reset', {});
    await actionRegistry.execute('setState', { setFilters: vi.fn() }); // missing args
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });
});

describe('ActionRegistry registry API', () => {
  it('register / has / getRegisteredTypes / unregister round-trip', () => {
    const handler = vi.fn();
    actionRegistry.register('__unit_test_action__', handler);
    expect(actionRegistry.has('__unit_test_action__')).toBe(true);
    expect(actionRegistry.getRegisteredTypes()).toContain('__unit_test_action__');
    actionRegistry.unregister('__unit_test_action__');
    expect(actionRegistry.has('__unit_test_action__')).toBe(false);
  });

  it('registerBatch registers multiple handlers at once', () => {
    const a = vi.fn();
    const b = vi.fn();
    actionRegistry.registerBatch({ __unit_batch_a__: a, __unit_batch_b__: b });
    expect(actionRegistry.has('__unit_batch_a__')).toBe(true);
    expect(actionRegistry.has('__unit_batch_b__')).toBe(true);
    actionRegistry.unregister('__unit_batch_a__');
    actionRegistry.unregister('__unit_batch_b__');
  });
});

describe('ActionRegistry router.back / cancel handlers', () => {
  it('router.back and cancel both navigate back by -1', async () => {
    const navigate = vi.fn();
    await actionRegistry.execute('router.back', { navigate });
    await actionRegistry.execute('cancel', { navigate });
    expect(navigate).toHaveBeenCalledTimes(2);
    expect(navigate).toHaveBeenCalledWith(-1);
  });

  it('router.back and cancel log and no-op without navigate', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('router.back', {});
    await actionRegistry.execute('cancel', {});
    expect(spy).toHaveBeenCalledTimes(2);
    spy.mockRestore();
  });
});

describe('ActionRegistry dataSource handlers', () => {
  it('dataSource.fetch calls manager.fetch with target and args', async () => {
    const dataSourceManager = { fetch: vi.fn(), reload: vi.fn() };
    const args = { target: 'ds_orders', extra: 1 };
    await actionRegistry.execute('dataSource.fetch', { dataSourceManager, args });
    expect(dataSourceManager.fetch).toHaveBeenCalledWith('ds_orders', args);
  });

  it('dataSource.fetch logs and no-ops without a manager or a target', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('dataSource.fetch', {});
    await actionRegistry.execute('dataSource.fetch', { dataSourceManager: { fetch: vi.fn() } });
    expect(spy).toHaveBeenCalledTimes(2);
    spy.mockRestore();
  });

  it('dataSource.reload reloads a single target, an array, and cleans empty entries', async () => {
    const dataSourceManager = { reload: vi.fn() };
    await actionRegistry.execute('dataSource.reload', {
      dataSourceManager,
      args: { target: 'ds_a' },
    });
    expect(dataSourceManager.reload).toHaveBeenCalledWith('ds_a');

    await actionRegistry.execute('dataSource.reload', {
      dataSourceManager,
      args: { targets: ['ds_a', '', 'ds_b'] },
    });
    expect(dataSourceManager.reload).toHaveBeenCalledWith(['ds_a', 'ds_b']);
  });

  it('dataSource.reload logs and no-ops without a manager or resolvable targets', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('dataSource.reload', {});
    await actionRegistry.execute('dataSource.reload', {
      dataSourceManager: { reload: vi.fn() },
      args: { id: '' },
    });
    expect(spy).toHaveBeenCalledTimes(2);
    spy.mockRestore();
  });
});

describe('ActionRegistry toast handlers', () => {
  it('toast.show prefers args.message over args.content and resolves i18n objects', async () => {
    const showToast = vi.fn();
    await actionRegistry.execute('toast.show', {
      showToast,
      args: { message: { 'zh-CN': '已保存', 'en-US': 'Saved' } },
    });
    expect(showToast).toHaveBeenCalledWith('已保存', 'info');

    await actionRegistry.execute('toast.show', {
      showToast,
      args: { content: 'plain', level: 'warning' },
    });
    expect(showToast).toHaveBeenCalledWith('plain', 'warning');
  });

  it('toast.success and toast.error default missing messages and force the level', async () => {
    const showToast = vi.fn();
    await actionRegistry.execute('toast.success', { showToast, args: {} });
    expect(showToast).toHaveBeenCalledWith('Operation successful', 'success');

    await actionRegistry.execute('toast.error', { showToast, args: { content: { 'en-US': 'Oops' } } });
    expect(showToast).toHaveBeenCalledWith('Oops', 'error');

    await actionRegistry.execute('toast.success', { showToast, args: { message: {} } });
    expect(showToast).toHaveBeenCalledWith('Operation successful', 'success');
  });

  it('toasts tolerate a missing showToast sink', async () => {
    await expect(
      actionRegistry.execute('toast.show', { args: { message: 'hi' } }),
    ).resolves.toBeUndefined();
  });
});

describe('ActionRegistry dialog.form handler', () => {
  it('stores submitted values into the scoped form state', async () => {
    const stateManager = { updateForm: vi.fn() };
    window.addEventListener('dialog:form', (e) => {
      (e as CustomEvent).detail.onSubmit({ name: 'a', qty: 2 });
    }, { once: true });

    await actionRegistry.execute('dialog.form', {
      stateManager,
      scopeId: 'scope-1',
      args: { title: 'Add', fields: [{ field: 'name', type: 'input' }] },
    });

    expect(stateManager.updateForm).toHaveBeenCalledWith('scope-1', 'name', 'a');
    expect(stateManager.updateForm).toHaveBeenCalledWith('scope-1', 'qty', 2);
  });

  it('returns silently when the user cancels', async () => {
    const stateManager = { updateForm: vi.fn() };
    window.addEventListener('dialog:form', (e) => {
      (e as CustomEvent).detail.onCancel();
    }, { once: true });

    await actionRegistry.execute('dialog.form', {
      stateManager,
      scopeId: 'scope-1',
      args: { fields: [{ field: 'name' }] },
    });

    expect(stateManager.updateForm).not.toHaveBeenCalled();
  });

  it('logs and no-ops without fields in args', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('dialog.form', { args: {} });
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });
});

describe('ActionRegistry event.emit / noop handlers', () => {
  it('event.emit dispatches a CustomEvent with args as detail', () => {
    const listener = vi.fn();
    window.addEventListener('menu:refresh', listener);
    try {
      actionRegistry.execute('event.emit', { args: { event: 'menu:refresh', extra: 1 } });
      expect(listener).toHaveBeenCalledTimes(1);
      const detail = listener.mock.calls[0][0].detail;
      expect(detail.event).toBe('menu:refresh');
      expect(detail.extra).toBe(1);
    } finally {
      window.removeEventListener('menu:refresh', listener);
    }
  });

  it('event.emit logs and no-ops without an event name', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    actionRegistry.execute('event.emit', { args: {} });
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });

  it('noop resolves without touching anything', async () => {
    await expect(actionRegistry.execute('noop', {})).resolves.toBeUndefined();
  });
});

describe('ActionRegistry form.reset / state.set handlers', () => {
  it('form.reset resets the scoped form', async () => {
    const stateManager = { resetForm: vi.fn() };
    await actionRegistry.execute('form.reset', { stateManager, scopeId: 's1' });
    expect(stateManager.resetForm).toHaveBeenCalledWith('s1');
  });

  it('form.reset logs and no-ops without stateManager or scopeId', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('form.reset', {});
    await actionRegistry.execute('form.reset', { stateManager: { resetForm: vi.fn() } });
    expect(spy).toHaveBeenCalledTimes(2);
    spy.mockRestore();
  });

  it('state.set writes every args entry into the scoped state', async () => {
    const stateManager = { updateState: vi.fn() };
    await actionRegistry.execute('state.set', {
      stateManager,
      scopeId: 's1',
      args: { mode: 'compact', page: 2 },
    });
    expect(stateManager.updateState).toHaveBeenCalledWith('s1', 'mode', 'compact');
    expect(stateManager.updateState).toHaveBeenCalledWith('s1', 'page', 2);
  });

  it('state.set logs and no-ops without stateManager, scopeId, or args', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('state.set', {});
    await actionRegistry.execute('state.set', { stateManager: { updateState: vi.fn() } });
    await actionRegistry.execute('state.set', { stateManager: { updateState: vi.fn() }, scopeId: 's1' });
    expect(spy).toHaveBeenCalledTimes(3);
    spy.mockRestore();
  });
});

describe('ActionRegistry form.validate handler', () => {
  const makeState = (form: Record<string, unknown>) => ({
    getContext: vi.fn(() => ({ form })),
  });

  it('passes when no field carries validation rules', async () => {
    await expect(
      actionRegistry.execute('form.validate', {
        stateManager: makeState({ name: 'x' }),
        scopeId: 's1',
        args: { fields: [{ field: 'name' }] },
      }),
    ).resolves.toBeUndefined();
  });

  it('collects the first failing rule per field, toasts, and throws', async () => {
    const showToast = vi.fn();
    await expect(
      actionRegistry.execute('form.validate', {
        stateManager: makeState({ name: '', email: 'not-an-email' }),
        scopeId: 's1',
        showToast,
        args: {
          fields: [
            { field: 'name', label: 'Name', validation: [{ type: 'required', message: 'Name is required' }] },
            { field: 'email', validation: [{ type: 'email', message: 'Bad email' }] },
          ],
        },
      }),
    ).rejects.toThrow('Form validation failed');

    expect(showToast).toHaveBeenCalledWith(
      expect.stringContaining('name: Name is required'),
      'error',
    );
  });

  it('logs and no-ops without stateManager or scopeId', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('form.validate', {});
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });
});

describe('ActionRegistry ui.openContainer / ui.closeContainer handlers', () => {
  it('drawer.form create/edit routes navigate from schema.modelCode', async () => {
    const navigate = vi.fn();
    const ctx = { navigate, schema: { modelCode: 'sl_order' } };
    await actionRegistry.execute('ui.openContainer', {
      ...ctx,
      stepTarget: 'drawer.form',
      args: { mode: 'create' },
    });
    expect(navigate).toHaveBeenCalledWith('/p/sl_order/new');

    await actionRegistry.execute('ui.openContainer', {
      ...ctx,
      args: { target: 'form', mode: 'edit', id: 'R9' },
    });
    expect(navigate).toHaveBeenCalledWith('/p/sl_order/edit/R9');
  });

  it('parses modelCode from a "list.model" schema.id and opens detail views', async () => {
    const navigate = vi.fn();
    await actionRegistry.execute('ui.openContainer', {
      navigate,
      schema: { id: 'list.sl_order' },
      args: { target: 'drawer.detail', id: 'R7' },
    });
    expect(navigate).toHaveBeenCalledWith('/p/sl_order/view/R7');
  });

  it('warns on unknown mode/detail without id/unknown target, and errors without navigate or model', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const navigate = vi.fn();

    await actionRegistry.execute('ui.openContainer', {
      navigate,
      schema: { modelCode: 'm1' },
      args: { target: 'drawer.form', mode: 'weird' },
    });
    await actionRegistry.execute('ui.openContainer', {
      navigate,
      schema: { modelCode: 'm1' },
      args: { target: 'drawer.detail' },
    });
    await actionRegistry.execute('ui.openContainer', {
      navigate,
      schema: { modelCode: 'm1' },
      args: { target: 'teleport' },
    });
    await actionRegistry.execute('ui.openContainer', {
      schema: { modelCode: 'm1' },
      args: { target: 'form' },
    });
    await actionRegistry.execute('ui.openContainer', {
      navigate,
      schema: {},
      args: { target: 'form' },
    });

    expect(navigate).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalledTimes(3);
    expect(errorSpy).toHaveBeenCalledTimes(2);
    errorSpy.mockRestore();
    warnSpy.mockRestore();
  });

  it('ui.closeContainer navigates back and tolerates a missing navigate', async () => {
    const navigate = vi.fn();
    await actionRegistry.execute('ui.closeContainer', { navigate });
    expect(navigate).toHaveBeenCalledWith(-1);
    await expect(actionRegistry.execute('ui.closeContainer', {})).resolves.toBeUndefined();
  });
});

describe('ActionRegistry notify handler', () => {
  it('notify(dataSource reload) reloads the payload data source', async () => {
    const dataSourceManager = { reload: vi.fn() };
    await actionRegistry.execute('notify', {
      dataSourceManager,
      args: { channel: 'dataSource', payload: { id: 'ds_x', event: 'reload' } },
    });
    expect(dataSourceManager.reload).toHaveBeenCalledWith('ds_x');
  });

  it('notify warns on missing channel/manager/id and unknown channels/events', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const dataSourceManager = { reload: vi.fn() };

    await actionRegistry.execute('notify', { args: {} });
    await actionRegistry.execute('notify', { args: { channel: 'dataSource' } });
    await actionRegistry.execute('notify', {
      args: { channel: 'dataSource', payload: { event: 'reload' } },
      dataSourceManager,
    });
    await actionRegistry.execute('notify', {
      args: { channel: 'dataSource', payload: { id: 'ds_x', event: 'purge' } },
      dataSourceManager,
    });
    await actionRegistry.execute('notify', {
      args: { channel: 'sms' },
      dataSourceManager,
    });

    expect(warnSpy).toHaveBeenCalledTimes(5);
    expect(dataSourceManager.reload).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });
});
