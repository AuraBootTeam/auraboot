import { describe, expect, it, vi, beforeEach } from 'vitest';
import { resolveLocalizedField, toFrontend } from '~/plugins/core-designer/components/bpmn-designer/services/bpmnService';

describe('bpmnService label normalization', () => {
  beforeEach(() => {
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
            id: 'task_manager_approve',
            type: 'userTask',
            data: {
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
            },
          },
        ],
      }),
      createdAt: null,
      updatedAt: null,
    });

    expect(definition.nodes[0]?.data.label).toBe('主管审批');
    expect(definition.edges[0]?.data?.label).toBe('主管');
    expect(definition.edges[0]?.label).toBe('主管');
  });
});
