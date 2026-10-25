import { describe, expect, it, vi } from 'vitest';

import { actionRegistry, promptInputForm } from '~/framework/meta/runtime/actions/ActionRegistry';
import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { FlowRunner } from '~/framework/meta/runtime/schema-runtime/FlowRunner';
import { ExpressionEvaluator } from '~/framework/meta/runtime/expression/evaluator';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { ActionCancelledError } from '../ActionCancelledError';
import { executeFetch } from '~/shared/services/http-client/FetchExecutor';
import { registerConfirmDialog, unregisterConfirmDialog } from '~/utils/confirmDialog';

describe('ActionRegistry command failure feedback', () => {
  it('carries the current page language through a flow to its command handler', async () => {
    const context = createExpressionContext({global:{locale:'zh-CN',theme:'light'}});
    const fetchResult = vi.fn().mockResolvedValue({code:'35000',message:'Bad parameter',
      context:{detail:'Record not found: SECRET-PID in model: internal_model'}});
    const flow = new FlowRunner({
      evaluator:{evaluateCondition:()=>true, evaluateObject:(value:unknown)=>value} as any, actionRegistry,
      stateManager:{getContext:()=>context} as any, scopeId:'feedback-test',
      dataSourceManager:{} as any, schema:{} as any, getAllFormFields:()=>[],
    });
    await expect(flow.run([{action:'command.execute',args:{command:'quote:create_version'}}],
      {...context,fetchResult} as any)).rejects.toThrow('关联记录不存在或不可访问，请重新选择后再提交。');
  });
  it('preserves the actionable reason from a failed flow command', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      code: '35000', message: 'Bad parameter',
      context: { detail: 'Plugin handler execution failed: Approval requires a decision note' },
    });
    await expect(actionRegistry.execute('command.execute', {
      fetchResult, args: { command: 'quote:approve' },
    })).rejects.toThrow('Approval requires a decision note');
  });

  it('explains unavailable referenced records without exposing internal identifiers', async () => {
    const fetchResult = vi.fn().mockResolvedValue({
      code: '35000', message: 'Bad parameter',
      context: { detail: 'Plugin handler execution failed: Record not found: SECRET-PID in model: internal_model' },
    });
    await expect(actionRegistry.execute('command.execute', {
      fetchResult, locale: 'zh-CN', args: { command: 'quote:create_version' },
    })).rejects.toThrow('关联记录不存在或不可访问，请重新选择后再提交。');
  });
});

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

    await expect(actionRegistry.execute('command.execute', {
      fetchResult,
      args: {
        command: 'cr_account:set_credential',
        targetRecordPid: 'A1',
        inputFields: [{ field: 'cookies_json', type: 'textarea', required: true }],
      },
    })).rejects.toBeInstanceOf(ActionCancelledError);

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
  it('aborts the real flow before its command and navigation when confirmation is cancelled', async () => {
    const fetchResult = vi.fn();
    const navigate = vi.fn();
    const context = createExpressionContext();
    const flow = new FlowRunner({
      evaluator: new ExpressionEvaluator(), actionRegistry,
      stateManager: { getContext: () => context } as any, scopeId: 'cancel-test',
      dataSourceManager: {} as any, schema: {} as any, getAllFormFields: () => [], navigate,
    });
    registerConfirmDialog(async () => false);
    try {
      await expect(flow.run([
        { action: 'dialog.confirm', args: { message: 'Confirm?' } },
        { action: 'command.execute', args: { command: 'purchase:allocate' } },
        { action: 'router.back' },
      ], { ...context, fetchResult } as any)).rejects.toBeInstanceOf(ActionCancelledError);
      expect(fetchResult).not.toHaveBeenCalled();
      expect(navigate).not.toHaveBeenCalled();
    } finally {
      unregisterConfirmDialog();
    }
  });

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
  it.each(['navigate', 'router.push'])('resolves %s from the accepted API result in the real flow', async action => {
    const context = createExpressionContext();
    const navigate = vi.fn();
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: { snapshotToken: 'sealed-capture' } });
    const manager = { getContext: () => context, updateState: (_scope: string, key: string, value: unknown) => { context.state[key] = value; } };
    const flow = new FlowRunner({ evaluator: new ExpressionEvaluator(), actionRegistry,
      stateManager: manager as any, scopeId: 'capture-route', dataSourceManager: {} as any,
      schema: {} as any, getAllFormFields: () => [], navigate });
    await flow.run([
      { action: 'api.request', method: 'post', endpoint: '/api/statistics/captures', body: { state: 'all' }, target: 'capture' },
      { action, args: { path: '/p/statistics/view/${state.capture.snapshotToken}' } },
    ], { ...context, fetchResult } as any);
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/p/statistics/view/sealed-capture');
  });

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

