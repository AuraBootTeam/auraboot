import { describe, it, expect } from 'vitest';
import { actionRegistry } from '~/plugins/core-designer/components/studio/services/actions/index';
import { actionRegistry as legacyRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';

describe('studio action services', () => {
  it('re-export the shared action registry instance', () => {
    expect(actionRegistry).toBe(legacyRegistry);
  });
});
