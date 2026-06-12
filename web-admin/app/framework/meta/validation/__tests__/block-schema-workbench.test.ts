import { describe, expect, it } from 'vitest';
import { BLOCK_TYPES, blockTypeEnum } from '../schemas/block.schema';

describe('block schema workbench block types', () => {
  it('allows workbench block types in the static fallback enum', () => {
    expect(BLOCK_TYPES).toContain('metric-strip');
    expect(BLOCK_TYPES).toContain('record-inspector');
    expect(BLOCK_TYPES).toContain('candidate-list');
    expect(BLOCK_TYPES).toContain('workbench-action-bar');
    expect(BLOCK_TYPES).toContain('evidence-panel');
    expect(BLOCK_TYPES).toContain('artifact-timeline');
    expect(BLOCK_TYPES).toContain('review-drawer');
    expect(BLOCK_TYPES).toContain('status-banner');
    expect(BLOCK_TYPES).toContain('detail-section');
    expect(BLOCK_TYPES).toContain('text');
    expect(BLOCK_TYPES).toContain('chart-card');
    expect(BLOCK_TYPES).toContain('selection-info');

    expect(blockTypeEnum.safeParse('metric-strip').success).toBe(true);
    expect(blockTypeEnum.safeParse('record-inspector').success).toBe(true);
    expect(blockTypeEnum.safeParse('candidate-list').success).toBe(true);
    expect(blockTypeEnum.safeParse('workbench-action-bar').success).toBe(true);
    expect(blockTypeEnum.safeParse('evidence-panel').success).toBe(true);
    expect(blockTypeEnum.safeParse('artifact-timeline').success).toBe(true);
    expect(blockTypeEnum.safeParse('review-drawer').success).toBe(true);
    expect(blockTypeEnum.safeParse('status-banner').success).toBe(true);
    expect(blockTypeEnum.safeParse('detail-section').success).toBe(true);
    expect(blockTypeEnum.safeParse('text').success).toBe(true);
    expect(blockTypeEnum.safeParse('chart-card').success).toBe(true);
    expect(blockTypeEnum.safeParse('selection-info').success).toBe(true);
  });
});