describe('ActionRegistry controlled export', () => {
  it('declares filtered scope and sends normalized conditions', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '35000', message: 'fixture stop' });
    const filters = [{ field: 'status', operator: 'EQ', value: 'active' }];

    await actionRegistry.execute('export', {
      tableName: 'customer',
      filters,
      fetchResult,
      buildApiEndpoint: (tableName) => `/api/dynamic/${tableName}`,
    });

    expect(fetchResult).toHaveBeenCalledWith('/api/dynamic/customer/export', {
      method: 'post',
      params: { scope: 'filtered', conditions: filters },
      token: undefined,
    });
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
    const dataSourceManager = { fetch: vi.fn(), reload: vi.fn() } as unknown as DataSourceManager;
    const args = { target: 'ds_orders', extra: 1 };
    await actionRegistry.execute('dataSource.fetch', { dataSourceManager, args });
    expect(dataSourceManager.fetch).toHaveBeenCalledWith('ds_orders', args);
  });

  it('dataSource.fetch logs and no-ops without a manager or a target', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    await actionRegistry.execute('dataSource.fetch', {});
    await actionRegistry.execute('dataSource.fetch', { dataSourceManager: { fetch: vi.fn() } as unknown as DataSourceManager });
    expect(spy).toHaveBeenCalledTimes(2);
    spy.mockRestore();
  });

  it('dataSource.reload reloads a single target, an array, and cleans empty entries', async () => {
    const dataSourceManager = { reload: vi.fn() } as unknown as DataSourceManager;
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
      dataSourceManager: { reload: vi.fn() } as unknown as DataSourceManager,
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
  it.each(['toast.show', 'toast.success', 'toast.error'])('%s honors the active locale and catalog messages', async (action) => {
    const showToast=vi.fn();
    await actionRegistry.execute(action,{showToast,locale:'en-US',args:{message:{'zh-CN':'管理操作已记录','en-US':'Administration recorded'}}});
    expect(showToast).toHaveBeenLastCalledWith('Administration recorded',expect.any(String));
    await actionRegistry.execute(action,{showToast,locale:'zh-CN',t:key=>key==='recovery.recorded'?'管理操作已记录':key,args:{message:'$i18n:recovery.recorded'}});
    expect(showToast).toHaveBeenLastCalledWith('管理操作已记录',expect.any(String));
  });
});

