import { describe, it, expect } from 'vitest';
import {
  ConflictResolver,
  MultiSelectManager,
} from '~/plugins/core-designer/components/studio/workbench/components/system/index';

describe('system component exports', () => {
  it('provides ConflictResolver', () => {
    expect(ConflictResolver).toBeDefined();
  });

  it('provides MultiSelectManager', () => {
    expect(MultiSelectManager).toBeDefined();
  });
});
