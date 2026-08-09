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
});

function property(propertyPath: string, route: string) {
  return {
    propertyPath,
    allowedOperations: ['ADD', 'REPLACE', 'REMOVE'],
    route,
    risk: route === 'HANDOFF_STUDIO' ? 'L3' : 'L1',
    effectTags: route === 'HANDOFF_STUDIO' ? ['DATA_BINDING'] : ['PRESENTATION'],
    reversibility: 'REVERSIBLE',
    protectedSemantic: false,
    rolePreviewRequired: route === 'HANDOFF_STUDIO',
  };
}
