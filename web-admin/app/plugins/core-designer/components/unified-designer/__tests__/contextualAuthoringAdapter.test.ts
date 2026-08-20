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
      blockType: '$page',
      pluginCode: 'core.designer',
      pluginVersion: '1',
      manifestVersion: '1',
      checksum: 'page-1',
      properties: {
        '/$page/kind': property('/$page/kind', 'HANDOFF_STUDIO'),
      },
    },
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
        '/$structure/create': property('/$structure/create', 'HANDOFF_STUDIO', ['ADD']),
        '/$structure/remove': property('/$structure/remove', 'HANDOFF_STUDIO', ['REMOVE']),
        '/$structure/parent': property('/$structure/parent', 'HANDOFF_STUDIO', ['MOVE']),
      },
    },
  ],
};

const pageConstructionCapabilities: CapabilityRegistry = {
  checksum: 'registry-construction',
  manifests: [
    {
      blockType: 'table',
      pluginCode: 'core.designer',
      pluginVersion: '1',
      manifestVersion: '1',
      checksum: 'table-create',
      properties: {
        '/$structure/create': property('/$structure/create', 'HANDOFF_STUDIO', ['ADD']),
      },
    },
    {
      blockType: 'column',
      pluginCode: 'core.designer',
      pluginVersion: '1',
      manifestVersion: '1',
      checksum: 'column-create',
      properties: {
        '/field': property('/field', 'HANDOFF_STUDIO'),
        '/props/label': property('/props/label', 'INLINE'),
        '/$structure/create': property('/$structure/create', 'HANDOFF_STUDIO', ['ADD']),
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

  it('preserves a recursive V4 authoring snapshot instead of synthesizing a phantom root', () => {
    const baseline = authoringSnapshotToPageSchemaV3({
      pid: 'page-1',
      pageKey: 'orders_list',
      schemaVersion: 4,
      kind: 'list',
      blocks: [{ id: 'server-list-root', blockType: 'list', blocks: [] }],
    });

    expect(baseline.blocks).toEqual([
      { id: 'server-list-root', blockType: 'list', blocks: [] },
    ]);
  });

  it('plans a compatible root kind switch as one server-owned page operation', () => {
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'orders',
      blocks: [{ id: 'stable-root', blockType: 'list', blocks: [] }],
    };
    const candidate: PageSchemaV3 = {
      ...baseline,
      kind: 'detail',
      blocks: [{ ...baseline.blocks[0], blockType: 'detail' }],
    };

    const plan = planStudioAuthoringPatches(baseline, candidate, capabilities);

    expect(plan.unsupported).toEqual([]);
    expect(plan.kindSwitch).toEqual({ targetKind: 'detail', manifestChecksum: 'page-1' });
    expect(plan.creates).toEqual([]);
    expect(plan.removes).toEqual([]);
    expect(plan.patches).toEqual([]);
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

  it('translates declared creation but still fails closed for undeclared property edits', () => {
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
    expect(plan.creates).toEqual([
      {
        blockId: 'table-2',
        blockType: 'table',
        parentBlockId: null,
        beforeBlockId: null,
        manifestChecksum: 'table-1',
      },
    ]);
    expect(plan.unsupported.join(' ')).toContain('能力清单未声明');
  });

  it('persists declared initial properties after creating the server-owned block shell', () => {
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'composite',
      id: 'operations',
      blocks: [],
    };
    const candidate: PageSchemaV3 = {
      ...baseline,
      blocks: [{ id: 'table-1', blockType: 'table', title: 'New table' }],
    };

    const plan = planStudioAuthoringPatches(baseline, candidate, capabilities);

    expect(plan.unsupported).toEqual([]);
    expect(plan.creates).toHaveLength(1);
    expect(plan.patches).toEqual([
      {
        blockId: 'table-1',
        propertyPath: '/title',
        operation: 'ADD',
        value: 'New table',
        manifestChecksum: 'table-1',
      },
    ]);
  });

  it('plans a copied block as a fresh create plus server-declared source lineage', () => {
    const copyCapabilities: CapabilityRegistry = {
      checksum: 'registry-copy',
      manifests: [{
        blockType: 'description',
        pluginCode: 'core.designer',
        pluginVersion: '1',
        manifestVersion: '1',
        checksum: 'description-copy',
        properties: {
          '/props/content': property('/props/content', 'INLINE'),
          '/extension/authoringCopyLineage': property(
            '/extension/authoringCopyLineage',
            'HANDOFF_STUDIO',
            ['ADD'],
          ),
          '/$structure/create': property('/$structure/create', 'HANDOFF_STUDIO', ['ADD']),
        },
      }],
    };
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'composite',
      id: 'copy-page',
      blocks: [],
    };
    const candidate: PageSchemaV3 = {
      ...baseline,
      blocks: [{
        id: 'description-source-copy',
        blockType: 'description',
        props: { content: 'Copied content' },
        extension: { authoringCopyLineage: { sourceBlockId: 'description-source' } },
      }],
    };

    const plan = planStudioAuthoringPatches(baseline, candidate, copyCapabilities);

    expect(plan.unsupported).toEqual([]);
    expect(plan.creates).toEqual([{
      blockId: 'description-source-copy',
      blockType: 'description',
      parentBlockId: null,
      beforeBlockId: null,
      manifestChecksum: 'description-copy',
    }]);
    expect(plan.patches).toEqual(expect.arrayContaining([
      expect.objectContaining({
        blockId: 'description-source-copy',
        propertyPath: '/extension/authoringCopyLineage',
        operation: 'ADD',
        value: { sourceBlockId: 'description-source' },
      }),
    ]));
  });

  it('plans a nested table and model-backed column as governed structure plus typed patches', () => {
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'production_exception_list',
      modelCode: 'production_exception',
      blocks: [{ id: 'list-root', blockType: 'list', blocks: [] }],
    };
    const candidate: PageSchemaV3 = {
      ...baseline,
      blocks: [{
        ...baseline.blocks[0],
        blocks: [{
          id: 'table-1',
          blockType: 'table',
          blocks: [{
            id: 'column-exception-no',
            blockType: 'column',
            props: { label: 'Exception No.' },
            // Deliberately after props: JSON object insertion order is not semantic,
            // and server manifest maps may arrive in a different key order.
            field: 'exception_no',
          }],
        }],
      }],
    };

    const plan = planStudioAuthoringPatches(
      baseline,
      candidate,
      pageConstructionCapabilities,
    );

    expect(plan.unsupported).toEqual([]);
    expect(plan.creates.map(({ blockId, parentBlockId }) => ({ blockId, parentBlockId }))).toEqual([
      { blockId: 'table-1', parentBlockId: 'list-root' },
      { blockId: 'column-exception-no', parentBlockId: 'table-1' },
    ]);
    expect(plan.patches).toEqual([
      expect.objectContaining({
        blockId: 'column-exception-no',
        propertyPath: '/field',
        value: 'exception_no',
      }),
      expect.objectContaining({
        blockId: 'column-exception-no',
        propertyPath: '/props/label',
        value: 'Exception No.',
      }),
    ]);
  });

  it('translates a declared cross-parent move as a Studio relocation', () => {
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

    expect(plan.relocations).toEqual([
      {
        blockId: 'table-a',
        targetParentBlockId: 'right',
        beforeBlockId: null,
        manifestChecksum: 'table-1',
      },
    ]);
    expect(plan.moves).toEqual([]);
    expect(plan.patches).toEqual([]);
    expect(plan.unsupported).toEqual([]);
  });

  it('collapses subtree deletion to one governed removal item', () => {
    const baseline: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'composite',
      id: 'operations',
      blocks: [{
        id: 'form-root',
        blockType: 'form',
        blocks: [{
          id: 'table-a',
          blockType: 'table',
          blocks: [{ id: 'table-child', blockType: 'table' }],
        }],
      }],
    };
    const candidate: PageSchemaV3 = {
      ...baseline,
      blocks: [{ ...baseline.blocks[0], blocks: [] }],
    };

    const plan = planStudioAuthoringPatches(baseline, candidate, capabilities);

    expect(plan.removes).toEqual([{ blockId: 'table-a', manifestChecksum: 'table-1' }]);
    expect(plan.unsupported).toEqual([]);
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
