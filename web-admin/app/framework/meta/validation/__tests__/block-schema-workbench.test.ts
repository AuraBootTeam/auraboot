import { describe, expect, it } from 'vitest';
import { BLOCK_TYPES, blockTypeEnum } from '../schemas/block.schema';

describe('block schema workbench block types', () => {
  it('allows workbench block types in the static fallback enum', () => {
    expect(BLOCK_TYPES).toContain('metric-strip');
    expect(BLOCK_TYPES).toContain('record-inspector');
    expect(BLOCK_TYPES).toContain('candidate-list');
    expect(BLOCK_TYPES).toContain('workbench-action-bar');

    expect(blockTypeEnum.safeParse('metric-strip').success).toBe(true);
    expect(blockTypeEnum.safeParse('record-inspector').success).toBe(true);
    expect(blockTypeEnum.safeParse('candidate-list').success).toBe(true);
    expect(blockTypeEnum.safeParse('workbench-action-bar').success).toBe(true);
  });
});