describe('ActionRegistry dialog.form handler', () => {
  it.each(['dialog.form', 'command.execute'])('stops downstream writes and success feedback after cancelling %s', async (action) => {
    const manager = new ScopedStateManager({ locale: 'en', theme: 'light' } as any);
    manager.createScope('cancel-input', { form: { category: 'previous selection' } });
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: {} });
    const showToast = vi.fn();
    const flow = new FlowRunner({ evaluator: new ExpressionEvaluator(), actionRegistry,
      stateManager: manager, scopeId: 'cancel-input', dataSourceManager: {} as any,
      schema: {} as any, getAllFormFields: () => [] });
    window.addEventListener('dialog:form', (event) => (event as CustomEvent).detail.onCancel(), { once: true });
    const fields = [{ field: 'category', type: 'text' }];
    await expect(flow.run([
      { action, args: action === 'dialog.form' ? { fields } : { command: 'policy:create', inputFields: fields } },
      { action: 'command.execute', args: { command: 'policy:save', payload: { category: '${form.category}' } } },
      { action: 'toast.show', args: { message: 'Saved', level: 'success' } },
    ], { ...manager.getContext('cancel-input'), fetchResult, showToast } as any)).rejects.toBeInstanceOf(ActionCancelledError);
    expect(fetchResult).not.toHaveBeenCalled();
    expect(showToast).not.toHaveBeenCalled();
    expect(manager.getContext('cancel-input').form?.category).toBe('previous selection');
  });

  it('resolves choice endpoints and defaults from the current flow context', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: [{ value: 'supplier-a', label: 'Supplier A' }] });
    const stateManager = { updateForm: vi.fn() };
    let detail: any;
    window.addEventListener('dialog:form', (event) => { detail = (event as CustomEvent).detail;
      detail.onSubmit({ suppliers: ['supplier-a'] }); }, { once: true });
    await actionRegistry.execute('dialog.form', { fetchResult, stateManager, scopeId: 'current',
      expressionContext: { form: { connection: 'connection-a', suppliers: ['supplier-a'] } } as any,
      args: { fields: [{ field: 'suppliers', type: 'multiselect', defaultValue: '${form.suppliers}',
        dataSource: { type: 'api', endpoint: '/api/connections/${form.connection}/suppliers' } }] } });
    expect(fetchResult).toHaveBeenCalledWith('/api/connections/connection-a/suppliers', { method: 'get' });
    expect(detail.defaults.suppliers).toEqual(['supplier-a']);
    expect(stateManager.updateForm).toHaveBeenCalledWith('current', 'suppliers', ['supplier-a']);
  });

  it('propagates invalid provider defaults instead of treating them as user cancellation', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: [{ value: 'group', label: 'Group', options: [], preferred: ['foreign'] }] });
    await expect(actionRegistry.execute('command.execute', { fetchResult, args: { command: 'policy:save',
      inputFields: [{ field: 'groups', type: 'grouped-multiselect', props: { defaultValuesField: 'preferred' },
        dataSource: { type: 'api', endpoint: '/api/choices' } }] } })).rejects.toThrow('Invalid grouped defaults');
    expect(fetchResult).toHaveBeenCalledTimes(1);
    expect(fetchResult.mock.calls[0][0]).toBe('/api/choices');
  });
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

  it('signals deliberate cancellation without changing form values', async () => {
    const stateManager = { updateForm: vi.fn() };
    window.addEventListener('dialog:form', (e) => {
      (e as CustomEvent).detail.onCancel();
    }, { once: true });

    await expect(actionRegistry.execute('dialog.form', {
      stateManager,
      scopeId: 'scope-1',
      args: { fields: [{ field: 'name' }] },
    })).rejects.toBeInstanceOf(ActionCancelledError);

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
    const dataSourceManager = { reload: vi.fn() } as unknown as DataSourceManager;
    await actionRegistry.execute('notify', {
      dataSourceManager,
      args: { channel: 'dataSource', payload: { id: 'ds_x', event: 'reload' } },
    });
    expect(dataSourceManager.reload).toHaveBeenCalledWith('ds_x');
  });

  it('notify warns on missing channel/manager/id and unknown channels/events', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const dataSourceManager = { reload: vi.fn() } as unknown as DataSourceManager;

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

describe('ActionRegistry API request failure boundaries', () => {
  it.each(['35000', '403'])('stops the flow on business code %s before state or success feedback', async (code) => {
    const context = createExpressionContext({ global: { locale: 'zh-CN', theme: 'light' } });
    const updateState = vi.fn();
    const showToast = vi.fn();
    const flow = new FlowRunner({
      evaluator: { evaluateCondition: () => true, evaluateTemplate: (value: string) => value, evaluateObject: (value: unknown) => value } as any,
      actionRegistry,
      stateManager: { getContext: () => context, updateState } as any,
      scopeId: 'api-failure-boundary',
      dataSourceManager: {} as any,
      schema: {} as any,
      getAllFormFields: () => [],
      showToast,
    });
    const fetchResult = vi.fn().mockResolvedValue({
      code, success: true, message: 'Request denied',
      data: { valid: true }, context: { detail: 'The signing request was denied.' },
    });
    await expect(flow.run([
      { action: 'api.request', endpoint: '/api/bpm/signatures/sign', method: 'post',
        body: { documentId: 'LEAVE-ACCEPTANCE' }, target: 'signature' },
      { action: 'toast.show', args: { message: 'Signed successfully', level: 'success' } },
    ], { ...context, fetchResult, showToast } as any)).rejects.toThrow('The signing request was denied.');
    expect(updateState).not.toHaveBeenCalled();
    expect(showToast).not.toHaveBeenCalled();
  });

  it('rejects a raw unwrapped response rather than continuing with empty data', async () => {
    await expect(actionRegistry.execute('api.request', {
      fetchResult: vi.fn().mockResolvedValue({ valid: true, signatureCount: 1 }),
      args: { endpoint: '/api/bpm/signatures/verify/LEAVE-ACCEPTANCE' },
    })).rejects.toThrow();
  });

  it('retains successful request payload and updates the declared state target', async () => {
    const data = { valid: true, signatureCount: 1 };
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data });
    const updateState = vi.fn();
    await expect(actionRegistry.execute('api.request', {
      fetchResult,
      stateManager: { updateState } as any,
      scopeId: 'api-success-boundary',
      args: { endpoint: '/api/bpm/signatures/sign', method: 'post',
        body: { documentId: 'LEAVE-ACCEPTANCE' }, target: 'signature' },
    })).resolves.toBeUndefined();
    expect(fetchResult).toHaveBeenCalledWith('/api/bpm/signatures/sign', {
      method: 'post', params: { documentId: 'LEAVE-ACCEPTANCE' },
    });
    expect(updateState).toHaveBeenCalledWith('api-success-boundary', 'signature', data);
  });
});


