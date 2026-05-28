// web-admin/app/flow-designer-sdk/__tests__/index.exports.test.ts
// Guards that G7/G8 are part of the SDK public surface. Failing this test
// means a breaking change to consumers (bpmn-designer, automation-designer).
import { describe, it, expect } from 'vitest';
import * as SDK from '../index';

describe('flow-designer-sdk public surface (G7+G8)', () => {
  it('exports useNodeNeighbors (G7)', () => {
    expect(typeof SDK.useNodeNeighbors).toBe('function');
  });

  it('exports useNodeMonitorStatus (G8)', () => {
    expect(typeof SDK.useNodeMonitorStatus).toBe('function');
  });
});
