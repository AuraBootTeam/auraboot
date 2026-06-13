/**
 * aiLockedFields.test.ts
 *
 * The AI field-lock feature (D5): a form field marked `props.aiLocked: true`
 * must never be overwritten by an AI fill. These pure helpers derive the set of
 * locked field codes from a DSL form schema and partition an AI-returned field
 * map into the values that may be applied vs. the codes that must be skipped.
 */
import { describe, it, expect } from 'vitest';
import { collectAiLockedFieldCodes, partitionFieldsByLock } from '../aiLockedFields';

describe('collectAiLockedFieldCodes', () => {
  it('collects field codes where props.aiLocked is true, nested in the block tree', () => {
    const schema = {
      kind: 'form',
      blocks: [
        {
          blockType: 'form-section',
          blocks: [
            { blockType: 'field', field: 'wd_req_reason', props: { aiLocked: true } },
            { blockType: 'field', field: 'wd_req_type', props: { aiLocked: false } },
            { blockType: 'field', field: 'wd_req_days' },
          ],
        },
      ],
    };
    expect(collectAiLockedFieldCodes(schema)).toEqual(['wd_req_reason']);
  });

  it('returns an empty array when no field is locked', () => {
    expect(collectAiLockedFieldCodes({ blocks: [{ field: 'a', props: {} }] })).toEqual([]);
  });

  it('tolerates null / undefined / non-object schemas', () => {
    expect(collectAiLockedFieldCodes(null)).toEqual([]);
    expect(collectAiLockedFieldCodes(undefined)).toEqual([]);
    expect(collectAiLockedFieldCodes('nope')).toEqual([]);
  });

  it('dedupes a field code that appears locked more than once', () => {
    const schema = {
      blocks: [
        { field: 'reason', props: { aiLocked: true } },
        { field: 'reason', props: { aiLocked: true } },
      ],
    };
    expect(collectAiLockedFieldCodes(schema)).toEqual(['reason']);
  });

  it('ignores aiLocked on a node that has no field code (e.g. a section)', () => {
    const schema = { blocks: [{ blockType: 'section', props: { aiLocked: true }, blocks: [] }] };
    expect(collectAiLockedFieldCodes(schema)).toEqual([]);
  });
});

describe('partitionFieldsByLock', () => {
  it('splits AI-returned values into applied (unlocked) and skipped (locked)', () => {
    const { applied, skipped } = partitionFieldsByLock(
      { wd_req_reason: 'family matter', wd_req_type: 'annual', wd_req_days: 2 },
      ['wd_req_reason'],
    );
    expect(applied).toEqual({ wd_req_type: 'annual', wd_req_days: 2 });
    expect(skipped).toEqual(['wd_req_reason']);
  });

  it('applies everything when the locked set is empty', () => {
    const { applied, skipped } = partitionFieldsByLock({ a: 1 }, []);
    expect(applied).toEqual({ a: 1 });
    expect(skipped).toEqual([]);
  });

  it('only reports a skipped code when that field was actually present', () => {
    const { applied, skipped } = partitionFieldsByLock({ a: 1 }, ['b']);
    expect(applied).toEqual({ a: 1 });
    expect(skipped).toEqual([]);
  });
});
