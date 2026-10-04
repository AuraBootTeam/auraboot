import { describe, expect, it } from 'vitest';
import { resolveCommandTargetRecordId } from '../useActionHandler';

describe('command target intent', () => {
  const record = { pid: 'class-context' };
  it('does not promote ambient page or row context into a CREATE target', () => {
    for (const operationType of ['create', 'CREATE']) {
      expect(resolveCommandTargetRecordId({ operationType }, { record }, record, { data: record })).toBeUndefined();
    }
  });
  it('preserves explicit CREATE targets so the execution validator can reject them', () => {
    expect(resolveCommandTargetRecordId({ operationType: 'create', targetRecordPid: 'explicit-target' }, {}, record, {})).toBe('explicit-target');
  });
  it('retains row and explicit target selection for UPDATE and DELETE', () => {
    for (const operationType of ['update', 'delete']) {
      expect(resolveCommandTargetRecordId({ operationType }, {}, record, {})).toBe('class-context');
      expect(resolveCommandTargetRecordId({ operationType, targetRecordPid: 'explicit-target' }, {}, record, {})).toBe('explicit-target');
    }
  });
});
