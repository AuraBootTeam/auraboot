import { describe, expect, it } from 'vitest';

import {
  comparisonStatusFieldClass,
  comparisonStatusForField,
} from '../ReviewDrawerCandidateFields';

/**
 * Review-card comparison pill coloring (2026-07-06 B regression).
 *
 * The candidate card colors each attribute cell from the writer's comparison
 * payload: it looks up `evidence_json.groups.<comparisonGroup>.comparisons[]`,
 * matches the entry by `comparisonKey`, and maps that entry's `status` to an
 * emerald (matched) / amber (mismatch·missing) / neutral (no data) class.
 *
 * The bug shipped to B was the WRITER omitting `groups`/`comparisons` for the
 * v2/L0 engines (fixed in plugins #182 + backend contract test there). THIS
 * test pins the OTHER half — the renderer's mapping — so a renderer change that
 * stops reading the payload, or a class rename, is caught without a browser.
 */
const resistanceField = {
  comparisonGroup: 'parameters',
  comparisonKey: 'resistance_ohms',
};

function evidence(groups: unknown): { bom_me_evidence_json: string } {
  return { bom_me_evidence_json: JSON.stringify({ groups }) };
}

describe('comparisonStatusForField', () => {
  it('reads the matched status from groups.<group>.comparisons by comparisonKey', () => {
    const candidate = evidence({
      parameters: {
        status: 'matched',
        comparisons: [{ key: 'resistance_ohms', status: 'matched' }],
      },
    });
    expect(comparisonStatusForField(candidate, resistanceField)).toBe('matched');
  });

  it('surfaces a mismatch status for the matched key', () => {
    const candidate = evidence({
      parameters: {
        status: 'mismatch',
        comparisons: [{ key: 'resistance_ohms', status: 'mismatch' }],
      },
    });
    expect(comparisonStatusForField(candidate, resistanceField)).toBe('mismatch');
  });

  it('falls back to the flat comparisons[] when the group has none', () => {
    const candidate = {
      bom_me_evidence_json: JSON.stringify({
        comparisons: [{ key: 'resistance_ohms', status: 'matched' }],
      }),
    };
    expect(comparisonStatusForField(candidate, resistanceField)).toBe('matched');
  });

  it('returns undefined when the writer emitted no comparison payload (the B bug)', () => {
    const candidate = {
      bom_me_evidence_json: JSON.stringify({ engine: 'v2', tier: 'REVIEW' }),
    };
    expect(comparisonStatusForField(candidate, resistanceField)).toBeUndefined();
  });

  it('matches by label when the comparison entry has no key', () => {
    const candidate = evidence({
      parameters: { comparisons: [{ label: 'resistance_ohms', status: 'matched' }] },
    });
    expect(comparisonStatusForField(candidate, resistanceField)).toBe('matched');
  });
});

describe('comparisonStatusFieldClass', () => {
  it('colors matched cells emerald', () => {
    expect(comparisonStatusFieldClass('matched')).toContain('emerald');
  });

  it('colors mismatch and every missing variant amber', () => {
    for (const status of [
      'mismatch',
      'missing',
      'missing_source',
      'missing_candidate',
      'missing_both',
    ]) {
      expect(comparisonStatusFieldClass(status), `status=${status}`).toContain('amber');
    }
  });

  it('renders no color when there is no comparison status (undefined / empty)', () => {
    expect(comparisonStatusFieldClass(undefined)).toBe('');
    expect(comparisonStatusFieldClass('')).toBe('');
  });

  it('end-to-end: a matched resistance comparison paints the cell emerald', () => {
    const candidate = evidence({
      parameters: { comparisons: [{ key: 'resistance_ohms', status: 'matched' }] },
    });
    const status = comparisonStatusForField(candidate, resistanceField);
    expect(comparisonStatusFieldClass(status)).toContain('emerald');
  });
});
