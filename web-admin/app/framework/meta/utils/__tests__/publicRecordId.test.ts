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

  it('reads canonical PIDs from synchronous and nested command results', () => {
    expect(getCommandResultRecordPid({ recordPid: 'record-pid', pid: 'other-pid' })).toBe('record-pid');
    expect(getCommandResultRecordPid({ pid: 'public-pid' })).toBe('public-pid');
    expect(getCommandResultRecordPid({ data: { data: { recordPid: 'nested-pid' } } })).toBe('nested-pid');
  });

  it('rejects legacy command identity aliases and numeric internal identities', () => {
    expect(getCommandResultRecordPid({ recordId: 'legacy-alias', id: 1001 })).toBeUndefined();
    expect(getCommandResultRecordPid({ recordPid: 1001 })).toBeUndefined();
    expect(getCommandResultRecordPid(null)).toBeUndefined();
    expect(getCommandResultRecordPid({ data: { id: 1001 } })).toBeUndefined();
  });

  it('builds command params with only targetRecordPid', () => {
    expect(buildCommandTargetParams(' public-pid ')).toEqual({ targetRecordPid: 'public-pid' });
    expect(buildCommandTargetParams('')).toEqual({});
  });
});
