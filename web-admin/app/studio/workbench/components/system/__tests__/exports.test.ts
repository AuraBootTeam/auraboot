import { describe, it, expect } from 'vitest';
import {
  VersionPanel,
  ConflictResolver,
  DesignerWorkflow,
  MultiSelectManager,
} from '~/studio/workbench/components/system/index';

describe('system component exports', () => {
  it('provides VersionPanel', () => {
    expect(VersionPanel).toBeDefined();
  });

  it('provides ConflictResolver', () => {
    expect(ConflictResolver).toBeDefined();
  });

  it('provides DesignerWorkflow', () => {
    expect(DesignerWorkflow).toBeDefined();
  });

  it('provides MultiSelectManager', () => {
    expect(MultiSelectManager).toBeDefined();
  });
});
