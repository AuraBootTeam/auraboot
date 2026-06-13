import { describe, expect, it } from 'vitest';
import { getEnumCodes, getFallbackRegistry } from '../dslRegistryService';

describe('dslRegistryService fallback registry', () => {
  it('includes workbench block types for offline validation and designer startup', () => {
    const blockTypes = getEnumCodes(getFallbackRegistry(), 'BlockType');

    expect(blockTypes).toContain('metric-strip');
    expect(blockTypes).toContain('record-inspector');
    expect(blockTypes).toContain('candidate-list');
    expect(blockTypes).toContain('workbench-action-bar');
    expect(blockTypes).toContain('evidence-panel');
    expect(blockTypes).toContain('gerber-viewer');
    expect(blockTypes).toContain('artifact-timeline');
  });
});