describe('ActionRegistry command snapshot version', () => {
  it('retains the reviewed version across a form and sends only explicit payload fields', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: {} });
    const snapshot = { record: { pid: 'TASK-1', row_version: 7, secret: 'not-a-command-input' } };
    const evaluator = { evaluateTemplate: (value: string) => value === '${record.row_version}'
      ? String(snapshot.record.row_version) : snapshot.record.pid,
      evaluateObject: (value: unknown) => value } as any;
    window.addEventListener('dialog:form', (event) => {
      snapshot.record.row_version = 8;
      (event as CustomEvent).detail.onSubmit({ remark: 'Updated delivery plan' });
    }, { once: true });
    await actionRegistry.execute('command.execute', { fetchResult, expressionEvaluator: evaluator,
      expressionContext: snapshot as any, args: { command: 'task:maintain_plan',
        targetRecordPid: '${record.pid}', expectedVersion: '${record.row_version}', idempotent: true,
        inputFields: [{ field: 'remark', type: 'textarea' }], payload: {} } });
    const body = fetchResult.mock.calls[0][1].params;
    expect(body).toEqual({ targetRecordPid: 'TASK-1', expectedVersion: 7,
      clientRequestId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      payload: { remark: 'Updated delivery plan' } });
    expect(fetchResult).toHaveBeenCalledOnce();
  });

  it.each(['', ' ', '${record.row_version}', -1, 1.5, true, null, Number.MAX_SAFE_INTEGER + 1])(
    'rejects an unavailable version before opening or submitting a form: %s', async (expectedVersion) => {
      const fetchResult = vi.fn();
      const opened = vi.fn();
      window.addEventListener('dialog:form', opened);
      try {
        await expect(actionRegistry.execute('command.execute', { fetchResult,
          args: { command: 'task:maintain_plan', expectedVersion,
            inputFields: [{ field: 'remark', type: 'textarea' }] } })).rejects.toThrow();
        expect(opened).not.toHaveBeenCalled();
        expect(fetchResult).not.toHaveBeenCalled();
      } finally { window.removeEventListener('dialog:form', opened); }
    });
});


