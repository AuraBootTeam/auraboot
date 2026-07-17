import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { validateStructure } from '../../validation/DslValidator';
import { canonicalizePageSchemaDto, type PageSchemaDTO } from '../canonicalizePageDsl';

function collectPluginPageFiles(dir: string): string[] {
  if (!existsSync(dir)) return [];

  return readdirSync(dir).flatMap((entry) => {
    if (entry === 'node_modules' || entry === '.git') return [];

    const path = resolve(dir, entry);
    if (!existsSync(path)) return [];

    const stat = statSync(path);
    if (stat.isDirectory()) {
      return collectPluginPageFiles(path);
    }
    return path.includes('/config/pages') && path.endsWith('.json') ? [path] : [];
  });
}

function readPages(file: string): PageSchemaDTO[] {
  const data = JSON.parse(readFileSync(file, 'utf8'));
  if (Array.isArray(data)) return data;
  if (Array.isArray(data.pages)) return data.pages;
  return [data];
}

describe('canonicalizePageSchemaDto', () => {
  it('builds a structurally valid canonical schema from a backend PageSchemaDTO', () => {
    const schema = canonicalizePageSchemaDto({
      pid: 'page-001',
      pageKey: 'meta_models_admin',
      modelCode: 'meta_models',
      modelCategory: null,
      name: 'Model Management',
      title: {
        'zh-CN': '模型',
        'en-US': 'Models',
      },
      description: '',
      kind: 'list',
      profile: 'admin',
      schemaVersion: 4,
      metaInfo: {},
      isTemplate: false,
      layout: {
        type: 'stack',
      },
      blocks: [
        {
          id: 'model_table',
          blockType: 'table',
          table: {
            rowKey: 'pid',
            dataSource: 'modelList',
            columns: [
              {
                field: 'code',
                valueType: 'meta_model_code',
              },
            ],
            rowActions: [
              {
                code: 'detail',
                navigateTo: '/p/meta_models/view/{code}',
              },
            ],
          },
        },
      ],
      extension: {
        dataSource: {
          type: 'api',
          endpoint: '/api/meta/models',
          method: 'get',
        },
        options: {
          enableCreate: true,
        },
      },
    });

    expect(schema.id).toBe('page-001');
    expect(schema.version).toBe('1.0.0');
    expect(schema.pageKey).toBe('meta_models_admin');
    expect((schema.blocks[0] as any).table.columns[0]).toMatchObject({
      field: 'code',
      cellRenderer: 'meta_model_code',
    });
    expect((schema.blocks[0] as any).table.columns[0].valueType).toBeUndefined();
    expect((schema.blocks[0] as any).rowActions[0]).toMatchObject({
      action: {
        type: 'navigate',
        to: '/p/meta_models/view/{code}',
      },
    });
    expect((schema.blocks[0] as any).table.rowActions[0]).toMatchObject({
      action: {
        type: 'navigate',
        to: '/p/meta_models/view/{code}',
      },
    });
    expect(schema.dataSource).toEqual({
      type: 'api',
      endpoint: '/api/meta/models',
      method: 'get',
    });
    expect(schema.options).toEqual({ enableCreate: true });
    expect(validateStructure(schema)).toEqual([]);
  });

  it('normalizes legacy button shortcuts, structured visibility, and inline block data sources', () => {
    const schema = canonicalizePageSchemaDto({
      pageKey: 'legacy_actions_list',
      modelCode: 'legacy_model',
      modelCategory: null,
      kind: 'list',
      layout: { type: 'stack' },
      blocks: [
        {
          id: 'toolbar',
          blockType: 'toolbar',
          buttons: [
            {
              code: 'edit',
              commandCode: 'legacy:update',
              navigateTo: 'legacy_form',
              confirmMessageKey: 'legacy.confirm',
              visibleWhen: { field: 'status', operator: 'EQ', value: 'draft' },
            },
          ],
        },
        {
          id: 'summary',
          blockType: 'stat-card',
          dataSource: {
            kind: 'namedQuery',
            queryCode: 'legacy_summary',
            url: '/api/datasource/list',
          },
        },
      ],
    });

    const button = (schema.blocks[0] as any).buttons[0];
    expect(button).toMatchObject({
      action: {
        type: 'navigate',
        to: 'legacy_form',
        command: 'legacy:update',
      },
      confirm: 'legacy.confirm',
      visibleWhen: '(row?.["status"] ?? record?.["status"] ?? form?.["status"]) === "draft"',
    });
    expect(button.commandCode).toBeUndefined();
    expect(button.navigateTo).toBeUndefined();
    expect(button.confirmMessageKey).toBeUndefined();

    expect((schema.blocks[1] as any).dataSource).toBe('summary_dataSource');
    expect(schema.dataSources?.summary_dataSource).toMatchObject({
      id: 'summary_dataSource',
      type: 'namedQuery',
      queryCode: 'legacy_summary',
      endpoint: '/api/datasource/list',
    });
    expect(validateStructure(schema)).toEqual([]);
  });

  it('folds commandCode into a command action for a legacy form-persist verb (save)', () => {
    // Regression: a form-buttons submit button uses the legacy { action: "save",
    // commandCode } shape (every built-in plugin form does, e.g. asset-management).
    // "save" is not an ActionRegistry action, so if commandCode is dropped without
    // being folded into the action the submit falls through to an unregistered
    // builtin and the form cannot persist. The verb must fold the commandCode in.
    const schema = canonicalizePageSchemaDto({
      pageKey: 'persist_form',
      modelCode: 'persist_model',
      modelCategory: null,
      kind: 'form',
      layout: { type: 'stack' },
      blocks: [
        {
          id: 'buttons',
          blockType: 'form-buttons',
          buttons: [
            { code: 'submit', action: 'save', commandCode: 'persist:create_x', primary: true },
            { code: 'cancel', action: 'cancel' },
          ],
        },
      ],
    });

    const [submit, cancel] = (schema.blocks[0] as any).buttons;
    expect(submit.action).toEqual({ type: 'command', command: 'persist:create_x' });
    expect(submit.commandCode).toBeUndefined();
    // a non-persist verb (cancel) is a registry action and is left as the string verb
    expect(cancel.action).toBe('cancel');
  });

  it('normalizes refresh toolbar preset shorthand into an executable builtin button', () => {
    const schema = canonicalizePageSchemaDto({
      pageKey: 'refresh_preset_list',
      modelCode: 'page_schema',
      modelCategory: null,
      kind: 'list',
      layout: { type: 'stack' },
      blocks: [
        {
          id: 'toolbar',
          blockType: 'toolbar',
          buttons: [{ preset: 'refresh' }],
        },
      ],
    });

    const button = (schema.blocks[0] as any).buttons[0];
    expect(button).toMatchObject({
      preset: 'refresh',
      code: 'refresh',
      label: { 'zh-CN': '刷新', 'en-US': 'Refresh' },
    });
    expect(validateStructure(schema)).toEqual([]);
  });

  it('preserves top-level page dataSources from DSL v4 page DTOs', () => {
    const schema = canonicalizePageSchemaDto({
      pageKey: 'workbench_detail',
      modelCode: 'workbench_task',
      modelCategory: null,
      kind: 'detail',
      layout: { type: 'stack' },
      dataSources: {
        taskSummary: {
          type: 'api',
          endpoint: '/api/dynamic/workbench_task/list',
          method: 'GET' as any,
          adaptor: 'table',
          autoFetch: true,
          params: {
            pid: '${form.pid}',
          },
        },
      },
      blocks: [
        {
          id: 'metrics',
          blockType: 'metric-strip',
          dataSource: 'taskSummary',
          metrics: [
            {
              id: 'green',
              label: 'Green',
              valueField: 'green_count',
            },
          ],
        },
      ],
    });

    expect(schema.dataSources?.taskSummary).toMatchObject({
      type: 'api',
      endpoint: '/api/dynamic/workbench_task/list',
      method: 'get',
      adaptor: 'table',
      autoFetch: true,
      params: {
        pid: '${form.pid}',
      },
    });
    expect(validateStructure(schema)).toEqual([]);
  });

  it('normalizes nested tab blocks, sub-table columns, row actions, and custom cell renderers', () => {
    const schema = canonicalizePageSchemaDto({
      pageKey: 'detail_nested',
      modelCode: 'detail_model',
      modelCategory: null,
      kind: 'detail',
      layout: { type: 'stack' },
      blocks: [
        {
          id: 'tabs',
          blockType: 'tabs',
          tabs: [
            {
              key: 'history',
              label: 'History',
              blocks: [
                {
                  id: 'history_table',
                  blockType: 'sub-table',
                  subTable: {
                    childModel: 'history_item',
                    parentField: 'parent_id',
                    columns: [
                      {
                        field: 'actor',
                        valueType: 'user_avatar_name',
                      },
                    ],
                    actions: [
                      {
                        code: 'open',
                        navigateTo: 'history_detail',
                      },
                    ],
                  },
                },
              ],
            },
          ],
        },
      ],
    });

    const subTable = (schema.blocks[0] as any).tabs[0].blocks[0].subTable;
    expect(subTable.columns[0]).toMatchObject({
      field: 'actor',
      cellRenderer: 'user_avatar_name',
    });
    expect(subTable.columns[0].valueType).toBeUndefined();
    expect(subTable.actions[0]).toMatchObject({
      action: {
        type: 'navigate',
        to: 'history_detail',
      },
    });
    expect(validateStructure(schema)).toEqual([]);
  });

  it('adapts recursive V3 form blocks for the current dynamic form runtime', () => {
    const schema = canonicalizePageSchemaDto({
      pageKey: 'recursive_form',
      modelCode: 'recursive_model',
      modelCategory: null,
      kind: 'form',
      schemaVersion: 2,
      layout: { type: 'grid', cols: 12 },
      blocks: [
        {
          id: 'form_recursive_form',
          blockType: 'form',
          layout: { type: 'grid', cols: 12 },
          blocks: [
            {
              id: 'basic',
              blockType: 'form-section',
              title: 'Basic',
              blocks: [
                {
                  id: 'basic_name',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                },
              ],
            },
            {
              id: 'buttons',
              blockType: 'action-bar',
              region: 'footer',
              blocks: [
                {
                  id: 'buttons_submit',
                  blockType: 'action',
                  actionType: 'command',
                  props: {
                    code: 'submit',
                    label: 'save',
                    primary: true,
                    action: {
                      type: 'command',
                      command: 'recursive:update',
                    },
                  },
                },
              ],
            },
          ],
        },
      ],
    });

    expect(schema.blocks).toHaveLength(2);
    expect(schema.blocks[0]).toMatchObject({
      id: 'basic',
      blockType: 'form-section',
      fields: [{ field: 'name', colSpan: 6 }],
    });
    expect(schema.blocks[1]).toMatchObject({
      id: 'buttons',
      blockType: 'form-buttons',
      buttons: [
        {
          code: 'submit',
          primary: true,
          action: {
            type: 'command',
            command: 'recursive:update',
          },
        },
      ],
    });
    expect(validateStructure(schema)).toEqual([]);
  });

  it('canonicalizes checked-in plugin page configs before structure validation', () => {
    const root = resolve(process.cwd(), '..');
    const pageFiles = collectPluginPageFiles(resolve(root, 'plugins'));
    const failures = pageFiles.flatMap((file) =>
      readPages(file).flatMap((page) => {
        const schema = canonicalizePageSchemaDto(page);
        return validateStructure(schema).map((message) => ({
          file: file.replace(`${root}/`, ''),
          pageKey: page.pageKey,
          path: message.path,
          message: message.message,
        }));
      }),
    );

    expect(failures).toEqual([]);
  });

  it('keeps DecisionOps connector row actions as governance and platform-management links', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/core-decisionops/config/pages.json');
    const page = readPages(pagesFile).find(
      (candidate) => candidate.pageKey === 'decisionops_connectors_list',
    );

    expect(page).toBeDefined();

    const schema = canonicalizePageSchemaDto(page!);
    const rowActions = (schema.blocks[0] as any).table.rowActions;
    const detailAction = rowActions.find((action: any) => action.code === 'detail');
    const manageAction = rowActions.find((action: any) => action.code === 'manage');

    expect(rowActions.map((action: any) => action.code)).not.toEqual(
      expect.arrayContaining(['test', 'delete', 'console']),
    );
    expect(JSON.stringify(page)).not.toContain('/decision-ops');
    expect(detailAction).toMatchObject({
      action: {
        type: 'navigate',
        to: '/p/decisionops_connectors/view/{pid}',
      },
    });
    expect(manageAction).toMatchObject({
      action: {
        type: 'navigate',
        to: '/p/api_connector',
      },
    });
  });

  it('keeps DecisionOps data-model row actions scoped to field impact', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/core-decisionops/config/pages.json');
    const page = readPages(pagesFile).find(
      (candidate) => candidate.pageKey === 'decisionops_model_fields_list',
    );

    expect(page).toBeDefined();

    const schema = canonicalizePageSchemaDto(page!);
    const rowActions = (schema.blocks[0] as any).table.rowActions;

    expect(rowActions.map((action: any) => action.code)).toEqual(['impact']);
    expect(rowActions[0]).toMatchObject({
      action: {
        type: 'navigate',
        to: '/p/decisionops_model_fields_impact?fieldRef={entityCode}.{path}&currentDataType={dataType}',
      },
    });
    expect(JSON.stringify(page)).not.toContain('/decision-ops');
  });

  it('keeps DecisionOps webhook row actions as governance and platform-management links', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/core-decisionops/config/pages.json');
    const pages = readPages(pagesFile);
    const listPage = pages.find((candidate) => candidate.pageKey === 'decisionops_webhooks_list');
    const detailPage = pages.find(
      (candidate) => candidate.pageKey === 'decisionops_webhooks_detail',
    );

    expect(listPage).toBeDefined();
    expect(detailPage).toBeDefined();

    const schema = canonicalizePageSchemaDto(listPage!);
    const rowActions = (schema.blocks[0] as any).table.rowActions;
    const detailAction = rowActions.find((action: any) => action.code === 'detail');
    const manageAction = rowActions.find((action: any) => action.code === 'manage');

    expect(rowActions.map((action: any) => action.code)).not.toEqual(
      expect.arrayContaining(['create', 'edit', 'delete']),
    );
    expect(detailAction).toMatchObject({
      action: {
        type: 'navigate',
        to: '/p/decisionops_webhooks/view/{pid}',
      },
    });
    expect(manageAction).toMatchObject({
      action: {
        type: 'navigate',
        to: '/p/webhook',
      },
    });
    expect(detailPage!.blocks?.[0] as any).toMatchObject({
      component: 'DecisionIntegrationImpactBlock',
      props: {
        targetType: 'WEBHOOK',
        targetCodeField: 'event_type',
      },
    });
  });

  it('hosts DecisionDefinition impact and lifecycle actions in a DSL custom block', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/core-decisionops/config/pages.json');
    const pages = readPages(pagesFile);
    const listPage = pages.find(
      (candidate) => candidate.pageKey === 'decisionops_definitions_list',
    );
    const detailPage = pages.find(
      (candidate) => candidate.pageKey === 'decisionops_definitions_detail',
    );

    expect(listPage).toBeDefined();
    expect(detailPage).toBeDefined();

    const listSchema = canonicalizePageSchemaDto(listPage!);
    const tableBlock = listSchema.blocks.find((block: any) => block.blockType === 'table') as any;
    const rowActions = tableBlock.table.rowActions;

    expect(rowActions.map((action: any) => action.code)).toEqual(['detail', 'rollout']);
    expect(rowActions.find((action: any) => action.code === 'rollout')).toMatchObject({
      action: {
        type: 'navigate',
        to: '/p/decisionops_rollouts?decisionCode={decisionCode}',
      },
    });
    expect(rowActions.map((action: any) => action.action?.to)).not.toEqual(
      expect.arrayContaining(['/decision-ops']),
    );
    expect(detailPage!.blocks?.[0] as any).toMatchObject({
      component: 'DecisionDefinitionActionsBlock',
      props: {
        mode: 'detail',
        rolloutUrl:
          '/p/decisionops_rollouts?decisionCode={decisionCode}&baselineVersion={baselineVersion}&candidateVersion={candidateVersion}',
      },
    });
  });

  it('hosts SLA rule-center binding in the platform-admin DSL form and detail pages', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/platform-admin/config/pages.json');
    const pages = readPages(pagesFile);
    const formPage = pages.find((candidate) => candidate.pageKey === 'sla_config_form');
    const detailPage = pages.find((candidate) => candidate.pageKey === 'sla_config_detail');

    expect(formPage).toBeDefined();
    expect(detailPage).toBeDefined();

    const schema = canonicalizePageSchemaDto(formPage!);
    expect(validateStructure(schema)).toEqual([]);
    const ruleBlock = schema.blocks.find((block: any) => block.id === 'sla_rule_binding') as any;

    expect(ruleBlock).toMatchObject({
      blockType: 'custom',
      component: 'DecisionRuleBindingBlock',
      props: {
        mode: 'decision',
        valueField: 'rule_binding',
        consumerType: 'SLA',
        initialDecisionCode: 'complaint_sla_deadline',
        fieldCatalogMode: 'merge',
      },
    });
    expect(schema.blocks.find((block: any) => block.id === 'sla_action_policy')).toMatchObject({
      blockType: 'custom',
      component: 'DecisionActionPlanBlock',
      props: {
        valueField: 'action_policy',
        title: '超时后动作',
        triggerLabel: 'SLA 超时',
        fieldCatalogModelCodeField: 'model_code',
      },
    });

    const detailSchema = canonicalizePageSchemaDto(detailPage!);
    expect(validateStructure(detailSchema)).toEqual([]);
    expect(detailSchema.kind).toBe('detail');
    expect(detailSchema.extension).toMatchObject({
      dataSource: {
        type: 'api',
        endpoint: '/api/bpm/sla-configs/{pid}',
        method: 'get',
      },
    });
    expect(detailSchema.extension).toMatchObject({
      showShare: false,
      showReport: false,
      showPrint: false,
    });
    expect(detailSchema.blocks.find((block: any) => block.id === 'actions')).toMatchObject({
      blockType: 'toolbar',
      buttons: [
        expect.objectContaining({
          code: 'edit',
          primary: true,
          action: {
            type: 'navigate',
            to: 'sla_config_form',
            command: 'admin:update_sla_config',
          },
        }),
      ],
    });
    expect(detailSchema.blocks.find((block: any) => block.id === 'basic')).toMatchObject({
      blockType: 'form-section',
      readOnly: true,
    });
    expect(detailSchema.blocks.find((block: any) => block.id === 'timer_policy')).toMatchObject({
      blockType: 'form-section',
      readOnly: true,
    });
    expect(detailSchema.blocks.find((block: any) => block.id === 'sla_rule_binding')).toMatchObject(
      {
        blockType: 'custom',
        component: 'DecisionRuleBindingBlock',
        props: {
          mode: 'decision',
          valueField: 'rule_binding',
          consumerType: 'SLA',
          readOnly: true,
          variant: 'summary',
          showTestRunner: false,
        },
      },
    );
    expect(
      detailSchema.blocks.find((block: any) => block.id === 'sla_action_policy'),
    ).toMatchObject({
      blockType: 'custom',
      component: 'DecisionActionPlanBlock',
      props: {
        valueField: 'action_policy',
        readOnly: true,
        logsUrl: '/p/decisionops_execution_logs?callerType=SLA&callerRef={pid}',
      },
    });

    const fields = JSON.parse(
      readFileSync(resolve(root, 'plugins/platform-admin/config/fields.json'), 'utf8'),
    );
    expect(fields.find((field: any) => field.code === 'action_policy')).toMatchObject({
      code: 'action_policy',
      dataType: 'jsonb',
    });

    const commands = JSON.parse(
      readFileSync(resolve(root, 'plugins/platform-admin/config/commands.json'), 'utf8'),
    );
    expect(
      commands.find((command: any) => command.code === 'admin:create_sla_config')?.inputFields,
    ).toContain('action_policy');
    expect(
      commands.find((command: any) => command.code === 'admin:update_sla_config')?.inputFields,
    ).toContain('action_policy');
  });

  it('keeps the BPM process list aligned with the imported model field contract', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/platform-admin/config/pages.json');
    const pages = readPages(pagesFile);
    const listPage = pages.find((candidate) => candidate.pageKey === 'bpm_process_management_list');

    expect(listPage).toBeDefined();

    const schema = canonicalizePageSchemaDto(listPage!);
    expect(schema.dataSource).toMatchObject({
      type: 'api',
      endpoint: '/api/bpm/process-definitions',
      method: 'get',
    });
    const tableBlock = schema.blocks.find((block: any) => block.blockType === 'table') as any;
    const columns = tableBlock.table?.columns ?? tableBlock.columns;
    const fields = columns.map((column: any) => column.field);

    expect(fields).toEqual(expect.arrayContaining(['process_key', 'process_name', 'deployed_at']));
    expect(fields).not.toEqual(expect.arrayContaining(['processKey', 'processName', 'deployedAt']));
    expect(columns.find((column: any) => column.field === 'status')).toMatchObject({
      dictCode: 'bpm_process_status',
    });
    expect(tableBlock.detailUrl).toBe('/p/bpm_process_management/edit/{pid}');
    expect(tableBlock.rowActions).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'configure_rules',
          action: {
            type: 'navigate',
            to: '/p/bpm_process_management/edit/{pid}',
          },
        }),
        expect.objectContaining({
          code: 'open_bpmn_designer',
          action: {
            type: 'navigate',
            to: '/bpmn-designer?pid={pid}',
          },
        }),
      ]),
    );
  });

  it('uses the BPM process status dictionary on the process configuration form', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/platform-admin/config/pages.json');
    const pages = readPages(pagesFile);
    const formPage = pages.find((candidate) => candidate.pageKey === 'bpm_process_management_form');
    const schema = canonicalizePageSchemaDto(formPage!);
    const formSection = schema.blocks.find((block: any) => block.id === 'process_identity') as any;
    const statusField = formSection.fields.find((field: any) => field.field === 'status');

    expect(statusField).toMatchObject({
      dictCode: 'bpm_process_status',
      readonly: true,
    });
  });

  it('keeps the BPM process edit route from falling back to an auto-created stub page', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/platform-admin/config/pages.json');
    const pages = readPages(pagesFile);
    const formPage = pages.find((candidate) => candidate.pageKey === 'bpm_process_management_form');

    expect(formPage).toBeDefined();

    const schema = canonicalizePageSchemaDto(formPage!);
    const customBlock = schema.blocks.find((block: any) => block.blockType === 'custom') as any;
    const toolbarBlock = schema.blocks.find((block: any) => block.blockType === 'toolbar') as any;

    expect(customBlock).toMatchObject({
      component: 'DecisionRuleBindingBlock',
      props: {
        consumerType: 'BPM',
        initialDecisionCode: 'approval_routing',
        showImpactPreview: true,
        showTestRunner: true,
        fieldCatalogMode: 'fallback',
      },
    });
    expect(
      toolbarBlock.buttons.find((button: any) => button.code === 'open_bpmn_designer'),
    ).toMatchObject({
      action: {
        type: 'navigate',
        to: '/bpmn-designer?pid={pid}',
      },
    });
  });

  it('hosts the visual decision-table editor in a DSL custom workbench block', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/core-decisionops/config/pages.json');
    const pages = readPages(pagesFile);
    const tablePage = pages.find((candidate) => candidate.pageKey === 'decisionops_tables_list');

    expect(tablePage).toBeDefined();
    expect((tablePage!.extension as any).customOnly).toBe(true);
    expect(JSON.stringify(tablePage)).not.toContain('/decision-ops');

    const schema = canonicalizePageSchemaDto(tablePage!);
    expect(schema.blocks).toHaveLength(1);
    expect(schema.blocks[0]).toMatchObject({
      blockType: 'custom',
      component: 'DecisionTableWorkbenchBlock',
      props: {
        mode: 'workbench',
      },
    });
  });

  it('hosts EventPolicy actions and designer in DSL custom blocks instead of console row actions', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/core-decisionops/config/pages.json');
    const pages = readPages(pagesFile);
    const listPage = pages.find(
      (candidate) => candidate.pageKey === 'decisionops_event_policies_list',
    );
    const detailPage = pages.find(
      (candidate) => candidate.pageKey === 'decisionops_event_policies_detail',
    );
    const designerPage = pages.find(
      (candidate) => candidate.pageKey === 'decisionops_event_policy_designer_list',
    );

    expect(listPage).toBeDefined();
    expect(detailPage).toBeDefined();
    expect(designerPage).toBeDefined();

    const listSchema = canonicalizePageSchemaDto(listPage!);
    const tableBlock = listSchema.blocks.find((block: any) => block.blockType === 'table') as any;
    const rowActions = tableBlock.table.rowActions;
    const tableFields = tableBlock.table.columns.map((column: any) => column.field);

    expect(listSchema.blocks[0]).toMatchObject({
      component: 'EventPolicyActionsBlock',
      props: { mode: 'list' },
    });
    expect(tableFields).not.toEqual(
      expect.arrayContaining(['policyCode', 'targetType', 'targetKey', 'owner']),
    );
    expect(rowActions.map((action: any) => action.code)).toEqual(['detail', 'design', 'logs']);
    expect(rowActions.find((action: any) => action.code === 'design')).toMatchObject({
      action: {
        type: 'navigate',
        to: '/p/decisionops_event_policy_designer?policyCode={policyCode}',
      },
    });
    expect(rowActions.map((action: any) => action.action?.to)).not.toEqual(
      expect.arrayContaining(['/decision-ops']),
    );
    expect(detailPage!.blocks?.[0] as any).toMatchObject({
      component: 'EventPolicyActionsBlock',
      props: { mode: 'detail' },
    });
    expect(detailPage!.blocks).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          component: 'EventPolicyDesignerBlock',
          detailPlacement: 'header',
          props: { mode: 'detail' },
        }),
      ]),
    );
    expect(designerPage!.blocks?.[0] as any).toMatchObject({
      component: 'EventPolicyDesignerBlock',
    });
    expect((designerPage!.extension as any).customOnly).toBe(true);
  });

  it('hosts ExecutionLog advanced filters and trace chain in DSL custom blocks', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/core-decisionops/config/pages.json');
    const pages = readPages(pagesFile);
    const listPage = pages.find(
      (candidate) => candidate.pageKey === 'decisionops_execution_logs_list',
    );
    const detailPage = pages.find(
      (candidate) => candidate.pageKey === 'decisionops_execution_logs_detail',
    );

    expect(listPage).toBeDefined();
    expect(detailPage).toBeDefined();

    const listSchema = canonicalizePageSchemaDto(listPage!);
    expect((listPage!.extension as any).customOnly).toBe(true);
    expect(listSchema.blocks).toHaveLength(1);
    expect(listSchema.blocks[0]).toMatchObject({
      component: 'ExecutionLogTraceBlock',
      props: {
        mode: 'list',
        pageSize: 50,
      },
    });
    expect(JSON.stringify(listPage)).not.toContain('/decision-ops');
    expect(detailPage!.blocks?.[0] as any).toMatchObject({
      component: 'ExecutionLogTraceBlock',
      props: { mode: 'detail' },
    });
  });

  it('hosts Behavior Quarantine as an API-backed DSL list with replay action', () => {
    const root = resolve(process.cwd(), '..');
    const pagesFile = resolve(root, 'plugins/core-dashboard/config/pages.json');
    const page = readPages(pagesFile).find(
      (candidate) => candidate.pageKey === 'behavior_quarantine_list',
    );

    expect(page).toBeDefined();
    expect(page!.extension).toMatchObject({
      dataSource: {
        type: 'api',
        endpoint: '/api/analytics/behavior/quarantine',
        method: 'get',
      },
      skipFieldMeta: true,
    });

    const schema = canonicalizePageSchemaDto(page!);
    const filterBlock = schema.blocks.find((block: any) => block.blockType === 'filters') as any;
    const tableBlock = schema.blocks.find((block: any) => block.blockType === 'table') as any;
    const columns = tableBlock.table.columns.map((column: any) => column.field);
    const replayAction = tableBlock.table.rowActions.find(
      (action: any) => action.code === 'replay',
    );

    expect(filterBlock.fields.map((field: any) => field.field)).toEqual(['reason', 'replayStatus']);
    expect(columns).toEqual(
      expect.arrayContaining(['reason', 'replayStatus', 'eventId', 'detail', 'rawEvent']),
    );
    expect(replayAction.visibleWhen).toBe("record.replayStatus == 'pending'");
    expect(replayAction.action.type).toBe('flow');
    expect(replayAction.action.steps[0]).toMatchObject({
      action: 'api.request',
      endpoint: '/api/analytics/behavior/quarantine/{id}/replay',
      method: 'post',
    });
    expect(replayAction.action.steps.at(-1)).toMatchObject({
      action: 'dataSource.reload',
      args: { target: 'list' },
    });
  });
});

describe('canonicalizePageSchemaDto — convention command map carry-through', () => {
  it('carries the server-resolved commands map onto the unified schema', () => {
    const dto: PageSchemaDTO = {
      pageKey: 'showcase_all_fields_form',
      kind: 'form',
      modelCode: 'showcase_all_fields',
      blocks: [],
      commands: {
        create: 'sc:create_showcase',
        update: 'sc:update_showcase',
        delete: 'sc:delete_showcase',
      },
    };
    const schema = canonicalizePageSchemaDto(dto);
    expect(schema.commands).toEqual({
      create: 'sc:create_showcase',
      update: 'sc:update_showcase',
      delete: 'sc:delete_showcase',
    });
  });

  it('omits commands when the DTO has none (pure CRUD model)', () => {
    const dto: PageSchemaDTO = {
      pageKey: 'plain_model_form',
      kind: 'form',
      modelCode: 'plain_model',
      blocks: [],
    };
    const schema = canonicalizePageSchemaDto(dto);
    expect(schema.commands).toBeUndefined();
  });
});
