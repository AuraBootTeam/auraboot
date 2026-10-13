import type { Page } from '@playwright/test';
import { describe, expect, it, vi } from 'vitest';

import { ensureSidebarExpanded } from '../../tests/e2e/helpers';
import type { APIRequestContext } from '@playwright/test';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { FixtureCleanupJournal } from '../../tests/e2e/pcba-solution/fixture-cleanup';

function mockPage(reload: ReturnType<typeof vi.fn>): Page {
  return {
    evaluate: vi.fn().mockResolvedValue(undefined),
    reload,
    waitForLoadState: vi.fn().mockResolvedValue(undefined),
  } as unknown as Page;
}

describe('ensureSidebarExpanded', () => {
  it('retries one reload aborted by a concurrent navigation', async () => {
    const reload = vi
      .fn()
      .mockRejectedValueOnce(new Error('page.reload: net::ERR_ABORTED; maybe frame was detached?'))
      .mockResolvedValueOnce(null);
    const page = mockPage(reload);

    await ensureSidebarExpanded(page);

    expect(reload).toHaveBeenCalledTimes(2);
    expect(page.waitForLoadState).toHaveBeenCalledTimes(2);
  });

  it('does not retry unrelated reload failures', async () => {
    const reload = vi.fn().mockRejectedValue(new Error('page.reload: net::ERR_CONNECTION_REFUSED'));
    const page = mockPage(reload);

    await expect(ensureSidebarExpanded(page)).rejects.toThrow('ERR_CONNECTION_REFUSED');
    expect(reload).toHaveBeenCalledTimes(1);
  });
});

