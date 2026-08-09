import { describe, expect, it } from 'vitest';
import type { CapabilityRegistry } from '~/framework/meta/authoring/types';
import type { PageSchemaV3 } from '../types';
import {
  authoringSnapshotToPageSchemaV3,
  buildStudioThreeWayMerge,
  planStudioAuthoringPatches,
  resolveStudioThreeWayMerge,
} from '../persistence/contextualAuthoringAdapter';

const capabilities: CapabilityRegistry = {
  checksum: 'registry-1',
  manifests: [
    {
      blockType: 'table',
      pluginCode: 'core.designer',
      pluginVersion: '1',
      manifestVersion: '1',
      checksum: 'table-1',
      properties: {
        '/title': property('/title', 'INLINE'),
        '/dataSource': property('/dataSource', 'HANDOFF_STUDIO'),
        '/$structure/order': property('/$structure/order', 'GUIDED_INLINE', ['MOVE']),
      },
    },
  ],
};

describe('contextualAuthoringAdapter', () => {
  it('materializes the isolated V3 snapshot and plans manifest-backed property patches', () => {
    const baseline = authoringSnapshotToPageSchemaV3({
      pid: 'page-1',
      pageKey: 'orders_list',
      schemaVersion: 3,
      kind: 'list',
      blocks: [
        {
          id: 'table-1',
          blockType: 'table',
          title: 'Orders',
          dataSource: { model: 'orders' },
        },
      ],
    });
    const candidate: PageSchemaV3 = {
      ...baseline,
      blocks: [
        {
          ...baseline.blocks[0],
          dataSource: { model: 'payments' },
        },
      ],
    };

    const plan = planStudioAuthoringPatches(baseline, candidate, capabilities);

    expect(plan.unsupported).toEqual([]);
    expect(plan.moves).toEqual([]);
    expect(plan.patches).toEqual([
      {
        blockId: 'table-1',
        propertyPath: '/dataSource',
        operation: 'REPLACE',
        value: { model: 'payments' },
        manifestChecksum: 'table-1',
      },
    ]);
  });

  it('plans stable same-parent reorder operations without treating them as property patches', () => {
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'orders_list',
      blocks: [
        { id: 'table-a', blockType: 'table' },
        { id: 'table-b', blockType: 'table' },
        { id: 'table-c', blockType: 'table' },
      ],
    };
    const candidate: PageSchemaV3 = {
      ...baseline,
      blocks: [baseline.blocks[1], baseline.blocks[2], baseline.blocks[0]],
    };

    const plan = planStudioAuthoringPatches(baseline, candidate, capabilities);

    expect(plan.unsupported).toEqual([]);
    expect(plan.patches).toEqual([]);
    expect(plan.moves).toEqual([
      {
        blockId: 'table-b',
        beforeBlockId: 'table-a',
        manifestChecksum: 'table-1',
      },
      {
        blockId: 'table-c',
        beforeBlockId: 'table-a',
        manifestChecksum: 'table-1',
      },
    ]);
  });

  it('fails closed for structural and undeclared property edits', () => {
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'orders_list',
      blocks: [{ id: 'table-1', blockType: 'table', props: { secret: false } }],
    };
    const candidate: PageSchemaV3 = {
      ...baseline,
      blocks: [
        { id: 'table-2', blockType: 'table' },
        { ...baseline.blocks[0], props: { secret: true } },
      ],
    };

    const plan = planStudioAuthoringPatches(baseline, candidate, capabilities);

    expect(plan.patches).toEqual([]);
    expect(plan.unsupported.join(' ')).toContain('新增或删除区块');
    expect(plan.unsupported.join(' ')).toContain('能力清单未声明');
  });

  it('fails closed instead of translating a cross-parent move', () => {
    const tableA = { id: 'table-a', blockType: 'table' } as const;
    const tableB = { id: 'table-b', blockType: 'table' } as const;
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'composite',
      id: 'operations',
      blocks: [
        { id: 'left', blockType: 'form', blocks: [tableA] },
        { id: 'right', blockType: 'form', blocks: [tableB] },
      ],
    };
    const candidate: PageSchemaV3 = {
      ...baseline,
      blocks: [
        { ...baseline.blocks[0], blocks: [] },
        { ...baseline.blocks[1], blocks: [tableB, tableA] },
      ],
    };

    const plan = planStudioAuthoringPatches(baseline, candidate, capabilities);

    expect(plan.moves).toEqual([]);
    expect(plan.patches).toEqual([]);
    expect(plan.unsupported.join(' ')).toContain('跨父级移动');
  });

  it('rebases disjoint edits and requires an explicit Mine or Latest decision for conflicts', () => {
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'orders_list',
      blocks: [
        {
          id: 'table-1',
          blockType: 'table',
          title: 'Orders',
          dataSource: { model: 'orders' },
        },
      ],
    };
    const mine: PageSchemaV3 = {
      ...baseline,
      blocks: [{ ...baseline.blocks[0], dataSource: { model: 'payments' } }],
    };
    const disjointLatest: PageSchemaV3 = {
      ...baseline,
      blocks: [{ ...baseline.blocks[0], title: 'Latest orders' }],
    };

    const disjoint = buildStudioThreeWayMerge(
      baseline,
      mine,
      disjointLatest,
      capabilities,
    );

    expect(disjoint.conflicts).toEqual([]);
    expect(disjoint.autoMergedChanges).toBe(1);
    expect(disjoint.autoMergedDocument.blocks[0]).toMatchObject({
      title: 'Latest orders',
      dataSource: { model: 'payments' },
    });

    const conflictingLatest: PageSchemaV3 = {
      ...baseline,
      blocks: [{ ...baseline.blocks[0], dataSource: { model: 'refunds' } }],
    };
    const conflict = buildStudioThreeWayMerge(
      baseline,
      mine,
      conflictingLatest,
      capabilities,
    );

    expect(conflict.conflicts).toEqual([
      expect.objectContaining({
        id: 'PROPERTY:table-1:/dataSource',
        baseValue: { model: 'orders' },
        mineValue: { model: 'payments' },
        latestValue: { model: 'refunds' },
      }),
    ]);
    expect(() => resolveStudioThreeWayMerge(conflict, {})).toThrow('1 个三方冲突未裁决');
    expect(
      resolveStudioThreeWayMerge(conflict, {
        'PROPERTY:table-1:/dataSource': 'MINE',
      }).blocks[0],
    ).toMatchObject({ dataSource: { model: 'payments' } });
    expect(
      resolveStudioThreeWayMerge(conflict, {
        'PROPERTY:table-1:/dataSource': 'LATEST',
      }).blocks[0],
    ).toMatchObject({ dataSource: { model: 'refunds' } });
  });

  it('treats divergent stable-ID sibling order as one parent-level conflict', () => {
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'orders_list',
      blocks: [
        { id: 'table-a', blockType: 'table' },
        { id: 'table-b', blockType: 'table' },
        { id: 'table-c', blockType: 'table' },
      ],
    };
    const mine = { ...baseline, blocks: [baseline.blocks[1], baseline.blocks[0], baseline.blocks[2]] };
    const latest = { ...baseline, blocks: [baseline.blocks[0], baseline.blocks[2], baseline.blocks[1]] };

    const merge = buildStudioThreeWayMerge(baseline, mine, latest, capabilities);

    expect(merge.conflicts).toEqual([
      expect.objectContaining({
        id: 'ORDER:$page-root',
        baseValue: ['table-a', 'table-b', 'table-c'],
        mineValue: ['table-b', 'table-a', 'table-c'],
        latestValue: ['table-a', 'table-c', 'table-b'],
      }),
    ]);
    expect(
      resolveStudioThreeWayMerge(merge, { 'ORDER:$page-root': 'MINE' }).blocks.map(
        (block) => block.id,
      ),
    ).toEqual(['table-b', 'table-a', 'table-c']);
  });
});

function property(
  propertyPath: string,
  route: string,
  allowedOperations = ['ADD', 'REPLACE', 'REMOVE'],
) {
  return {
    propertyPath,
    allowedOperations,
    route,
    risk: route === 'HANDOFF_STUDIO' ? 'L3' : 'L1',
    effectTags: route === 'HANDOFF_STUDIO' ? ['DATA_BINDING'] : ['PRESENTATION'],
    reversibility: 'REVERSIBLE',
    protectedSemantic: false,
    rolePreviewRequired: route === 'HANDOFF_STUDIO',
  };
}
