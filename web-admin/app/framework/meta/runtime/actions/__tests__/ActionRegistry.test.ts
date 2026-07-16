import { describe, expect, it, vi } from 'vitest';

import { actionRegistry, promptInputForm } from '~/framework/meta/runtime/actions/ActionRegistry';

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
  it('pops a form (FormDialog) and merges collected values into the command payload', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: { ok: true } });
    // Simulate the user filling + submitting the FormDialog the action pops.
    window.addEventListener(
      'dialog:form',
      (e) => (e as CustomEvent).detail.onSubmit({ cookies_json: '{"sid":"1"}' }),
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
        inputFields: [{ field: 'cookies_json', label: 'Cookies', type: 'textarea', required: true }],
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

  it('aborts (does not submit the command) when the user cancels the form', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: {} });
    window.addEventListener(
      'dialog:form',
      (e) => (e as CustomEvent).detail.onCancel(),
      { once: true },
    );

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
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:command-artifact');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

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
          'en-US': "Install this template? It will create the template's models and pages in your tenant.",
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
