import { describe, expect, it } from 'vitest';
import { domainLevels, selectDomainLevel } from '../domainLevels';
import type { Capability } from '../types';

const levels = ['read', 'write', 'manage'].map((name, index) => ({
  code: `bom.cap.${name}`,
  tier: ['viewer', 'editor', 'admin'][index],
  includes: ['bom.project.read', 'bom.convert.execute', 'bom.structure.release'].slice(
    0,
    index + 1,
  ),
  conventionDerived: false,
})) as Capability[];

describe('domain permission levels', () => {
  it('recognizes only a complete strictly nested triple', () => {
    const groups = [{ group: 'BOM', capabilities: levels }];
    expect(domainLevels(groups, 'bom.cap.write')).toEqual(levels);
    expect(
      domainLevels([{ group: 'BOM', capabilities: levels.slice(0, 2) }], 'bom.cap.read'),
    ).toEqual([]);
    expect(
      domainLevels(
        [
          {
            group: 'BOM',
            capabilities: [levels[0], { ...levels[1], includes: ['write'] }, levels[2]],
          },
        ],
        'bom.cap.read',
      ),
    ).toEqual([]);
  });
  it('downgrades manage to read and explicitly replaces legacy write and release grants', () => {
    expect(
      selectDomainLevel(
        ['qo.cap.write', ...levels.map((cap) => cap.code)],
        [],
        levels,
        'bom.cap.read',
      ),
    ).toEqual({
      selected: ['qo.cap.write', 'bom.cap.read'],
      revoked: ['bom.cap.write', 'bom.cap.manage'],
    });
  });
  it('includes lower levels without requiring additional clicks; clearing removes all domain levels', () => {
    const managed = selectDomainLevel(
      ['qo.cap.read'],
      ['bom.cap.manage'],
      levels,
      'bom.cap.manage',
    );
    expect(managed).toEqual({
      selected: ['qo.cap.read', 'bom.cap.read', 'bom.cap.write', 'bom.cap.manage'],
      revoked: [],
    });
    expect(selectDomainLevel(managed.selected, [], levels, null)).toEqual({
      selected: ['qo.cap.read'],
      revoked: levels.map((cap) => cap.code),
    });
  });
});
