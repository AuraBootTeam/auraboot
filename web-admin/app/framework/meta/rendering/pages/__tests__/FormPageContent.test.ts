import { describe, expect, it } from 'vitest';
import { mergeLoadedRecordWithDirtyFields } from '../FormPageContent';

describe('mergeLoadedRecordWithDirtyFields', () => {
  it('keeps user-edited fields when a late edit-record fetch resolves', () => {
    const loadedRecord = {
      pid: 'record-1',
      templateVersion: 'REV-1',
      structureRule: '{"root":"PCBA"}',
      enabled: true,
    };
    const currentData = {
      pid: 'record-1',
      templateVersion: 'REV-2',
      structureRule: '{"root":"PCBA"}',
      enabled: true,
    };

    expect(
      mergeLoadedRecordWithDirtyFields(loadedRecord, currentData, new Set(['templateVersion'])),
    ).toEqual({
      pid: 'record-1',
      templateVersion: 'REV-2',
      structureRule: '{"root":"PCBA"}',
      enabled: true,
    });
  });

  it('uses loaded data unchanged when no field has been edited', () => {
    const loadedRecord = { pid: 'record-1', revision: 'A0' };

    expect(mergeLoadedRecordWithDirtyFields(loadedRecord, { revision: 'A1' }, new Set())).toBe(
      loadedRecord,
    );
  });
});
