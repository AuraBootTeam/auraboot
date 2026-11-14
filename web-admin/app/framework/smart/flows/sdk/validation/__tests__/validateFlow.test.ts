import { describe, it, expect } from 'vitest';
import { validateFlow } from '../validateFlow';
import type { FlowNode } from '../../store/types';
import type { FlowNodeDefinition, PropertySchema } from '../../nodes/types';

function def(type: string, configSchema?: PropertySchema[]): FlowNodeDefinition {
  return { type, label: type, icon: '', category: 'test', configSchema };
}

function resolver(defs: FlowNodeDefinition[]) {
  return (type: string) => defs.find((d) => d.type === type);
}

function node(id: string, type: string, config: Record<string, unknown>): FlowNode {
  return { id, type, position: { x: 0, y: 0 }, data: { label: id, config } };
}

describe('validateFlow', () => {
  const triggerDef = def('trigger', [
    { key: 'modelCode', label: 'Model', type: 'model', required: true },
    { key: 'note', label: 'Note', type: 'text' }, // optional
  ]);

  it('returns valid when all required fields are filled', () => {
    const result = validateFlow(
      [node('t1', 'trigger', { modelCode: 'crm_lead' })],
      resolver([triggerDef]),
    );
    expect(result.valid).toBe(true);
    expect(result.errors).toHaveLength(0);
  });

  it('flags an empty required field with nodeId + fieldKey + error type', () => {
    const result = validateFlow([node('t1', 'trigger', {})], resolver([triggerDef]));
    expect(result.valid).toBe(false);
    expect(result.errors).toHaveLength(1);
    expect(result.errors[0]).toMatchObject({
      nodeId: 't1',
      fieldKey: 'modelCode',
      type: 'error',
    });
  });

  it('treats empty string and empty array as empty', () => {
    const arrDef = def('a', [
      { key: 'name', label: 'Name', type: 'text', required: true },
      { key: 'items', label: 'Items', type: 'array', required: true },
    ]);
    const result = validateFlow(
      [node('a1', 'a', { name: '   ', items: [] })],
      resolver([arrDef]),
    );
    expect(result.errors.map((e) => e.fieldKey).sort()).toEqual(['items', 'name']);
  });

  it('does not flag a required field hidden by an unmet dependsOn', () => {
    const condDef = def('c', [
      { key: 'mode', label: 'Mode', type: 'select', required: true },
      {
        key: 'expression',
        label: 'Expr',
        type: 'expression',
        required: true,
        dependsOn: { field: 'mode', value: 'advanced' },
      },
    ]);
    // mode=simple => expression field hidden => not required
    const result = validateFlow(
      [node('c1', 'c', { mode: 'simple' })],
      resolver([condDef]),
    );
    expect(result.valid).toBe(true);
  });

  it('flags a required field whose dependsOn is satisfied', () => {
    const condDef = def('c', [
      { key: 'mode', label: 'Mode', type: 'select', required: true },
      {
        key: 'expression',
        label: 'Expr',
        type: 'expression',
        required: true,
        dependsOn: { field: 'mode', value: 'advanced' },
      },
    ]);
    const result = validateFlow(
      [node('c1', 'c', { mode: 'advanced' })],
      resolver([condDef]),
    );
    expect(result.errors.map((e) => e.fieldKey)).toEqual(['expression']);
  });

  it('skips nodes whose definition is missing or has no configSchema', () => {
    const result = validateFlow(
      [node('x1', 'unknown', {}), node('p1', 'plain', {})],
      resolver([def('plain')]),
    );
    expect(result.valid).toBe(true);
  });

  it('uses the provided requiredMessage', () => {
    const result = validateFlow([node('t1', 'trigger', {})], resolver([triggerDef]), {
      requiredMessage: '此字段必填',
    });
    expect(result.errors[0].message).toBe('此字段必填');
  });
});