describe('ActionRegistry grouped API choices', () => {
  const defaultField = { field: 'groups', type: 'grouped-multiselect',
    props: { groupField: 'category', valuesField: 'supplierPids', defaultValuesField: 'preferred' },
    dataSource: { type: 'api', endpoint: '/api/group-options' } };

  it('initializes independent configured defaults and returns the later manual selection', async () => {
    const groups = [
      { value: 'a', label: 'A', preferred: ['s1'], options: [{ value: 's1', label: 'One' }, { value: 's2', label: 'Two' }] },
      { value: 'b', label: 'B', preferred: [], options: [{ value: 's2', label: 'Two' }] },
    ];
    const edited = [{ category: 'a', supplierPids: ['s2'] }, { category: 'b', supplierPids: ['s2'] }];
    let detail: any;
    window.addEventListener('dialog:form', (event) => {
      detail = (event as CustomEvent).detail;
      detail.onSubmit({ groups: edited });
    }, { once: true });
    const result = await promptInputForm([defaultField], 'Select', vi.fn().mockResolvedValue({ code: '0', data: { items: groups } }));
    expect(detail.defaults.groups).toEqual([{ category: 'a', supplierPids: ['s1'] }, { category: 'b', supplierPids: [] }]);
    expect(result.groups).toEqual(edited);
    expect(groups[0].preferred).toEqual(['s1']);
  });

  it('preserves explicitly supplied form defaults ahead of provider suggestions', async () => {
    const saved = [{ category: 'a', supplierPids: ['s2'] }];
    let detail: any;
    window.addEventListener('dialog:form', (event) => { detail = (event as CustomEvent).detail; detail.onCancel(); }, { once: true });
    await expect(promptInputForm([{ ...defaultField, defaultValue: saved }], 'Select',
      vi.fn().mockResolvedValue({ code: '0', data: [{ value: 'a', label: 'A', preferred: ['s1'], options: [] }] })))
      .rejects.toThrow('User cancelled');
    expect(detail.defaults.groups).toEqual(saved);
  });

  it.each([
    { preferred: ['foreign'] }, { preferred: ['s1', 's1'] }, { preferred: 's1' }, {},
    { preferred: ['s1'], disabled: true },
    { preferred: ['s1'], options: [{ value: 's1', label: 'One', disabled: true }] },
  ])('rejects invalid provider defaults before opening a form: %j', async (invalid) => {
    const listener = vi.fn();
    window.addEventListener('dialog:form', listener);
    try {
      await expect(promptInputForm([defaultField], 'Select', vi.fn().mockResolvedValue({ code: '0', data: [
        { value: 'a', label: 'A', options: [{ value: 's1', label: 'One' }], ...invalid },
      ] }))).rejects.toThrow('Invalid grouped defaults');
      expect(listener).not.toHaveBeenCalled();
    } finally { window.removeEventListener('dialog:form', listener); }
  });

  it('retains group-scoped choices and disabled flags without exposing unrelated provider fields', async () => {
    const fetchResult = vi.fn().mockResolvedValue({ code: '0', data: { items: [
      { value: 'category-a', label: 'Resistors', secret: 'not-for-ui', options: [
        { value: 'supplier-a', label: 'Supplier A', secret: 'not-for-ui' },
        { value: 'supplier-b', label: 'Supplier B', disabled: true },
        { label: 'No identity' },
      ] },
    ] } });
    let detail: any;
    window.addEventListener('dialog:form', (event) => { detail = (event as CustomEvent).detail; detail.onCancel(); }, { once: true });
    await expect(promptInputForm([{ field: 'groups', type: 'grouped-multiselect', dataSource: {
      type: 'api', endpoint: '/api/group-options' } }], 'Select', fetchResult)).rejects.toThrow('User cancelled');
    expect(detail.fieldOptions.groups).toEqual([{ value: 'category-a', label: 'Resistors', options: [
      { value: 'supplier-a', label: 'Supplier A' }, { value: 'supplier-b', label: 'Supplier B', disabled: true },
    ] }]);
  });
});


