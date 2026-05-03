import { describe, expect, it } from 'vitest';
import { describeCommandPipelineTool } from '../../../../src/mcp/tools/read/describeCommandPipeline.js';

describe('describeCommandPipelineTool', () => {
  it('declares correct identity + read-only annotations', () => {
    const tool = describeCommandPipelineTool();
    expect(tool.name).toBe('describe_command_pipeline');
    expect(tool.annotations).toMatchObject({ readOnlyHint: true, idempotentHint: true });
  });

  it('returns parseable JSON content with no HTTP call', async () => {
    const tool = describeCommandPipelineTool();
    const result = await tool.handler({});
    expect(result.isError).toBeUndefined();
    expect(() => JSON.parse(result.content[0].text)).not.toThrow();
  });

  it('matches canonical CommandStage.java contract', async () => {
    const tool = describeCommandPipelineTool();
    const result = await tool.handler({});
    const data = JSON.parse(result.content[0].text);

    // Must mirror CommandStage.TOTAL_TRANSACTIONAL_STAGES = 20.
    expect(data.totalTransactionalStages).toBe(20);
    expect(data.totalAfterCommitStages).toBe(4);

    // 19 declared in-tx stages (Stage 15 is reserved/merged).
    expect(data.inTransaction).toHaveLength(19);
    expect(data.afterCommit).toHaveLength(4);

    // Anchor stages we rely on heavily downstream.
    const stages = data.inTransaction.map((s: any) => [s.stage, s.name]);
    expect(stages).toContainEqual([1, 'load']);
    expect(stages).toContainEqual([14, 'handler']);
    expect(stages).toContainEqual([16, 'side_effect']);
    expect(stages).toContainEqual([20, 'post_invariant']);

    // Stage 15 must NOT appear (per CommandStage.java).
    const hasStage15 = data.inTransaction.some((s: any) => s.stage === 15);
    expect(hasStage15).toBe(false);

    // After-commit numbers are 21..24.
    const acStages = data.afterCommit.map((s: any) => s.stage);
    expect(acStages).toEqual([21, 22, 23, 24]);
  });

  it('includes guidance notes for LLM consumers', async () => {
    const tool = describeCommandPipelineTool();
    const result = await tool.handler({});
    const data = JSON.parse(result.content[0].text);
    expect(Array.isArray(data.notes)).toBe(true);
    expect(data.notes.length).toBeGreaterThan(0);
  });
});
