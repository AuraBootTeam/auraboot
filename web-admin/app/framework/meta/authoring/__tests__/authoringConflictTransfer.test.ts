import { beforeEach, describe, expect, it } from 'vitest';
import {
  consumeAuthoringConflictTransfer,
  storeAuthoringConflictTransfer,
} from '../authoringConflictTransfer';

describe('authoringConflictTransfer', () => {
  beforeEach(() => window.sessionStorage.clear());

  it('keeps snapshots out of the URL and consumes the actor session binding only once', () => {
    const contextId = storeAuthoringConflictTransfer({
      sessionPid: 'session-1',
      changeSetPid: 'changeset-1',
      pagePid: 'page-1',
      baseRevision: 3,
      baseSnapshot: { blocks: [{ id: 'table-1', title: 'Base' }] },
      mineSnapshot: { blocks: [{ id: 'table-1', title: 'Mine' }] },
    });

    expect(contextId).toMatch(/^[a-f0-9]{32}$/);
    expect(contextId).not.toContain('Base');
    expect(
      consumeAuthoringConflictTransfer(contextId, {
        sessionPid: 'session-1',
        changeSetPid: 'changeset-1',
        pagePid: 'page-1',
      }),
    ).toMatchObject({ baseRevision: 3, mineSnapshot: { blocks: [{ title: 'Mine' }] } });
    expect(() =>
      consumeAuthoringConflictTransfer(contextId, {
        sessionPid: 'session-1',
        changeSetPid: 'changeset-1',
        pagePid: 'page-1',
      }),
    ).toThrow('已使用、已过期');
  });

  it('rejects a mismatched session and invalidates the transfer', () => {
    const contextId = storeAuthoringConflictTransfer({
      sessionPid: 'session-1',
      changeSetPid: 'changeset-1',
      pagePid: 'page-1',
      baseRevision: 3,
      baseSnapshot: {},
      mineSnapshot: {},
    });

    expect(() =>
      consumeAuthoringConflictTransfer(contextId, {
        sessionPid: 'session-2',
        changeSetPid: 'changeset-1',
        pagePid: 'page-1',
      }),
    ).toThrow('与当前会话不匹配');
    expect(() =>
      consumeAuthoringConflictTransfer(contextId, {
        sessionPid: 'session-1',
        changeSetPid: 'changeset-1',
        pagePid: 'page-1',
      }),
    ).toThrow('已使用、已过期');
  });
});