describe('ActionRegistry explicit form replacement', () => {
  it('places an API result into the form through the real expression pipeline without changing unrelated edits', async () => {
    const manager = new ScopedStateManager({ locale: 'zh-CN', theme: 'light' } as any);
    manager.createScope('plan', { form: { note: 'Keep my edit', rows: [{ quantity: '2' }] } });
    const rows = [{ quantity: '12345678901234567890.000001' }, { quantity: '0' }];
    const flow = new FlowRunner({ evaluator: new ExpressionEvaluator(), actionRegistry,
      stateManager: manager, scopeId: 'plan', dataSourceManager: {} as any,
      schema: {} as any, getAllFormFields: () => [] });
    await flow.run([
      { action: 'api.request', endpoint: '/api/example/read-only-plan', method: 'post',
        body: { rows: '${form.rows}' }, target: 'proposal' },
      { action: 'form.set', args: { rows: '${state.proposal.rows}' } },
    ], { ...manager.getContext('plan'), fetchResult: vi.fn().mockResolvedValue({ code: '0', data: { rows } }) } as any);
    expect(manager.getContext('plan').form).toEqual({ note: 'Keep my edit', rows });
  });
  it('rejects unresolved expressions before replacing any field', async () => {
    const updateForm = vi.fn();
    await expect(actionRegistry.execute('form.set', { stateManager: { updateForm } as any,
      scopeId: 'plan', args: { note: 'Changed', rows: '${state.missing}' },
      expressionEvaluator: new ExpressionEvaluator(), expressionContext: createExpressionContext() }))
      .rejects.toThrow('unresolved field value');
    expect(updateForm).not.toHaveBeenCalled();
  });
  it('does not replace form edits when the preceding API request fails', async () => {
    const manager = new ScopedStateManager({ locale: 'en', theme: 'light' } as any);
    manager.createScope('failed-plan', { form: { rows: [{ quantity: '3.000001' }] } });
    const flow = new FlowRunner({ evaluator: new ExpressionEvaluator(), actionRegistry,
      stateManager: manager, scopeId: 'failed-plan', dataSourceManager: {} as any,
      schema: {} as any, getAllFormFields: () => [] });
    await expect(flow.run([
      { action: 'api.request', endpoint: '/api/example/read-only-plan', target: 'proposal' },
      { action: 'form.set', args: { rows: '${state.proposal.rows}' } },
    ], { ...manager.getContext('failed-plan'), fetchResult: vi.fn().mockResolvedValue({ code: '403', message: 'Denied' }) } as any)).rejects.toThrow();
    expect(manager.getContext('failed-plan').form?.rows).toEqual([{ quantity: '3.000001' }]);
  });
});


