import { describe, expect, it } from 'vitest';
import { migratePageSchemaV2ToV3 } from '../migration/migrateToV3';
import { serializePageTreeToFlat } from '../persistence/flatPageSerializer';
import { findBlockById } from '../utils/recursiveBlockWalker';
import type { LegacyDslBlockV2, LegacyPageSchemaV2 } from '../types';

function load(block: LegacyDslBlockV2) {
  const stored: LegacyPageSchemaV2 = {
    schemaVersion: 4, kind: 'detail', id: 'stored_preview',
    modelCode: 'page_schema', blocks: [block],
  };
  const tree = migratePageSchemaV2ToV3(stored);
  return { stored, tree, block: findBlockById(tree.blocks, block.id!)!.block };
}

const helpers = [
  {
    blockType: 'ai-fill-banner',
    props: { description: 'Authored suggestions', feedback: 'Applied',
      suggestedFields: [{ field: 'name', label: 'Name', value: 'Ready' }] },
  },
  {
    blockType: 'workflow-panel',
    props: { description: 'Awaiting approval', status: 'pending', assignee: 'Reviewer',
      dueAt: '2026-05-21', actions: [{ label: 'Approve', actionType: 'approve' }] },
  },
  {
    blockType: 'activity-timeline',
    props: { items: [{ actor: 'Operator', action: 'Updated', time: '2026-05-20', description: 'Changed' }] },
  },
  {
    blockType: 'field-history',
    props: { entries: [{ field: 'status', from: 'draft', to: 'review', changedBy: 'Reviewer' }] },
  },
];

describe('stored passthrough property locations', () => {
  it.each(helpers)('preserves authored $blockType props through repeated stored-page loads', fixture => {
    const original: LegacyDslBlockV2 = {
      id: 'helper', span: 12, ...fixture,
      dataSource: { type: 'namedQuery', executionMode: 'preview', queryCode: 'configured_query' },
    };
    const first = load(original);
    expect(first.block.props).toEqual(fixture.props);
    expect(first.block.dataSource).toEqual(original.dataSource);
    for (const key of Object.keys(fixture.props)) {
      expect(Object.hasOwn(first.block, key)).toBe(false);
    }
    const flat = serializePageTreeToFlat(first.tree);
    expect(flat.blocks).toEqual([original]);
    const reloaded = migratePageSchemaV2ToV3({ ...first.stored, blocks: flat.blocks });
    expect(findBlockById(reloaded.blocks, 'helper')?.block.props).toEqual(fixture.props);
  });

  it('keeps helper props separate from same-named common metadata', () => {
    const original: LegacyDslBlockV2 = {
      id: 'workflow', blockType: 'workflow-panel', span: 12,
      description: 'Block metadata', area: 'detail',
      props: { description: 'Helper description', area: 'panel' },
    };
    const first = load(original);
    expect(first.block).toMatchObject({ description: original.description, area: original.area });
    expect(first.block.props).toEqual(original.props);
    const flat = serializePageTreeToFlat(first.tree);
    expect(flat.blocks).toEqual([original]);
    const second = load(flat.blocks[0]);
    expect(second.block.props).toEqual(original.props);
    expect(serializePageTreeToFlat(second.tree).blocks).toEqual([original]);
  });

  it('keeps existing top-level display keys at the top level', () => {
    const original: LegacyDslBlockV2 = {
      id: 'metric', blockType: 'metric-strip', span: 12,
      variant: 'compact', metrics: [{ label: 'Total', value: 7 }],
    };
    const loaded = load(original);
    expect(loaded.block).toMatchObject({ variant: 'compact', metrics: original.metrics });
    expect(loaded.block.props).toBeUndefined();
    expect(serializePageTreeToFlat(loaded.tree).blocks).toEqual([original]);
  });

  it('does not hoist explicit props alongside legacy top-level display keys', () => {
    const original: LegacyDslBlockV2 = {
      id: 'metric', blockType: 'metric-strip', span: 12,
      variant: 'compact', metrics: [{ label: 'Total', value: 7 }], props: { dense: true },
    };
    const loaded = load(original);
    expect(loaded.block).toMatchObject({ variant: 'compact', metrics: original.metrics });
    expect(loaded.block.props).toEqual({ dense: true });
    expect(Object.hasOwn(loaded.block, 'dense')).toBe(false);
    expect(serializePageTreeToFlat(loaded.tree).blocks).toEqual([original]);
  });

  it('retains both locations when an explicit prop and a legacy key share a name', () => {
    const original: LegacyDslBlockV2 = {
      id: 'description', blockType: 'description', span: 12,
      content: 'Top-level display content', props: { content: 'Explicit nested metadata' },
    };
    const loaded = load(original);
    expect(loaded.block).toMatchObject({ content: original.content });
    expect(loaded.block.props).toEqual(original.props);
    expect(serializePageTreeToFlat(loaded.tree).blocks).toEqual([original]);
  });
});


describe('stored passthrough structural configuration', () => {
  it.each([
    { blockType: 'embedded-list', columns: [{ field: 'status', label: 'Status' }] },
    { blockType: 'workbench-action-bar', actions: [{ code: 'approve', label: 'Approve' }] },
    { blockType: 'candidate-list', selection: { mode: 'single', valueField: 'pid' } },
  ])('retains $blockType configuration through two loads and saves', fixture => {
    const original = { id: 'configured', span: 12, ...fixture } as LegacyDslBlockV2;
    const first = load(original);
    expect(first.block).toMatchObject(fixture);
    const flat = serializePageTreeToFlat(first.tree);
    expect(flat.blocks).toEqual([original]);
    const second = load(flat.blocks[0]);
    expect(second.block).toMatchObject(fixture);
    expect(serializePageTreeToFlat(second.tree).blocks).toEqual([original]);
  });
});


describe('stored repeating field identity', () => {
  it.each(['repeater', 'subform'])('keeps the nested %s field key across stored-page reloads', blockType => {
    const original: LegacyDslBlockV2 = {
      id: 'owned_rows', blockType, props: { rows: [{ sku: 'Initial SKU' }] },
      blocks: [{ id: 'field_sku', blockType: 'field', field: 'sku', props: { label: 'SKU', component: 'input' } }],
    };
    const first = load(original);
    expect(first.block.blocks?.[0].field).toBe('sku');
    expect(first.block.blocks?.[0].props).toEqual({ label: 'SKU', component: 'input' });
    expect(serializePageTreeToFlat(first.tree).blocks).toEqual([original]);
    const second = load(serializePageTreeToFlat(first.tree).blocks[0]);
    expect(second.block.blocks?.[0].field).toBe('sku');
  });
});
