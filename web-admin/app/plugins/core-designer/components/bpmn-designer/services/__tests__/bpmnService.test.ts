import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fetchResult } from '~/shared/services/http-client';
import {
  createProcessDefinition,
  normalizeDesignerJsonPayload,
  resolveLocalizedField,
  serializeDesignerJson,
  toFrontend,
} from '~/plugins/core-designer/components/bpmn-designer/services/bpmnService';
import { BPMNNodeType } from '~/plugins/core-designer/components/bpmn-designer/types';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

describe('bpmnService label normalization', () => {
  beforeEach(() => {
    vi.mocked(fetchResult).mockReset();
    vi.stubGlobal('navigator', { language: 'zh-CN' });
  });

  it('prefers localized label fields when plain label is missing', () => {
    expect(
      resolveLocalizedField(
        {
          'label:zh-CN': '主管审批',
          'label:en': 'Manager Approval',
        },
        'label',
      ),
    ).toBe('主管审批');
  });

  it('normalizes designerJson nodes and edges into label fields', () => {
    const definition = toFrontend({
      pid: 'P1',
      processKey: 'wd_leave_approval',
      processName: 'Leave Approval',
      description: null,
      category: 'approval',
      status: 'draft',
      version: 1,
      isCurrent: true,
      deploymentId: null,
      deployedAt: null,
      formBindings: null,
      designerJson: JSON.stringify({
        nodes: [
          {
            id: 'gw',
            type: 'exclusiveGateway',
            position: { x: 100, y: 100 },
            data: {
              type: 'exclusiveGateway',
              label: 'Gateway',
            },
          },
          {
            id: 'task_manager_approve',
            type: 'userTask',
            position: { x: 300, y: 100 },
            data: {
              type: 'userTask',
              'label:zh-CN': '主管审批',
              'label:en': 'Manager Approval',
            },
          },
        ],
        edges: [
          {
            id: 'flow_gw_manager',
            source: 'gw',
            target: 'task_manager_approve',
            data: {
              'label:zh-CN': '主管',
              'label:en': 'Manager',
              condition: {
                type: 'expression',
                content: '${approverRole == "manager"}',
              },
            },
          },
        ],
      }),
      createdAt: null,
      updatedAt: null,
    });

    expect(definition.nodes[1]?.data.label).toBe('主管审批');
    expect(definition.edges[0]?.data?.label).toBe('主管');
    expect(definition.edges[0]?.label).toBe('主管');
  });

  it('rejects designerJson nodes without positions', () => {
    expect(() =>
      toFrontend({
        pid: 'P2',
        processKey: 'legacy_gateway',
        processName: 'Legacy Gateway',
        description: null,
        category: 'approval',
        status: 'draft',
        version: 1,
        isCurrent: true,
        deploymentId: null,
        deployedAt: null,
        formBindings: null,
        designerJson: JSON.stringify({
          nodes: [
            { id: 'start', type: 'startEvent', data: { type: 'startEvent' } },
            { id: 'gw', type: 'exclusiveGateway', data: { type: 'exclusiveGateway' } },
          ],
          edges: [{ id: 'e1', source: 'start', target: 'gw', data: {} }],
        }),
        createdAt: null,
        updatedAt: null,
      }),
    ).toThrow('designerJson.nodes[0].position must be an object');
  });

  it('defaults missing designerJson edge data for legacy payloads', () => {
    const designer = normalizeDesignerJsonPayload({
      nodes: [
        {
          id: 'start',
          type: BPMNNodeType.START_EVENT,
          position: { x: 100, y: 100 },
          data: { type: BPMNNodeType.START_EVENT, label: 'Start' },
        },
        {
          id: 'end',
          type: BPMNNodeType.END_EVENT,
          position: { x: 300, y: 100 },
          data: { type: BPMNNodeType.END_EVENT, label: 'End' },
        },
      ],
      edges: [{ id: 'e1', source: 'start', target: 'end' }],
    });

    expect(designer.edges[0].data).toEqual({});
  });

  it('defaults missing designerJson node data.type from the top-level node type', () => {
    const designer = normalizeDesignerJsonPayload({
      nodes: [
        {
          id: 'start',
          type: BPMNNodeType.START_EVENT,
          position: { x: 100, y: 100 },
          data: { label: 'Start' },
        },
        {
          id: 'end',
          type: BPMNNodeType.END_EVENT,
          position: { x: 300, y: 100 },
          data: { label: 'End' },
        },
      ],
      edges: [{ id: 'e1', source: 'start', target: 'end' }],
    });

    expect(designer.nodes[0].data.type).toBe(BPMNNodeType.START_EVENT);
    expect(designer.nodes[1].data.type).toBe(BPMNNodeType.END_EVENT);
  });

  it('accepts backend service delegate node types with required fields', () => {
    const designer = normalizeDesignerJsonPayload({
      nodes: [
        {
          id: 'start',
          type: BPMNNodeType.START_EVENT,
          position: { x: 100, y: 100 },
          data: { type: BPMNNodeType.START_EVENT, label: 'Start' },
        },
        {
          id: 'rule',
          type: BPMNNodeType.RULE_TASK,
          position: { x: 300, y: 100 },
          data: { type: BPMNNodeType.RULE_TASK, label: 'Rule', ruleCode: 'wd_leave_routing' },
        },
      ],
      edges: [{ id: 'e1', source: 'start', target: 'rule', data: { label: '' } }],
    });

    expect(designer.nodes[1]?.data.type).toBe(BPMNNodeType.RULE_TASK);
  });

  it('rejects rule-task nodes without ruleCode', () => {
    expect(() =>
      normalizeDesignerJsonPayload({
        nodes: [
          {
            id: 'rule',
            type: BPMNNodeType.RULE_TASK,
            position: { x: 100, y: 100 },
            data: { type: BPMNNodeType.RULE_TASK, label: 'Rule' },
          },
        ],
        edges: [],
      }),
    ).toThrow('designerJson.nodes[0].data.ruleCode must be a non-empty string');
  });

  it('rejects exclusive gateway outgoing edges without conditions', () => {
    expect(() =>
      normalizeDesignerJsonPayload({
        nodes: [
          {
            id: 'gw',
            type: BPMNNodeType.EXCLUSIVE_GATEWAY,
            position: { x: 100, y: 100 },
            data: { type: BPMNNodeType.EXCLUSIVE_GATEWAY, label: 'Gateway' },
          },
          {
            id: 'task',
            type: BPMNNodeType.USER_TASK,
            position: { x: 300, y: 100 },
            data: { type: BPMNNodeType.USER_TASK, label: 'Task' },
          },
        ],
        edges: [{ id: 'e1', source: 'gw', target: 'task', data: { label: 'Missing condition' } }],
      }),
    ).toThrow('designerJson.edges[0].data.condition must be an object');
  });

  it('serializes a strict designerJson payload without React Flow runtime fields', () => {
    const serialized = serializeDesignerJson({
      nodes: [
        {
          id: 'start',
          type: BPMNNodeType.START_EVENT,
          position: { x: 100, y: 100 },
          measured: { width: 36, height: 36 },
          selected: true,
          dragging: false,
          data: { type: BPMNNodeType.START_EVENT, label: 'Start' },
        } as never,
      ],
      edges: [
        {
          id: 'e1',
          source: 'start',
          target: 'start',
          selected: true,
          interactionWidth: 20,
          data: { label: '' },
        } as never,
      ],
    });
    const parsed = JSON.parse(serialized);

    expect(parsed.nodes[0].measured).toBeUndefined();
    expect(parsed.nodes[0].selected).toBeUndefined();
    expect(parsed.nodes[0].dragging).toBeUndefined();
    expect(parsed.edges[0].selected).toBeUndefined();
    expect(parsed.edges[0].interactionWidth).toBeUndefined();
    expect(parsed.nodes[0].position).toEqual({ x: 100, y: 100 });
  });

  it('validates designerJson before sending create requests', async () => {
    await expect(
      createProcessDefinition({
        key: 'bad_import',
        name: 'Bad Import',
        status: 'draft',
        nodes: [
          {
            id: 'start',
            type: BPMNNodeType.START_EVENT,
            data: { type: BPMNNodeType.START_EVENT, label: 'Start' },
          } as never,
        ],
        edges: [],
      }),
    ).rejects.toThrow('designerJson.nodes[0].position must be an object');

    expect(fetchResult).not.toHaveBeenCalled();
  });
});
