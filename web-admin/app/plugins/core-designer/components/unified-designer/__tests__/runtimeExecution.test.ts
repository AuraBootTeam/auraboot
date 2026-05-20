import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { DslBlockV3 } from '../types';
import { defaultRuntimeExecutionServices } from '../runtime/runtimeExecution';
import { commandActionService } from '~/plugins/core-designer/components/studio/services/command/CommandActionService';
import { startProcessFromAction } from '~/plugins/core-bpm/services/bpmWorkbenchService';
import { queryBuilderService } from '~/plugins/core-designer/components/query-builder/services/queryBuilderService';
import { namedQueryService } from '~/shared/services/namedQueryService';

vi.mock('~/plugins/core-designer/components/studio/services/command/CommandActionService', () => ({
  commandActionService: {
    execute: vi.fn(),
  },
}));

vi.mock('~/plugins/core-bpm/services/bpmWorkbenchService', () => ({
  startProcessFromAction: vi.fn(),
}));

vi.mock('~/plugins/core-designer/components/query-builder/services/queryBuilderService', () => ({
  queryBuilderService: {
    execute: vi.fn(),
  },
}));

vi.mock('~/shared/services/namedQueryService', () => ({
  namedQueryService: {
    execute: vi.fn(),
  },
}));

describe('defaultRuntimeExecutionServices', () => {
  beforeEach(() => {
    vi.mocked(commandActionService.execute).mockReset();
    vi.mocked(startProcessFromAction).mockReset();
    vi.mocked(queryBuilderService.execute).mockReset();
    vi.mocked(namedQueryService.execute).mockReset();
  });

  it('loads picker options from query-builder model data sources', async () => {
    vi.mocked(queryBuilderService.execute).mockResolvedValue({
      code: '0',
      desc: 'OK',
      data: [
        { page_key: 'system_overview', name: 'System overview', status: 'active' },
        { page_key: 'customer_list', name: 'Customer list', status: 'draft' },
      ],
    });
    const block: DslBlockV3 = {
      id: 'field_page',
      blockType: 'field',
      props: {
        component: 'picker',
        pickerDataSource: 'model',
        pickerSource: 'page_schema',
        valueField: 'page_key',
        displayField: 'name',
        pageSize: 50,
      },
    };

    const options = await defaultRuntimeExecutionServices.loadPickerOptions?.(block);

    expect(queryBuilderService.execute).toHaveBeenCalledWith({
      modelCode: 'page_schema',
      fields: ['page_key', 'name'],
      limit: 50,
    });
    expect(options).toEqual([
      {
        label: 'System overview',
        value: 'system_overview',
        record: { page_key: 'system_overview', name: 'System overview', status: 'active' },
      },
      {
        label: 'Customer list',
        value: 'customer_list',
        record: { page_key: 'customer_list', name: 'Customer list', status: 'draft' },
      },
    ]);
  });

  it('passes picker search keywords to query-builder model data sources', async () => {
    vi.mocked(queryBuilderService.execute).mockResolvedValue({
      code: '0',
      desc: 'OK',
      data: [{ page_key: 'system_overview', name: 'System overview' }],
    });
    const block: DslBlockV3 = {
      id: 'field_page',
      blockType: 'field',
      props: {
        component: 'picker',
        pickerDataSource: 'model',
        pickerSource: 'page_schema',
        valueField: 'page_key',
        displayField: 'name',
        pageSize: 50,
      },
    };

    await defaultRuntimeExecutionServices.loadPickerOptions?.(block, {
      source: 'unified-designer-runtime-preview',
      pageId: 'page_schema_form',
      pageKind: 'form',
      schemaVersion: 3,
      blockId: 'field_page',
      blockType: 'field',
      blockPath: ['form_root', 'section_basic', 'field_page'],
      pickerSearch: 'system',
    });

    expect(queryBuilderService.execute).toHaveBeenCalledWith({
      modelCode: 'page_schema',
      fields: ['page_key', 'name'],
      filters: [{ fieldName: 'name', operator: 'LIKE', value: 'system' }],
      limit: 50,
    });
  });

  it('loads picker options from named-query data sources with parameters', async () => {
    vi.mocked(namedQueryService.execute).mockResolvedValue({
      records: [
        { page_key: 'dashboard_home', title: 'Dashboard home' },
        { page_key: 'ops_console', title: 'Ops console' },
      ],
      total: 2,
      size: 25,
      current: 1,
      pages: 1,
    });
    const block: DslBlockV3 = {
      id: 'field_page',
      blockType: 'field',
      props: {
        component: 'picker',
        pickerDataSource: 'named-query',
        pickerQueryCode: 'udw_page_options',
        valueField: 'page_key',
        displayField: 'title',
        pageSize: 25,
        pickerParameters: { status: 'published' },
      },
    };

    const options = await defaultRuntimeExecutionServices.loadPickerOptions?.(block);

    expect(namedQueryService.execute).toHaveBeenCalledWith('udw_page_options', {
      page: 1,
      size: 25,
      executeQuery: true,
      parameters: { status: 'published' },
    });
    expect(options).toEqual([
      {
        label: 'Dashboard home',
        value: 'dashboard_home',
        record: { page_key: 'dashboard_home', title: 'Dashboard home' },
      },
      {
        label: 'Ops console',
        value: 'ops_console',
        record: { page_key: 'ops_console', title: 'Ops console' },
      },
    ]);
  });

  it('passes picker search keywords to named-query parameters', async () => {
    vi.mocked(namedQueryService.execute).mockResolvedValue({
      records: [{ page_key: 'dashboard_home', title: 'Dashboard home' }],
      total: 1,
      size: 25,
      current: 1,
      pages: 1,
    });
    const block: DslBlockV3 = {
      id: 'field_page',
      blockType: 'field',
      props: {
        component: 'picker',
        pickerDataSource: 'named-query',
        pickerQueryCode: 'udw_page_options',
        valueField: 'page_key',
        displayField: 'title',
        pageSize: 25,
        pickerParameters: { status: 'published' },
      },
    };

    await defaultRuntimeExecutionServices.loadPickerOptions?.(block, {
      source: 'unified-designer-runtime-preview',
      pageId: 'page_schema_form',
      pageKind: 'form',
      schemaVersion: 3,
      blockId: 'field_page',
      blockType: 'field',
      blockPath: ['form_root', 'section_basic', 'field_page'],
      pickerSearch: 'dashboard',
    });

    expect(namedQueryService.execute).toHaveBeenCalledWith('udw_page_options', {
      page: 1,
      size: 25,
      executeQuery: true,
      parameters: { status: 'published', keyword: 'dashboard' },
      whereConditions: [{ field: 'title', operator: 'contains', value: 'dashboard' }],
    });
  });

  it('loads AI helper suggestions from named-query data sources', async () => {
    vi.mocked(namedQueryService.execute).mockResolvedValue({
      records: [
        {
          fieldcode: 'summary',
          fieldlabel: 'Summary',
          suggestedvalue: 'Generated summary',
          feedback: 'Applied from live data',
        },
      ],
      total: 1,
      size: 10,
      current: 1,
      pages: 1,
    });
    const block: DslBlockV3 = {
      id: 'ai_helper',
      blockType: 'ai-fill-banner',
      dataSource: {
        type: 'namedQuery',
        executionMode: 'live',
        queryCode: 'udw_ai_suggestions',
        parameters: { recordId: 'customer-1' },
        page: 1,
        size: 10,
      },
    };

    const data = await defaultRuntimeExecutionServices.loadHelperBlockData?.(block);

    expect(namedQueryService.execute).toHaveBeenCalledWith('udw_ai_suggestions', {
      page: 1,
      size: 10,
      executeQuery: true,
      parameters: { recordId: 'customer-1' },
    });
    expect(data).toEqual({
      source: 'named-query',
      suggestedFields: [
        { field: 'summary', label: 'Summary', value: 'Generated summary' },
      ],
      feedback: 'Applied from live data',
      emptyText: undefined,
      description: undefined,
    });
  });

  it('loads workflow helper panels from query-builder data sources', async () => {
    vi.mocked(queryBuilderService.execute).mockResolvedValue({
      code: '0',
      desc: 'OK',
      data: [
        {
          status: 'pending',
          assignee: 'Ada',
          dueat: '2026-05-21',
          actionlabel: 'Approve',
          actiontype: 'approve',
        },
      ],
    });
    const block: DslBlockV3 = {
      id: 'bpm_helper',
      blockType: 'bpm-panel',
      dataSource: {
        type: 'query-builder',
        executionMode: 'live',
        query: {
          modelCode: 'workflow_task',
          fields: ['status', 'assignee', 'due_at'],
          limit: 1,
        },
      },
    };

    const data = await defaultRuntimeExecutionServices.loadHelperBlockData?.(block);

    expect(queryBuilderService.execute).toHaveBeenCalledWith({
      modelCode: 'workflow_task',
      fields: ['status', 'assignee', 'due_at'],
      limit: 1,
    });
    expect(data).toEqual({
      source: 'query-builder',
      status: 'pending',
      description: '',
      assignee: 'Ada',
      dueAt: '2026-05-21',
      actions: [{ label: 'Approve', actionType: 'approve' }],
    });
  });

  it('maps timeline and field history helper rows from data sources', async () => {
    vi.mocked(queryBuilderService.execute).mockResolvedValueOnce({
      code: '0',
      desc: 'OK',
      data: [
        {
          actor: 'Grace',
          event: 'Updated record',
          createdat: '2026-05-20 10:00',
          message: 'Changed amount',
        },
      ],
    });
    vi.mocked(queryBuilderService.execute).mockResolvedValueOnce({
      code: '0',
      desc: 'OK',
      data: [
        {
          fieldcode: 'status',
          oldvalue: 'draft',
          newvalue: 'approved',
          changedby: 'Lin',
        },
      ],
    });

    const timeline = await defaultRuntimeExecutionServices.loadHelperBlockData?.({
      id: 'activity_helper',
      blockType: 'activity-timeline',
      dataSource: {
        type: 'query-builder',
        executionMode: 'live',
        query: { modelCode: 'activity_log', fields: ['actor', 'event'] },
      },
    });
    const history = await defaultRuntimeExecutionServices.loadHelperBlockData?.({
      id: 'history_helper',
      blockType: 'field-history',
      dataSource: {
        type: 'query-builder',
        executionMode: 'live',
        query: { modelCode: 'field_audit', fields: ['fieldCode', 'old_value', 'new_value'] },
      },
    });

    expect(timeline?.items).toEqual([
      {
        actor: 'Grace',
        action: 'Updated record',
        time: '2026-05-20 10:00',
        description: 'Changed amount',
      },
    ]);
    expect(history?.entries).toEqual([
      { field: 'status', from: 'draft', to: 'approved', changedBy: 'Lin' },
    ]);
  });

  it('strips reserved audit context from live command business payload', async () => {
    vi.mocked(commandActionService.execute).mockResolvedValue({
      commandCode: 'page_schema:export',
      data: { ok: true },
    });
    const block: DslBlockV3 = {
      id: 'action_export',
      blockType: 'action',
      actionType: 'command',
      props: {
        command: 'page_schema:export',
        executionMode: 'live',
        payload: {
          pageKey: 'page_schema_list',
          __auditContext: { source: 'spoofed-client' },
        },
      },
    };

    await defaultRuntimeExecutionServices.executeAction?.(block, {
      source: 'unified-designer-runtime-preview',
      pageId: 'page_schema_list',
      pageKind: 'list',
      schemaVersion: 3,
      blockId: 'action_export',
      blockType: 'action',
      blockPath: ['list_root', 'toolbar', 'action_export'],
      actionType: 'command',
      permissionCode: 'meta.page-schema.export',
    });

    expect(commandActionService.execute).toHaveBeenCalledWith(
      'page_schema:export',
      { pageKey: 'page_schema_list' },
      expect.objectContaining({
        auditContext: expect.objectContaining({
          source: 'unified-designer-runtime-preview',
          pageId: 'page_schema_list',
          blockId: 'action_export',
          permissionCode: 'meta.page-schema.export',
        }),
      }),
    );
  });

  it('resolves live command payload templates from page and block runtime context', async () => {
    vi.mocked(commandActionService.execute).mockResolvedValue({
      commandCode: 'page_schema:export',
      data: { ok: true },
    });
    const block: DslBlockV3 = {
      id: 'action_export',
      blockType: 'action',
      actionType: 'command',
      props: {
        command: 'page_schema:export',
        executionMode: 'live',
        payload: {
          pageId: '{{page.id}}',
          pageKind: '{{page.kind}}',
          schemaVersion: '{{schema.version}}',
          actionSummary: '{{page.kind}}/{{action.type}}/{{block.id}}',
          nested: {
            blockType: '{{block.type}}',
            blockPath: '{{block.path}}',
            unknown: '{{record.id}}',
            __auditContext: { source: 'spoofed-nested-client' },
          },
          entries: ['{{block.id}}', '{{schema.version}}'],
          routePageId: '{{route.query.pageId}}',
          routeTags: '{{route.query.tag}}',
          routeSummary: '{{route.query.mode}}/{{route.query.pageId}}',
          unknownRouteQuery: '{{route.query.missing}}',
          formName: '{{form.values.name}}',
          formSummary: '{{form.values.name}}/{{form.values.status}}',
          unknownFormValue: '{{form.values.missing}}',
          selectedRows: '{{selected.rows}}',
          selectedRowIds: '{{selected.rowIds}}',
          selectedCount: '{{selected.count}}',
          currentRow: '{{current.row}}',
          currentRowId: '{{current.rowId}}',
          currentRowName: '{{current.row.name}}',
          currentSummary: '{{current.row.name}}/{{current.row.status}}',
          unknownCurrentValue: '{{current.row.missing}}',
        },
      },
    };

    await defaultRuntimeExecutionServices.executeAction?.(block, {
      source: 'unified-designer-runtime-preview',
      pageId: 'page_schema_list',
      pageKind: 'list',
      schemaVersion: 3,
      blockId: 'action_export',
      blockType: 'action',
      blockPath: ['list_root', 'toolbar', 'action_export'],
      actionType: 'command',
      routeQuery: {
        pageId: '01KRWJKF5JFN2ZG0DD5XWHFB79',
        mode: 'preview',
        tag: ['alpha', 'beta'],
      },
      formValues: {
        name: 'Ada Lovelace',
        status: 'draft',
      },
      selectedRows: [{ id: 'row_001', name: 'Selected row' }],
      selectedRowIds: ['row_001'],
      currentRow: { id: 'row_002', name: 'Current row', status: 'active' },
      currentRowId: 'row_002',
    });

    expect(commandActionService.execute).toHaveBeenCalledWith(
      'page_schema:export',
      {
        pageId: 'page_schema_list',
        pageKind: 'list',
        schemaVersion: 3,
        actionSummary: 'list/command/action_export',
        nested: {
          blockType: 'action',
          blockPath: ['list_root', 'toolbar', 'action_export'],
          unknown: '{{record.id}}',
        },
        entries: ['action_export', 3],
        routePageId: '01KRWJKF5JFN2ZG0DD5XWHFB79',
        routeTags: ['alpha', 'beta'],
        routeSummary: 'preview/01KRWJKF5JFN2ZG0DD5XWHFB79',
        unknownRouteQuery: '{{route.query.missing}}',
        formName: 'Ada Lovelace',
        formSummary: 'Ada Lovelace/draft',
        unknownFormValue: '{{form.values.missing}}',
        selectedRows: [{ id: 'row_001', name: 'Selected row' }],
        selectedRowIds: ['row_001'],
        selectedCount: 1,
        currentRow: { id: 'row_002', name: 'Current row', status: 'active' },
        currentRowId: 'row_002',
        currentRowName: 'Current row',
        currentSummary: 'Current row/active',
        unknownCurrentValue: '{{current.row.missing}}',
      },
      expect.any(Object),
    );
  });

  it('resolves live workflow business keys and variables from runtime context', async () => {
    vi.mocked(startProcessFromAction).mockResolvedValue({
      processInstanceId: 'proc_001',
    });
    const block: DslBlockV3 = {
      id: 'action_review',
      blockType: 'action',
      actionType: 'workflow',
      props: {
        workflowKey: 'flow.review',
        executionMode: 'live',
        businessKey: 'wf-{{page.id}}-{{block.id}}-{{route.query.pageId}}',
        variables: {
          pageId: '{{page.id}}',
          routeMode: '{{route.query.mode}}',
          formName: '{{form.values.name}}',
          schemaVersion: '{{schema.version}}',
          nested: {
            blockPath: '{{block.path}}',
            unknown: '{{record.id}}',
            __auditContext: { source: 'spoofed-nested-client' },
          },
          entries: ['{{action.type}}', '{{block.type}}'],
          __auditContext: { source: 'spoofed-client' },
        },
      },
    };

    await defaultRuntimeExecutionServices.executeAction?.(block, {
      source: 'unified-designer-runtime-preview',
      pageId: 'page_schema_list',
      pageKind: 'list',
      schemaVersion: 3,
      blockId: 'action_review',
      blockType: 'action',
      blockPath: ['list_root', 'toolbar', 'action_review'],
      actionType: 'workflow',
      routeQuery: {
        pageId: '01KRWJKF5JFN2ZG0DD5XWHFB79',
        mode: 'runtime',
      },
      formValues: {
        name: 'Grace Hopper',
      },
    });

    expect(startProcessFromAction).toHaveBeenCalledWith({
      processDefinitionKey: 'flow.review',
      businessKey: 'wf-page_schema_list-action_review-01KRWJKF5JFN2ZG0DD5XWHFB79',
      variables: {
        pageId: 'page_schema_list',
        routeMode: 'runtime',
        formName: 'Grace Hopper',
        schemaVersion: 3,
        nested: {
          blockPath: ['list_root', 'toolbar', 'action_review'],
          unknown: '{{record.id}}',
        },
        entries: ['workflow', 'action'],
      },
    });
  });
});
