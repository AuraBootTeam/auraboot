import { describe, expect, it } from 'vitest';
import {
  buildCommandTargetParams,
  getCommandResultRecordPid,
  getLegacyCompatibleRecordPid,
  getPublicRecordKey,
  getPublicRecordPid,
  toPublicRecordPid,
} from '../publicRecordId';

describe('publicRecordId utilities', () => {
  it('normalizes non-empty pid values', () => {
    expect(toPublicRecordPid(' rec-pid ')).toBe('rec-pid');
    expect(toPublicRecordPid(123)).toBe('123');
    expect(toPublicRecordPid('')).toBeUndefined();
    expect(toPublicRecordPid(null)).toBeUndefined();
  });

  it('uses public pid and ignores legacy id fields', () => {
    const record = { pid: 'public-pid', id: 1001 };

    expect(getPublicRecordPid(record)).toBe('public-pid');
    expect(getLegacyCompatibleRecordPid(record)).toBe('public-pid');
    expect(getLegacyCompatibleRecordPid({ id: 1001 })).toBeUndefined();
    expect(getPublicRecordPid({ id: 1001 })).toBeUndefined();
  });

  it('uses configured public key fields before public pid', () => {
    expect(getPublicRecordKey({ code: 'tree-1', pid: 'pid-1' }, undefined, 'code')).toBe('tree-1');
    expect(getPublicRecordKey({ pid: 'pid-1' }, 'fallback', 'code')).toBe('pid-1');
    expect(getPublicRecordKey({}, 'fallback', 'code')).toBeUndefined();
  });

  it('builds command params with only targetRecordPid', () => {
    expect(buildCommandTargetParams(' public-pid ')).toEqual({ targetRecordPid: 'public-pid' });
    expect(buildCommandTargetParams('')).toEqual({});
  });
});

describe('command-result public record identity', () => {
  it('uses explicit public recordPid before a record payload pid', () => {
    expect(getCommandResultRecordPid({ recordPid: 'record-1', pid: 'other-record' })).toBe('record-1');
    expect(getCommandResultRecordPid({ pid: 'record-2' })).toBe('record-2');
  });

  it('unwraps nested command envelopes without changing public identity', () => {
    expect(getCommandResultRecordPid({ data: { data: { recordPid: 'record-3' } } })).toBe('record-3');
  });

  it('never accepts an internal id or legacy recordId as an attachment target', () => {
    expect(getCommandResultRecordPid({ recordId: 'legacy-id' })).toBeUndefined();
    expect(getCommandResultRecordPid({ id: '9223372036854775807' })).toBeUndefined();
    expect(getCommandResultRecordPid({ data: { recordId: 'legacy-nested' } })).toBeUndefined();
  });

  it('ignores a legacy identity when a public pid is present', () => {
    expect(getCommandResultRecordPid({ recordId: 'legacy-id', pid: 'public-record' })).toBe('public-record');
  });

  it('normalizes nonempty public strings and searches beyond invalid root fields', () => {
    expect(getCommandResultRecordPid({ recordPid: '  record-4  ' })).toBe('record-4');
    expect(getCommandResultRecordPid({ recordPid: '  ', pid: 'record-5' })).toBe('record-5');
    expect(getCommandResultRecordPid({ recordPid: 42, pid: 'record-6' })).toBe('record-6');
    expect(getCommandResultRecordPid({ pid: '', data: { pid: 'record-7' } })).toBe('record-7');
  });

  it('rejects non-string public values and absent result objects', () => {
    for (const value of [null, undefined, '', 42, [], { recordPid: 42 }, { pid: 42 }, { pid: '' }]) {
      expect(getCommandResultRecordPid(value)).toBeUndefined();
    }
  });
});

it('does not loop on a cyclic in-memory command result', () => {
  const result: { data?: unknown } = {};
  result.data = result;
  expect(getCommandResultRecordPid(result)).toBeUndefined();
});
