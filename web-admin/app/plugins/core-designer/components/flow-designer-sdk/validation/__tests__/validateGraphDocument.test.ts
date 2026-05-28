// web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/__tests__/validateGraphDocument.test.ts
import { describe, it, expect } from 'vitest';
import { validateGraphDocument } from '../validateGraphDocument';
import { diffGraphDocuments } from '../diffGraphDocuments';

/**
 * Reusable minimal valid document — automation kind with one trigger node,
 * one action node and a connecting edge. Tests then mutate clones of this.
 */
function makeValidAutomationDoc() {
  return {
    schemaVersion: '1.0',
    kind: 'automation',
    meta: {
      key: 'auto-001',
      name: { 'en-US': 'Send welcome email', 'zh-CN': '发送欢迎邮件' },
      automation: {
        trigger: { type: 'record-create', modelCode: 'crm_lead', config: {} },
      },
    },
    nodes: [
      {
        id: 't1',
        type: 'trigger-record-create',
        position: { x: 0, y: 0 },
        data: { label: 'Trigger', config: {} },
      },
      {
        id: 'a1',
        type: 'action-send-notification',
        position: { x: 200, y: 0 },
        data: { label: 'Notify', config: { template: 'welcome' } },
      },
    ],
    edges: [{ id: 'e1', source: 't1', target: 'a1' }],
  };
}

describe('validateGraphDocument — happy path', () => {
  it('accepts a minimal valid automation document', () => {
    const result = validateGraphDocument(makeValidAutomationDoc());
    expect(result.valid).toBe(true);
    expect(result.errors).toEqual([]);
  });

  it('accepts a minimal valid bpmn document with one start + one end', () => {
    const doc = {
      schemaVersion: '1.0',
      kind: 'bpmn',
      meta: { key: 'proc-001', name: 'Leave Request' },
      nodes: [
        {
          id: 's1',
          type: 'startEvent',
          position: { x: 0, y: 0 },
          data: { label: 'Start', config: {} },
        },
        {
          id: 'u1',
          type: 'userTask',
          position: { x: 100, y: 0 },
          data: { label: 'Approve', config: { name: 'Approve' } },
        },
        {
          id: 'end1',
          type: 'endEvent',
          position: { x: 200, y: 0 },
          data: { label: 'End', config: {} },
        },
      ],
      edges: [
        { id: 'e1', source: 's1', target: 'u1' },
        { id: 'e2', source: 'u1', target: 'end1' },
      ],
    };
    expect(validateGraphDocument(doc).valid).toBe(true);
  });
});

describe('validateGraphDocument — schema-layer failures', () => {
  it('rejects a non-object input', () => {
    const result = validateGraphDocument('not an object');
    expect(result.valid).toBe(false);
    expect(result.errors[0].code).toBe('GRAPH-SCHEMA.NOT_AN_OBJECT');
  });

  it('rejects missing top-level required fields', () => {
    const result = validateGraphDocument({ kind: 'automation' });
    expect(result.valid).toBe(false);
    const codes = result.errors.map((e) => e.code);
    expect(codes).toContain('GRAPH-SCHEMA.MISSING_REQUIRED');
  });

  it('rejects an unsupported kind enum', () => {
    const doc = makeValidAutomationDoc();
    (doc as any).kind = 'workflow';
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'GRAPH-SCHEMA.ENUM_MISMATCH')).toBe(true);
  });

  it('rejects a wrong schemaVersion (const check)', () => {
    const doc = makeValidAutomationDoc();
    (doc as any).schemaVersion = '0.9';
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'GRAPH-SCHEMA.CONST_MISMATCH')).toBe(true);
  });

  it('rejects the retired data.type sub-discriminator (spec §3.2)', () => {
    const doc = makeValidAutomationDoc();
    (doc.nodes[0] as any).data.type = 'trigger';
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'GRAPH-SCHEMA.DEPRECATED_FIELD')).toBe(true);
  });

  it('rejects a structured condition with an invalid type enum', () => {
    const doc = makeValidAutomationDoc();
    doc.nodes.push({
      id: 'g1',
      type: 'control-condition',
      position: { x: 50, y: 50 },
      data: { label: 'gw', config: {} },
    } as any);
    doc.edges = [
      { id: 'e1', source: 't1', target: 'g1' },
      {
        id: 'e2',
        source: 'g1',
        target: 'a1',
        data: { condition: { type: 'WAT', content: 'x' } },
      } as any,
    ];
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'GRAPH-SCHEMA.ENUM_MISMATCH')).toBe(true);
  });
});

