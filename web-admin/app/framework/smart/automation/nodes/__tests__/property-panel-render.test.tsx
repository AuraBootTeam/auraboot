/**
 * Property-panel render coverage (Phase 3 Task 3.4).
 *
 * Asserts that the shared PropertyFieldRenderer — the renderer the automation flow
 * designer's FlowPropertyPanel delegates to (via PropertyField) — renders a control for
 * EVERY configSchema field `type` the 18 automation palette nodes actually use, and that
 * no automation node introduces a field type the renderer cannot render. This is the
 * front half of the 18-node-type coverage (the back half is the behavioral fire matrix in
 * tests/e2e/automation/automation-golden.spec.ts).
 *
 * Services + i18n are stubbed so the render is hermetic (no network); the per-type field
 * components (selects, expression editor, …) have their own unit tests.
 */
import { beforeEach, describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';

// Hermetic i18n.
vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (k: unknown, fallback?: string) =>
    fallback ?? (typeof k === 'string' ? k : ''),
  getLocalizedText: (t: unknown) => (typeof t === 'string' ? t : ''),
  useLocalizedText: () => (t: unknown) => String(t ?? ''),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US', t: (k: string, _p?: unknown, fb?: string) => fb ?? k }),
}));
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const resourceSelectServiceMocks = vi.hoisted(() => ({
  fetchPageOptions: vi.fn().mockResolvedValue([]),
  fetchDashboardOptions: vi.fn().mockResolvedValue([]),
  fetchProcessOptions: vi.fn().mockResolvedValue([]),
  fetchAutomationOptions: vi.fn().mockResolvedValue([]),
  fetchCommandOptions: vi.fn().mockResolvedValue([]),
  fetchModelOptions: vi.fn().mockResolvedValue([]),
  fetchFieldOptions: vi.fn().mockResolvedValue([]),
  fetchDictOptions: vi.fn().mockResolvedValue([]),
  fetchSemanticModelOptions: vi.fn().mockResolvedValue([]),
}));

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}));

// Resource/dict lookups resolve to empty so the *-select fields render a (zero-option)
// control instead of hitting the network.
vi.mock('~/shared/services/resourceSelectService', () => resourceSelectServiceMocks);
vi.mock('~/shared/services/dictService', () => ({
  dictService: { findAll: vi.fn().mockResolvedValue([]) },
}));
vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => ({
    get: apiMocks.get,
    post: apiMocks.post,
    delete: apiMocks.delete,
  }),
}));

import { PropertyFieldRenderer } from '~/shared/designer';
import { automationNodes } from '../index';
import { FlowPropertyPanel } from '~/plugins/core-designer/components/flow-designer-sdk/core/FlowPropertyPanel';
import { useFlowStore } from '~/plugins/core-designer/components/flow-designer-sdk/store/useFlowStore';
import { nodeRegistry } from '~/plugins/core-designer/components/flow-designer-sdk/nodes/NodeRegistry';

// The field types the automation palette nodes use (kept in sync with the configSchemas).
const EXPECTED_AUTOMATION_FIELD_TYPES = [
  'boolean',
  'command-select',
  'expression',
  'field-select',
  'json',
  'model-select',
  'multiselect',
  'number',
  'process-select',
  'rule-binding',
  'select',
  'text',
  'textarea',
] as const;

function stubAdapter(value: unknown = undefined) {
  return { value, setValue: vi.fn(), error: undefined, required: false, disabled: false };
}

function schemaFor(type: string) {
  const base: Record<string, unknown> = { key: `f_${type}`, label: type, type };
  if (type === 'select' || type === 'multiselect') {
    base.options = [
      { label: 'A', value: 'a' },
      { label: 'B', value: 'b' },
    ];
  }
  return base as any;
}

