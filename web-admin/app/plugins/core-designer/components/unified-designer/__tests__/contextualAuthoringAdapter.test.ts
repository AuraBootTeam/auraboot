import { describe, expect, it } from 'vitest';
import type { CapabilityRegistry } from '~/framework/meta/authoring/types';
import type { PageSchemaV3 } from '../types';
import {
  authoringSnapshotToPageSchemaV3,
  planStudioAuthoringPatches,
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
