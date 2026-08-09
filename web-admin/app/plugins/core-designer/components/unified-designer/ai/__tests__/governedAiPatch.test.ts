import { describe, expect, it } from 'vitest';
import type { CapabilityRegistry } from '~/framework/meta/authoring/types';
import type { PageSchemaV3 } from '../../types';
import {
  buildGovernedAiPatchPrompt,
  collectGovernedTargets,
  parseGovernedAiPatchResponse,
} from '../governedAiPatch';

const document: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'list',
  id: 'orders',
  blocks: [
    {
      id: 'table-1',
      blockType: 'table',
      title: 'Orders',
      props: { density: 'normal' },
      dataSource: { model: 'orders' },
    },
  ],
};

const capabilities: CapabilityRegistry = {
  checksum: 'registry-checksum',
  manifests: [
    {
      blockType: 'table',
      pluginCode: 'core.designer',
      pluginVersion: '1',
      manifestVersion: '1',
      checksum: 'table-checksum',
      properties: {
        '/props/density': property('/props/density', ['ADD', 'REPLACE', 'REMOVE']),
        '/dataSource': property('/dataSource', ['ADD', 'REPLACE', 'REMOVE']),
        '/$structure/order': property('/$structure/order', ['MOVE']),
      },
    },
  ],
};

describe('governedAiPatch', () => {
  it('limits the prompt and parser to declared property targets with server manifests', () => {
    const targets = collectGovernedTargets(document, capabilities);
    const prompt = buildGovernedAiPatchPrompt({ document, capabilities });
    const parsed = parseGovernedAiPatchResponse(
      '{"items":[{"blockId":"table-1","propertyPath":"/props/density",'
        + '"operation":"REPLACE","value":"compact"}]}',
      { document, capabilities },
    );

    expect(targets.map((target) => target.propertyPath)).toEqual([
      '/dataSource',
      '/props/density',
    ]);
    expect(prompt).toContain('You do not edit the page');
    expect(prompt).toContain('table-1');
    expect(prompt).not.toContain('/$structure/order');
    expect(parsed).toEqual([
      {
        blockId: 'table-1',
        propertyPath: '/props/density',
        operation: 'REPLACE',
        value: 'compact',
        manifestChecksum: 'table-checksum',
      },
    ]);
  });

  it('fails closed for prose, extra keys, unknown targets, moves, and duplicates', () => {
    const context = { document, capabilities };
    expect(() =>
      parseGovernedAiPatchResponse(
        '```json\n{"items":[{"blockId":"table-1","propertyPath":"/props/density",'
          + '"operation":"REPLACE","value":"compact"}]}\n```',
        context,
      ),
    ).toThrow('without prose or markdown');
    expect(() =>
      parseGovernedAiPatchResponse(
        '{"items":[{"blockId":"table-1","propertyPath":"/props/density",'
          + '"operation":"REPLACE","value":"compact","reason":"trust me"}]}',
        context,
      ),
    ).toThrow('undeclared keys');
    expect(() =>
      parseGovernedAiPatchResponse(
        '{"items":[{"blockId":"table-1","propertyPath":"/secret",'
          + '"operation":"REPLACE","value":true}]}',
        context,
      ),
    ).toThrow('unknown typed target');
    expect(() =>
      parseGovernedAiPatchResponse(
        '{"items":[{"blockId":"table-1","propertyPath":"/props/density",'
          + '"operation":"MOVE"}]}',
        context,
      ),
    ).toThrow('operation must be');
    expect(() =>
      parseGovernedAiPatchResponse(
        '{"items":['
          + '{"blockId":"table-1","propertyPath":"/props/density","operation":"REMOVE"},'
          + '{"blockId":"table-1","propertyPath":"/props/density","operation":"REMOVE"}]}',
        context,
      ),
    ).toThrow('duplicate typed target');
  });
});

function property(propertyPath: string, allowedOperations: string[]) {
  return {
    propertyPath,
    allowedOperations,
    route: 'HANDOFF_STUDIO',
    risk: 'L3',
    effectTags: ['DATA_BINDING'],
    reversibility: 'REVERSIBLE',
    protectedSemantic: false,
    rolePreviewRequired: false,
  };
}