describe('Automation property-panel render coverage (Phase 3 Task 3.4)', () => {
  beforeEach(() => {
    resourceSelectServiceMocks.fetchFieldOptions.mockReset();
    resourceSelectServiceMocks.fetchFieldOptions.mockResolvedValue([]);
    apiMocks.get.mockReset();
    apiMocks.post.mockReset();
    apiMocks.delete.mockReset();
    apiMocks.get.mockResolvedValue({ data: { entities: [], facts: [] } });
    useFlowStore.getState().reset();
    nodeRegistry.registerAll(automationNodes);
  });

  it('the automation nodes use exactly the expected set of configSchema field types (no unhandled type slips in)', () => {
    const used = new Set<string>();
    for (const node of automationNodes) {
      for (const field of node.configSchema ?? []) {
        used.add(field.type);
      }
    }
    // Every used type must be in the expected set (a new/typo'd type fails here loudly)…
    for (const t of used) {
      expect(EXPECTED_AUTOMATION_FIELD_TYPES, `automation field type '${t}' is not in the expected set`).toContain(t);
    }
    // …and every expected type must still be exercised by at least one node (catches a
    // node losing a field type, which would silently drop coverage).
    for (const t of EXPECTED_AUTOMATION_FIELD_TYPES) {
      expect(used.has(t), `expected automation field type '${t}' is no longer used by any node`).toBe(true);
    }
  });

  it.each(EXPECTED_AUTOMATION_FIELD_TYPES)(
    'PropertyFieldRenderer renders a control for the %s field type',
    (type) => {
      const { container } = render(
        <PropertyFieldRenderer schema={schemaFor(type)} adapter={stubAdapter()} />,
      );
      // The renderer must produce SOME element for the type — i.e. it routes to a real
      // field control, not the unknown/empty fallback.
      expect(container.firstChild, `PropertyFieldRenderer rendered nothing for type '${type}'`).not.toBeNull();
    },
  );

  it('PropertyFieldRenderer writes static multiselect values as arrays', () => {
    const setValue = vi.fn();
    render(
      <PropertyFieldRenderer
        schema={schemaFor('multiselect')}
        adapter={{ ...stubAdapter([]), setValue }}
      />,
    );

    fireEvent.click(screen.getByPlaceholderText('Select...'));
    fireEvent.click(screen.getByRole('button', { name: /A/ }));

    expect(setValue).toHaveBeenCalledWith(['a']);
  });

  it('PropertyFieldRenderer keeps the current rule-binding decision visible in the decision select', () => {
    render(
      <PropertyFieldRenderer
        schema={{
          key: 'ruleBinding',
          label: '规则绑定',
          type: 'rule-binding',
          ruleBindingMode: 'decision',
          ruleBindingConsumerType: 'AUTOMATION',
        } as any}
        adapter={stubAdapter({
          consumerType: 'AUTOMATION',
          bindingKind: 'DECISION_REF',
          enabled: true,
          decisionBinding: {
            decisionCode: 'leave_request_automation',
            versionPolicy: 'LATEST_PUBLISHED',
            inputMappings: [],
            outputMappings: [],
            fallbackPolicy: { mode: 'FAIL_CLOSED' },
            traceMode: 'ALWAYS',
            enabled: true,
          },
        })}
      />,
    );

    expect(screen.getByLabelText('decision-code')).toHaveValue('leave_request_automation');
    expect(screen.getByRole('option', { name: '请假申请自动化策略' })).toHaveAttribute(
      'title',
      'leave_request_automation',
    );
  });

  it('automation trigger rule bindings scope the decision field catalog to the selected model', () => {
    const triggerSchemas = automationNodes
      .filter((node) => node.category === 'trigger')
      .flatMap((node) => node.configSchema ?? []);

    const ruleBindingSchemas = triggerSchemas.filter((field) => field.type === 'rule-binding');

    expect(ruleBindingSchemas.length).toBeGreaterThan(0);
    for (const schema of ruleBindingSchemas) {
      expect(schema).toMatchObject({
        ruleBindingConsumerType: 'AUTOMATION',
        ruleBindingFieldCatalogModelCodeField: 'modelCode',
        ruleBindingInitialContextJsonField: 'testContext',
      });
    }
  });

  it('passes the automation trigger sample context into the rule-binding test runner', () => {
    render(
      <PropertyFieldRenderer
        schema={{
          key: 'ruleBinding',
          label: '规则绑定',
          type: 'rule-binding',
          ruleBindingMode: 'decision',
          ruleBindingConsumerType: 'AUTOMATION',
          ruleBindingShowTestRunner: true,
          ruleBindingInitialContextJsonField: 'testContext',
        } as any}
        adapter={{
          ...stubAdapter({
            consumerType: 'AUTOMATION',
            bindingKind: 'DECISION_REF',
            enabled: true,
            decisionBinding: {
              decisionCode: 'leave_request_automation',
              versionPolicy: 'LATEST_PUBLISHED',
              inputMappings: [],
              outputMappings: [],
              fallbackPolicy: { mode: 'FAIL_CLOSED' },
              traceMode: 'ALWAYS',
              enabled: true,
            },
          }),
          context: {
            testContext: {
              record: {
                wd_req_no: 'REQ-LONG-LEAVE-SAMPLE',
                wd_req_days: 5,
              },
            },
            source: 'ui-test-run',
          },
        }}
      />,
    );

    const contextValue = (screen.getByLabelText('test-run-context') as HTMLTextAreaElement).value;
    expect(contextValue).toContain('"data": {');
    expect(contextValue).toContain('"wd_req_days": 5');
    expect(contextValue).toContain('REQ-LONG-LEAVE-SAMPLE');
    expect(contextValue).not.toContain('ui-test-run');
  });

  it('loads the automation action expression picker from the unified fact catalog before legacy model fields', async () => {
    apiMocks.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
      if (endpoint === '/decision/facts/catalog') {
        expect(params).toEqual({ modelCode: 'wd_leave_request' });
        return Promise.resolve({
          data: {
            entities: [
              {
                entityCode: 'wd_leave_request',
                modelCode: 'wd_leave_request',
                modelName: '请假申请',
                facts: [
                  {
                    factKey: 'record.data.wd_leave_type',
                    scope: 'record',
                    path: 'data.wd_leave_type',
                    label: '请假类型',
                    dataType: 'dict',
                    dictCode: 'wd_leave_type',
                    allowedValues: [
                      { value: 'annual', label: '年假' },
                      { value: 'sick', label: '病假' },
                    ],
                  },
                  {
                    factKey: 'record.data.wd_req_days',
                    scope: 'record',
                    path: 'data.wd_req_days',
                    label: '请假天数',
                    dataType: 'decimal',
                  },
                ],
              },
            ],
          },
        });
      }
      return Promise.resolve({ data: {} });
    });

    let actionId = '';
    act(() => {
      useFlowStore.getState().addNode({
        type: 'trigger-record-create',
        position: { x: 0, y: 0 },
        data: {
          label: '创建请假申请',
          config: {
            triggerType: 'on_record_create',
            modelCode: 'wd_leave_request',
            testContext: {
              record: {
                wd_req_no: 'REQ-LONG-LEAVE-SAMPLE',
                wd_req_days: 5,
              },
            },
            ruleBinding: {
              consumerType: 'AUTOMATION',
              bindingKind: 'DECISION_REF',
              enabled: true,
              decisionBinding: {
                decisionCode: 'leave_request_automation',
                versionPolicy: 'LATEST_PUBLISHED',
                inputMappings: [],
                outputMappings: [
                  { output: 'severity', target: { kind: 'ACTION_PARAM', path: 'severity' } },
                  { output: 'message', target: { kind: 'ACTION_PARAM', path: 'message' } },
                ],
                fallbackPolicy: { mode: 'FAIL_CLOSED' },
                traceMode: 'ALWAYS',
                enabled: true,
              },
            },
          },
        },
      });
      actionId = useFlowStore.getState().addNode({
        type: 'action-send-notification',
        position: { x: 320, y: 0 },
        data: {
          label: '发送通知',
          config: {
            actionType: 'send_notification',
            notificationType: 'in_app',
            title: '提醒 ',
            content: '请处理',
            recipients: 'ROLE:wd_manager',
          },
        },
      });
      useFlowStore.getState().selectNode(actionId);
    });

    render(<FlowPropertyPanel />);

    await waitFor(() =>
      expect(apiMocks.get).toHaveBeenCalledWith('/decision/facts/catalog', { modelCode: 'wd_leave_request' }),
    );
    expect(resourceSelectServiceMocks.fetchFieldOptions).not.toHaveBeenCalled();

    fireEvent.click(screen.getAllByText('插入字段')[0]);

    const picker = screen.getAllByTestId('formula-field-picker')[0];
    expect(picker).toHaveTextContent('常用字段');
    expect(pickerButtons(picker, /请假类型.*record\.data\.wd_leave_type/).length).toBeGreaterThan(0);
    expect(pickerButtons(picker, /请假天数.*record\.data\.wd_req_days/).length).toBeGreaterThan(0);
    expect(picker).toHaveTextContent('规则输出');
    expect(pickerButtons(picker, /severity.*decision\.outputs\.severity/).length).toBeGreaterThan(0);

    fireEvent.click(pickerButtons(picker, /请假类型.*record\.data\.wd_leave_type/)[0]);

    const action = useFlowStore.getState().nodes.find((node) => node.id === actionId)!;
    expect(action.data.config.title).toBe('提醒 ${record.data.wd_leave_type}');
  });
});

function pickerButtons(container: HTMLElement, name: RegExp): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll('button')).filter((button) =>
    name.test(button.textContent?.replace(/\s+/g, ' ') ?? ''),
  );
}
