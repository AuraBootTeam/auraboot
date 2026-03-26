import { describe, it, expect } from 'vitest';
import * as adapter from '~/studio/services/runtime/SchemaRuntimeAdapter';
import { actionRegistry } from '~/meta/runtime/actions/ActionRegistry';

describe('SchemaRuntimeAdapter exports', () => {
  it('exposes the shared actionRegistry instance', () => {
    expect(adapter.actionRegistry).toBe(actionRegistry);
  });

  it('provides the schema converter entrypoint', () => {
    expect(typeof adapter.convertSchemaToUnified).toBe('function');
  });
});
