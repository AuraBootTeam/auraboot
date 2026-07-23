import { describe, expect, it } from 'vitest';
import { listBundledSkills, resolveBundleDir } from '../../src/skills/install.js';

describe('the shipped skill bundle', () => {
  it('resolves to a real directory', () => {
    expect(resolveBundleDir()).toMatch(/skills$/);
  });

  it('contains exactly the 6 documented end-user skills', () => {
    const names = listBundledSkills(resolveBundleDir())
      .map((s) => s.name)
      .sort();
    expect(names).toEqual([
      'auraboot-data-modeling',
      'auraboot-dsl-gitops',
      'auraboot-permissions',
      'auraboot-runtime-ops',
      'auraboot-ui-builder',
      'auraboot-workflow',
    ]);
  });
});
