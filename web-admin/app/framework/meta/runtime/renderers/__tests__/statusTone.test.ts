/**
 * resolveStatusTone maps arbitrary status/tag color names to the 5 canonical
 * semantic tones (standard §1.3). Status/tag cells render as 色点 + 文字 (a
 * semantic-colored dot + label), not a filled pill (§3).
 */
import { describe, it, expect } from 'vitest';
import { resolveStatusTone, STATUS_TONE_DOT } from '~/framework/meta/runtime/renderers/statusTone';

describe('resolveStatusTone', () => {
  it('maps success-family → green', () => {
    for (const c of ['success', 'green', 'done', 'completed', 'normal', 'pass'])
      expect(resolveStatusTone(c)).toBe('green');
  });
  it('maps error-family → red', () => {
    for (const c of ['error', 'red', 'danger', 'failed', 'rejected', 'overdue'])
      expect(resolveStatusTone(c)).toBe('red');
  });
  it('maps warning-family → amber', () => {
    for (const c of ['warning', 'amber', 'yellow', 'orange', 'pending'])
      expect(resolveStatusTone(c)).toBe('amber');
  });
  it('maps info/processing-family → blue', () => {
    for (const c of ['info', 'blue', 'processing', 'in_progress', 'active'])
      expect(resolveStatusTone(c)).toBe('blue');
  });
  it('maps neutral/unknown → gray', () => {
    for (const c of ['gray', 'grey', 'default', 'neutral', 'draft', 'closed', 'zzz', '', undefined])
      expect(resolveStatusTone(c as string)).toBe('gray');
  });
  it('is case-insensitive', () => {
    expect(resolveStatusTone('SUCCESS')).toBe('green');
    expect(resolveStatusTone('In_Progress')).toBe('blue');
  });
  it('maps hex color values to the nearest tone by hue (dicts often store hex)', () => {
    expect(resolveStatusTone('#10b981')).toBe('green'); // emerald
    expect(resolveStatusTone('#22c55e')).toBe('green');
    expect(resolveStatusTone('#ef4444')).toBe('red');
    expect(resolveStatusTone('#dc2626')).toBe('red');
    expect(resolveStatusTone('#f59e0b')).toBe('amber'); // amber
    expect(resolveStatusTone('#eab308')).toBe('amber'); // yellow
    expect(resolveStatusTone('#3b82f6')).toBe('blue');
    expect(resolveStatusTone('#2563eb')).toBe('blue');
    expect(resolveStatusTone('#9ca3af')).toBe('gray'); // low-saturation → gray
    expect(resolveStatusTone('#6b7280')).toBe('gray');
    expect(resolveStatusTone('10b981')).toBe('green'); // no leading #
    expect(resolveStatusTone('#0f0')).toBe('green'); // 3-digit
  });
  it('exposes a semantic dot bg class per tone', () => {
    expect(STATUS_TONE_DOT.green).toBe('bg-status-green');
    expect(STATUS_TONE_DOT.red).toBe('bg-status-red');
    expect(STATUS_TONE_DOT.amber).toBe('bg-status-amber');
    expect(STATUS_TONE_DOT.blue).toBe('bg-status-blue');
    expect(STATUS_TONE_DOT.gray).toBe('bg-status-gray');
  });
});