describe('ActionRegistry dynamic choice load failure boundaries', () => {
  const apiField = { field: 'selection', type: 'grouped-multiselect', required: true,
    dataSource: { type: 'api', endpoint: '/api/example/choices' } };
  const dictField = { field: 'selection', type: 'select', dictCode: 'example_states' };
  it.each([
    ['command.execute', apiField, '35000'], ['dialog.form', apiField, '40000'],
    ['command.execute', dictField, '40000'], ['dialog.form', dictField, '35000'],
  ])('aborts before the dialog for rejected %s choice results (%s / %s)', async (action, field, code) => {
    const dialog = vi.fn((event: Event) => (event as CustomEvent).detail.onCancel());
    window.addEventListener('dialog:form', dialog);
    const fetchResult = vi.fn().mockResolvedValue({ code, success: true, httpStatus: 400,
      data: null, context: { exception: 'BusinessException', detail: 'Classify the lines before dispatching.' } });
    try {
      await expect(actionRegistry.execute(action as string, { fetchResult, locale: 'en-US', t: key => key,
        args: action === 'command.execute' ? { command: 'example:update', inputFields: [field] } : { fields: [field] },
      })).rejects.toThrow('Classify the lines before dispatching.');
      expect(dialog).not.toHaveBeenCalled();
      expect(fetchResult).toHaveBeenCalledTimes(1);
      expect(fetchResult.mock.calls[0][1]).toMatchObject({ method: 'get' });
    } finally { window.removeEventListener('dialog:form', dialog); }
  });
  it.each([
    { code: '35000', httpStatus: 401 }, { code: '35000', httpStatus: 403 },
    { code: '35000', httpStatus: 500 }, { code: 'NETWORK_ERROR' },
    { code: '35000', httpStatus: 400, exception: 'IllegalStateException' },
  ])('uses localized fallback rather than untrusted diagnostics: %j', async ({ code, httpStatus, exception }) => {
    const dialog = vi.fn((event: Event) => (event as CustomEvent).detail.onCancel());
    window.addEventListener('dialog:form', dialog);
    try {
      await expect(actionRegistry.execute('command.execute', { locale: 'zh-CN',
        t: key => key === 'error.data_load_failed' ? '无法读取选项，请重试。' : key,
        fetchResult: vi.fn().mockResolvedValue({ code, httpStatus, data: null,
          message: 'SECRET transport / internal_model', context: { detail: 'SECRET raw diagnostic', ...(exception ? { exception } : {}) } }),
        args: { command: 'example:update', inputFields: [apiField] },
      })).rejects.toThrow('无法读取选项，请重试。');
      expect(dialog).not.toHaveBeenCalled();
    } finally { window.removeEventListener('dialog:form', dialog); }
  });
  it('uses localized fallback on thrown transport errors without opening or submitting', async () => {
    const dialog = vi.fn((event: Event) => (event as CustomEvent).detail.onCancel());
    window.addEventListener('dialog:form', dialog);
    const fetchResult = vi.fn().mockRejectedValue(new Error('SECRET network host and token'));
    try {
      await expect(actionRegistry.execute('dialog.form', { fetchResult, locale: 'en-US',
        t: key => key === 'error.data_load_failed' ? 'Could not load choices. Try again.' : key,
        args: { fields: [apiField] },
      })).rejects.toThrow('Could not load choices. Try again.');
      expect(dialog).not.toHaveBeenCalled();expect(fetchResult).toHaveBeenCalledTimes(1);
    } finally { window.removeEventListener('dialog:form', dialog); }
  });
  it('preserves actual HTTP200 raw plugin options through SDK normalization and the dialog', async () => {
    const dialog = vi.fn((event: Event) => (event as CustomEvent).detail.onCancel());
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ quoteId: 'Q-1', items: [{ value: 'a', label: 'A' }] }), { status: 200 }));
    window.addEventListener('dialog:form', dialog);
    try {
      await expect(actionRegistry.execute('dialog.form', { fetchResult: (url, options) => executeFetch(url, options),
        args: { fields: [{ ...apiField, type: 'select' }] } })).rejects.toBeInstanceOf(ActionCancelledError);
      expect((dialog.mock.calls[0][0] as CustomEvent).detail.fieldOptions.selection).toEqual([{ value: 'a', label: 'A' }]);
    } finally { globalThis.fetch = originalFetch; window.removeEventListener('dialog:form', dialog); }
  });
  it.each([
    { code: '35000', httpStatus: 200 }, { code: '403', httpStatus: 403 },
    { code: '', context: { detail: 'SECRET' } }, { code: '', message: 'SECRET unknown failure' },
    { success: false }, { code: '0', success: false },
    { code: '0', httpStatus: 403 }, { code: '0', httpStatus: 500 },
  ])('never treats a rejected result containing options as legacy success: %j', async failure => {
    const dialog = vi.fn((event: Event) => (event as CustomEvent).detail.onCancel());
    window.addEventListener('dialog:form', dialog);
    try {
      await expect(actionRegistry.execute('dialog.form', { locale: 'en-US',
        t: key => key === 'error.data_load_failed' ? 'Could not load choices.' : key,
        fetchResult: vi.fn().mockResolvedValue({ ...failure, items: [] }), args: { fields: [apiField] },
      })).rejects.toThrow('Could not load choices.');
      expect(dialog).not.toHaveBeenCalled();
    } finally { window.removeEventListener('dialog:form', dialog); }
  });
  it.each([apiField, dictField])('keeps successful empty choice reads available to the existing dialog', async field => {
    const dialog = vi.fn((event: Event) => (event as CustomEvent).detail.onCancel());
    window.addEventListener('dialog:form', dialog);
    try {
      await expect(actionRegistry.execute('dialog.form', { fetchResult: vi.fn().mockResolvedValue({ code: '0', data: [] }),
        args: { fields: [field] } })).rejects.toBeInstanceOf(ActionCancelledError);
      expect(dialog).toHaveBeenCalledTimes(1);
      expect((dialog.mock.calls[0][0] as CustomEvent).detail.fieldOptions.selection).toEqual([]);
    } finally { window.removeEventListener('dialog:form', dialog); }
  });
});

