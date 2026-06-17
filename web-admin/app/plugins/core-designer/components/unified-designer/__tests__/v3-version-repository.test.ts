import { describe, expect, it, vi } from 'vitest';
import {
  createPageVersion,
  getPageVersions,
  rollbackPageToVersion,
  type PageSchemaV3Api,
} from '../persistence/pageSchemaV3Repository';

/**
 * Unit coverage for the version-history repository helpers
 * (getPageVersions / createPageVersion / rollbackPageToVersion). These wrap the
 * real /api/pages/{pid}/versions[/rollback] endpoints; the E2E golden drives the
 * full UI → backend → readback round-trip. Here we assert the request shape and
 * the {code} envelope unwrapping in isolation.
 */

function baseApi(overrides: Partial<PageSchemaV3Api> = {}): PageSchemaV3Api {
  return {
    getPageByPid: vi.fn(),
    getPageByPageKey: vi.fn(),
    updatePage: vi.fn(),
    createPage: vi.fn(),
    ...overrides,
  };
}

describe('PageSchema V3 version repository', () => {
  it('getPageVersions returns the version list on code 0', async () => {
    const versions = [
      { id: 2, pagePid: 'p1', version: 2, operation: 'update', snapshot: {} },
      { id: 1, pagePid: 'p1', version: 1, operation: 'create', snapshot: {} },
    ];
    const getVersionHistory = vi.fn().mockResolvedValue({ code: '0', data: versions });
    const api = baseApi({ getVersionHistory });

    const result = await getPageVersions('p1', api);

    expect(getVersionHistory).toHaveBeenCalledWith('p1');
    expect(result.ok).toBe(true);
    expect(result.data).toEqual(versions);
  });

  it('getPageVersions surfaces a non-0 code as an error string (no throw)', async () => {
    const getVersionHistory = vi
      .fn()
      .mockResolvedValue({ code: '403', message: 'No page.page.read' });
    const api = baseApi({ getVersionHistory });

    const result = await getPageVersions('p1', api);

    expect(result.ok).toBe(false);
    expect(result.error).toBe('No page.page.read');
  });

  it('getPageVersions reports a clear error when the api lacks the method', async () => {
    const result = await getPageVersions('p1', baseApi());
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/not available/i);
  });

  it('createPageVersion sends a snapshot operation + reason description', async () => {
    const createVersion = vi
      .fn()
      .mockResolvedValue({ code: '0', data: { id: 3, pagePid: 'p1', version: 3, operation: 'snapshot' } });
    const api = baseApi({ createVersion });

    const result = await createPageVersion('p1', 'before risky edit', api);

    expect(createVersion).toHaveBeenCalledWith('p1', {
      operation: 'snapshot',
      description: 'before risky edit',
    });
    expect(result.ok).toBe(true);
    expect(result.data).toMatchObject({ id: 3, version: 3 });
  });

  it('createPageVersion surfaces a non-0 code as an error string', async () => {
    const createVersion = vi.fn().mockResolvedValue({ code: '500', desc: 'boom' });
    const api = baseApi({ createVersion });

    const result = await createPageVersion('p1', 'x', api);

    expect(result.ok).toBe(false);
    expect(result.error).toBe('boom');
  });

  it('rollbackPageToVersion calls rollback with the historyId + reason', async () => {
    const rollbackToVersion = vi
      .fn()
      .mockResolvedValue({ code: '0', data: { id: 4, pagePid: 'p1', version: 4, operation: 'rollback' } });
    const api = baseApi({ rollbackToVersion });

    const result = await rollbackPageToVersion('p1', 1, 'Roll back', api);

    expect(rollbackToVersion).toHaveBeenCalledWith('p1', 1, 'Roll back');
    expect(result.ok).toBe(true);
    expect(result.data).toMatchObject({ id: 4, version: 4 });
  });

  it('rollbackPageToVersion surfaces a non-0 code as an error string', async () => {
    const rollbackToVersion = vi
      .fn()
      .mockResolvedValue({ code: '400', message: 'cannot rollback' });
    const api = baseApi({ rollbackToVersion });

    const result = await rollbackPageToVersion('p1', 99, 'Roll back', api);

    expect(result.ok).toBe(false);
    expect(result.error).toBe('cannot rollback');
  });
});
