import { describe, it, expect } from 'vitest';
import { actionRegistry } from '~/studio/services/actions/index';
import { actionRegistry as legacyRegistry } from '~/meta/runtime/actions/ActionRegistry';

describe('studio action services', () => {
  it('re-export the shared action registry instance', () => {
    expect(actionRegistry).toBe(legacyRegistry);
  });
});