describe('fixture cleanup evidence', () => {
  function fixture() {
    const dir = mkdtempSync(path.join(tmpdir(), 'fixture-cleanup-'));
    const file = path.join(dir, 'journal.json');
    const journal = new FixtureCleanupJournal(file, 'http://127.0.0.1:5168');
    return { journal, file, dispose: () => rmSync(dir, { recursive: true, force: true }) };
  }
  function response(code: number, data: unknown, status = 200) {
    return { ok: () => status === 200, status: () => status, json: async () => ({ code, data }) };
  }

  function cleanupQuery(failure = '') {
    const calls = new Map<string, number>();
    return async (url: string) => {
      const count = (calls.get(url) ?? 0) + 1;
      calls.set(url, count);
      return response(failure === 'query-denied' ? 403 : 0,
        { records: failure === 'residue' || count % 2 === 1 ? [{ pid: 'owned' }] : [] });
    };
  }

  it('proves an already UI-deleted fixture absent without deleting it again', async () => {
    const f = fixture();
    try {
      f.journal.track({ model: 'bom_setting', pid: 'ui-deleted' });
      const request = { delete: vi.fn(), get: vi.fn().mockResolvedValue(response(0, { records: [] })) };
      await FixtureCleanupJournal.resume(f.file, f.journal.origin).cleanup(request as unknown as APIRequestContext);
      expect(request.delete).not.toHaveBeenCalled();
      expect(request.get).toHaveBeenCalledTimes(1);
      expect(JSON.parse(readFileSync(f.file, 'utf8')).pending).toBe(0);
    } finally { f.dispose(); }
  });

  it('persists pending identities before cleanup and verifies disappearance in reverse order', async () => {
    const f = fixture();
    try {
      f.journal.track({ model: 'bom_material_master', pid: 'parent' });
      f.journal.track({ model: 'bom_match_result', pid: 'child' });
      expect(JSON.parse(readFileSync(f.file, 'utf8')).pending).toBe(2);
      const request = { delete: vi.fn().mockResolvedValue(response(0, true)),
        get: vi.fn().mockImplementation(cleanupQuery()),
        post: vi.fn().mockResolvedValue(response(0, { data: { success: true } })) };
      await f.journal.cleanup(request as unknown as APIRequestContext);
      expect(request.delete.mock.calls.map(call => call[0])).toEqual([
        'http://127.0.0.1:5168/api/dynamic/bom_match_result/child',
        'http://127.0.0.1:5168/api/dynamic/bom_material_master/parent',
      ]);
      const state = JSON.parse(readFileSync(f.file, 'utf8'));
      expect(state.pending).toBe(0);
      expect(state.snapshotPending).toBe(false);
      expect(request.post).toHaveBeenCalledTimes(1);
      expect(state.entries.map((entry: { status: string }) => entry.status)).toEqual(['removed', 'removed']);
    } finally { f.dispose(); }
  });

  it.each(['denied', 'transport', 'residue', 'query-denied'])('keeps unresolved evidence and rejects a %s cleanup', async (failure) => {
    const f = fixture();
    try {
      f.journal.track({ model: 'bom_material_master', pid: 'owned' });
      const request = { delete: vi.fn().mockResolvedValue(response(failure === 'denied' ? 403 : 0, true)),
        get: vi.fn().mockImplementation(cleanupQuery(failure)),
        post: vi.fn().mockResolvedValue(response(0, { data: { success: true } })) };
      if (failure === 'transport') request.delete.mockRejectedValue(new Error('sensitive-transport-detail'));
      await expect(f.journal.cleanup(request as unknown as APIRequestContext)).rejects.toThrow('Fixture cleanup failed');
      const text = readFileSync(f.file, 'utf8');
      expect(JSON.parse(text).pending).toBe(1);
      expect(JSON.parse(text).entries[0].status).toBe('failed');
      expect(text).not.toContain('sensitive-transport-detail');
    } finally { f.dispose(); }
  });

  it('does not delete unregistered or already cleaned records', async () => {
    const f = fixture();
    try {
      const request = { delete: vi.fn().mockResolvedValue(response(0, true)),
        get: vi.fn().mockImplementation(cleanupQuery()),
        post: vi.fn().mockResolvedValue(response(0, { data: { success: true } })) };
      await expect(f.journal.cleanup(request as unknown as APIRequestContext,
        [{ model: 'bom_material_master', pid: 'foreign' }])).rejects.toThrow('unregistered');
      expect(request.delete).not.toHaveBeenCalled();
      f.journal.track({ model: 'bom_material_master', pid: 'owned' });
      await f.journal.cleanup(request as unknown as APIRequestContext);
      await f.journal.cleanup(request as unknown as APIRequestContext);
      expect(request.delete).toHaveBeenCalledTimes(1);
    } finally { f.dispose(); }
  });

  it('resumes an interrupted pending journal only against its original target', async () => {
    const f = fixture();
    try {
      f.journal.track({ model: 'bom_material_master', pid: 'interrupted' });
      expect(() => FixtureCleanupJournal.resume(f.file, 'http://127.0.0.1:5169')).toThrow('target/schema');
      const resumed = FixtureCleanupJournal.resume(f.file, f.journal.origin);
      const request = { delete: vi.fn().mockResolvedValue(response(0, true)),
        get: vi.fn().mockImplementation(cleanupQuery()),
        post: vi.fn().mockResolvedValue(response(0, { data: { success: true } })) };
      await resumed.cleanup(request as unknown as APIRequestContext);
      expect(JSON.parse(readFileSync(f.file, 'utf8')).pending).toBe(0);
      expect(request.delete).toHaveBeenCalledTimes(1);
    } finally { f.dispose(); }
  });

  it.each(['https://bootnap.com', 'http://bootnap.com', 'http://10.attacker.example', 'http://user:password@127.0.0.1:5168'])('rejects unsafe target %s before tracking data', (origin) => {
    expect(() => new FixtureCleanupJournal('/tmp/unused-fixture-journal.json', origin)).toThrow('nonproduction');
  });

  it('retains a failed snapshot refresh after deletion and resumes it without deleting twice', async () => {
    const f = fixture();
    try {
      f.journal.track({ model: 'bom_material_master', pid: 'owned' });
      const request = { delete: vi.fn().mockResolvedValue(response(0, true)),
        get: vi.fn().mockImplementation(cleanupQuery()),
        post: vi.fn().mockResolvedValue(response(403, null)) };
      await expect(f.journal.cleanup(request as unknown as APIRequestContext)).rejects.toThrow('material-snapshot');
      const state = JSON.parse(readFileSync(f.file, 'utf8'));
      expect(state.pending).toBe(0);
      expect(state.snapshotPending).toBe(true);
      request.post.mockResolvedValue(response(0, { data: { success: true } }));
      await FixtureCleanupJournal.resume(f.file, f.journal.origin).cleanup(request as unknown as APIRequestContext);
      expect(request.delete).toHaveBeenCalledTimes(1);
      expect(JSON.parse(readFileSync(f.file, 'utf8')).snapshotPending).toBe(false);
    } finally { f.dispose(); }
  });
});