describe('validateGraphDocument — semantic rules (spec §6)', () => {
  it('flags duplicate node ids', () => {
    const doc = makeValidAutomationDoc();
    doc.nodes.push({ ...doc.nodes[1], id: 't1' }); // collide with t1
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'GRAPH-SEMANTIC.DUPLICATE_NODE_ID')).toBe(true);
  });

  it('flags an edge pointing at a missing node', () => {
    const doc = makeValidAutomationDoc();
    doc.edges.push({ id: 'e2', source: 't1', target: 'ghost' } as any);
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.code === 'GRAPH-SEMANTIC.EDGE_TARGET_NOT_FOUND'),
    ).toBe(true);
  });

  it('flags zero start nodes (automation: missing trigger-*)', () => {
    const doc = makeValidAutomationDoc();
    doc.nodes[0].type = 'action-noop';
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'GRAPH-SEMANTIC.NO_START_NODE')).toBe(true);
  });

  it('flags multiple start nodes', () => {
    const doc = makeValidAutomationDoc();
    doc.nodes.push({
      id: 't2',
      type: 'trigger-scheduled',
      position: { x: 0, y: 100 },
      data: { label: 'Cron', config: {} },
    });
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.code === 'GRAPH-SEMANTIC.MULTIPLE_START_NODES'),
    ).toBe(true);
  });

  it('flags gateway out-edges that lack both condition and isDefault', () => {
    const doc = makeValidAutomationDoc();
    doc.nodes.push({
      id: 'g1',
      type: 'control-condition',
      position: { x: 100, y: 100 },
      data: { label: 'gw', config: {} },
    });
    doc.nodes.push({
      id: 'a2',
      type: 'action-send-notification',
      position: { x: 300, y: 100 },
      data: { label: 'a2', config: {} },
    });
    doc.edges = [
      { id: 'e1', source: 't1', target: 'g1' },
      { id: 'e2', source: 'g1', target: 'a1' },
      { id: 'e3', source: 'g1', target: 'a2' },
    ];
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    const offenders = result.errors.filter(
      (e) => e.code === 'GRAPH-SEMANTIC.GATEWAY_EDGE_MISSING_CONDITION',
    );
    expect(offenders.length).toBe(2);
  });

  it('flags a gateway with two default out-edges', () => {
    const doc = makeValidAutomationDoc();
    doc.nodes.push({
      id: 'g1',
      type: 'control-condition',
      position: { x: 100, y: 100 },
      data: { label: 'gw', config: {} },
    });
    doc.nodes.push({
      id: 'a2',
      type: 'action-send-notification',
      position: { x: 300, y: 100 },
      data: { label: 'a2', config: {} },
    });
    doc.edges = [
      { id: 'e1', source: 't1', target: 'g1' },
      { id: 'e2', source: 'g1', target: 'a1', data: { isDefault: true } } as any,
      { id: 'e3', source: 'g1', target: 'a2', data: { isDefault: true } } as any,
    ];
    const result = validateGraphDocument(doc);
    expect(result.valid).toBe(false);
    expect(
      result.errors.some((e) => e.code === 'GRAPH-SEMANTIC.GATEWAY_MULTIPLE_DEFAULTS'),
    ).toBe(true);
  });
});

describe('diffGraphDocuments — 4 known grammar divergences', () => {
  /**
   * Synthesises a "current automation" doc — mirrors what
   * web-admin/app/framework/smart/automation/components/AutomationEditor.tsx
   * still emits today (no envelope, bare-string condition).
   */
  function legacyAutomationDoc() {
    return {
      nodes: [
        {
          id: 't1',
          type: 'trigger-record-create',
          position: { x: 0, y: 0 },
          data: { label: 'Trigger', type: 'trigger', config: {} },
        },
        {
          id: 'a1',
          type: 'action-send-notification',
          position: { x: 200, y: 0 },
          data: { label: 'Notify', type: 'action', config: {} },
        },
      ],
      edges: [
        { id: 'e1', source: 't1', target: 'a1', data: { condition: 'amount > 100' } },
      ],
    };
  }

  /**
   * Synthesises a "current bpmn" doc — mirrors BPMNProcessDefinition shape
   * (root-level key/name/category, edges already structured).
   */
  function legacyBpmnDoc() {
    return {
      key: 'leave-request',
      name: 'Leave Request',
      category: 'hr',
      version: 1,
      nodes: [
        {
          id: 's1',
          type: 'startEvent',
          position: { x: 0, y: 0 },
          data: { type: 'startEvent', label: 'Start', config: {} },
        },
        {
          id: 'g1',
          type: 'exclusiveGateway',
          position: { x: 100, y: 0 },
          data: { type: 'exclusiveGateway', label: 'gw', config: {} },
        },
      ],
      edges: [
        { id: 'e1', source: 's1', target: 'g1' },
      ],
    };
  }

  it('detects all 4 spec divergences across the current pair', () => {
    const report = diffGraphDocuments(legacyAutomationDoc(), legacyBpmnDoc());
    const codes = new Set(report.divergences.map((d) => d.code));
    // D1 envelope missing on both sides; D2 data.type on both sides;
    // D3 bare-string condition on the automation side; D4 root meta on bpmn side.
    expect(codes.has('D1')).toBe(true);
    expect(codes.has('D2')).toBe(true);
    expect(codes.has('D3')).toBe(true);
    expect(codes.has('D4')).toBe(true);
  });

  it('reports zero divergences when both inputs are already spec-conformant', () => {
    const a = {
      schemaVersion: '1.0',
      kind: 'automation',
      meta: {
        key: 'k',
        name: 'n',
        automation: { trigger: { type: 'record-create', config: {} } },
      },
      nodes: [
        {
          id: 't1',
          type: 'trigger-record-create',
          position: { x: 0, y: 0 },
          data: { label: 'l', config: {} },
        },
      ],
      edges: [],
    };
    const b = {
      schemaVersion: '1.0',
      kind: 'bpmn',
      meta: { key: 'p', name: 'P' },
      nodes: [
        {
          id: 's1',
          type: 'startEvent',
          position: { x: 0, y: 0 },
          data: { label: 'Start', config: {} },
        },
      ],
      edges: [],
    };
    const report = diffGraphDocuments(a, b);
    expect(report.divergences).toEqual([]);
  });

  it('attaches an evidence snippet for D3 bare-string condition', () => {
    const report = diffGraphDocuments(legacyAutomationDoc(), legacyBpmnDoc());
    const d3 = report.divergences.find((d) => d.code === 'D3');
    expect(d3).toBeDefined();
    expect(d3?.evidence).toContain('amount > 100');
  });
});
