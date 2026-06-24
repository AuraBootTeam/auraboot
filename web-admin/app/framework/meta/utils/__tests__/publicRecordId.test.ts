import { describe, expect, it } from 'vitest';
import {
  buildCommandTargetParams,
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