describe('Scoped stable request-key action and real flow transport', () => {
  it('reuses the domain key after failed transport and refresh, clears only explicitly after success', async () => {
    const manager = new ScopedStateManager({locale:'en',theme:'light'} as any);
    manager.createScope('shipment', {form:{quantity:'250.0000'}});
    const flow = new FlowRunner({evaluator:new ExpressionEvaluator(),actionRegistry,stateManager:manager,scopeId:'shipment',dataSourceManager:{} as any,schema:{} as any,getAllFormFields:()=>[]});
    const fetchResult = vi.fn().mockResolvedValueOnce({code:'400',message:'Rejected'}).mockResolvedValue({code:'0',data:{ok:true}});
    const steps = [{action:'state.ensureRequestKey',args:{field:'shipmentRequestKey'}},{action:'api.request',endpoint:'/api/example/ship',method:'post',body:{requestKey:'${state.shipmentRequestKey}',quantity:'${form.quantity}'}}];
    await expect(flow.run(steps,{...manager.getContext('shipment'),fetchResult} as any)).rejects.toThrow();
    const first = manager.getContext('shipment').state.shipmentRequestKey;
    expect(first).toMatch(/^[a-f0-9-]{36}$/);
    await actionRegistry.execute('refresh',{loadData:vi.fn().mockResolvedValue(undefined)});
    await flow.run(steps,{...manager.getContext('shipment'),fetchResult} as any);
    expect(fetchResult).toHaveBeenCalledTimes(2);
    expect(fetchResult.mock.calls.map(call=>call[1].params)).toEqual([{requestKey:first,quantity:'250.0000'},{requestKey:first,quantity:'250.0000'}]);
    await actionRegistry.execute('state.set',{stateManager:manager,scopeId:'shipment',args:{shipmentRequestKey:null}});
    await flow.run(steps,{...manager.getContext('shipment'),fetchResult} as any);
    expect(manager.getContext('shipment').state.shipmentRequestKey).not.toBe(first);
  });
  it('rejects invalid existing keys and missing scopes without replacing them or calling transport', async () => {
    for (const value of ['',false,1,' bad ','a'.repeat(129),'bad\nkey']) {
      const manager = new ScopedStateManager({} as any);manager.createScope('s',{state:{requestKey:value}});
      await expect(actionRegistry.execute('state.ensureRequestKey',{stateManager:manager,scopeId:'s',args:{field:'requestKey'}})).rejects.toThrow();
      expect(manager.getContext('s').state.requestKey).toBe(value);
    }
    const manager = new ScopedStateManager({} as any);
    await expect(actionRegistry.execute('state.ensureRequestKey',{stateManager:manager,scopeId:'missing',args:{field:'requestKey'}})).rejects.toThrow();
    manager.createScope('s');
    for (const args of [{field:'__proto__'}, {field:'state.key'}, {field:'requestKey',value:'forced'}, {}])
      await expect(actionRegistry.execute('state.ensureRequestKey',{stateManager:manager,scopeId:'s',args})).rejects.toThrow();
    for (const action of ['state.ensureRequestKey','state.peekRequestKey','state.clearRequestKey']) {
      for (const target of ['__proto__','constructor','prototype','state.key','',null]) {
        await expect(actionRegistry.execute(action,{stateManager:manager,scopeId:'s',args:{field:'requestKey',persistence:'session'},stepTarget:target as any})).rejects.toThrow();
        expect(manager.getContext('s').state.requestKey).toBeUndefined();
      }
    }
    await expect(actionRegistry.execute('state.ensureRequestKey',{
      stateManager:manager,scopeId:'s',args:{field:'${record.field}'},expressionEvaluator:new ExpressionEvaluator(),
      expressionContext:{record:{field:'__proto__'}} as any,
    })).rejects.toThrow();
  });
});
