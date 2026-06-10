/**
 * Unit tests for dictService
 * Validates URL construction, payload forwarding, and response handling.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock, putMock, delMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  putMock: vi.fn(),
  delMock: vi.fn(),
}));

vi.mock('../http-client', () => ({
  get: getMock,
  post: postMock,
  put: putMock,
  del: delMock,
}));

import { dictService } from '../dictService';

// dictService.handleResponse checks result.success (bool) and result.message
const SUCCESS = { success: true };

describe('dictService', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    putMock.mockReset();
    delMock.mockReset();
  });

  // ── create ──────────────────────────────────────────────────────────────────

  describe('create', () => {
    it('posts to /api/meta/dict and returns data on success', async () => {
      const dto = { pid: 'p1', code: 'STATUS', name: 'Status' };
      postMock.mockResolvedValue({ ...SUCCESS, data: dto });

      const result = await dictService.create({ code: 'STATUS', name: 'Status', dictType: 'simple' });

      expect(postMock).toHaveBeenCalledWith('/api/meta/dict', { code: 'STATUS', name: 'Status', dictType: 'simple' }, undefined, undefined);
      expect(result).toEqual(dto);
    });

    it('throws when success is false', async () => {
      postMock.mockResolvedValue({ success: false, message: 'Server error', data: null });

      await expect(dictService.create({ code: 'X', name: 'X', dictType: 'simple' })).rejects.toThrow('Server error');
    });

    it('throws default message when result.message is missing', async () => {
      postMock.mockResolvedValue({ success: false, message: '', data: null });

      await expect(dictService.create({ code: 'X', name: 'X', dictType: 'simple' })).rejects.toThrow('Failed to create dictionary');
    });
  });

  // ── update ──────────────────────────────────────────────────────────────────

  describe('update', () => {
    it('puts to /api/meta/dict/:pid and returns data', async () => {
      const dto = { pid: 'p1', code: 'STATUS', name: 'Updated' };
      putMock.mockResolvedValue({ ...SUCCESS, data: dto });

      const result = await dictService.update('p1', { name: 'Updated' });

      expect(putMock).toHaveBeenCalledWith('/api/meta/dict/p1', { name: 'Updated' }, undefined, undefined);
      expect(result).toEqual(dto);
    });

    it('throws on failure', async () => {
      putMock.mockResolvedValue({ success: false, message: 'Bad request', data: null });

      await expect(dictService.update('p1', {})).rejects.toThrow('Bad request');
    });
  });

  // ── replaceItems ─────────────────────────────────────────────────────────────

  describe('replaceItems', () => {
    it('puts to /api/meta/dict/:pid/items', async () => {
      const dto = { pid: 'p1', code: 'STATUS', name: 'Status' };
      putMock.mockResolvedValue({ ...SUCCESS, data: dto });

      const items = [{ value: 'ACTIVE', label: 'Active' }];
      const result = await dictService.replaceItems('p1', items);

      expect(putMock).toHaveBeenCalledWith('/api/meta/dict/p1/items', items, undefined, undefined);
      expect(result).toEqual(dto);
    });
  });

  // ── delete ───────────────────────────────────────────────────────────────────

  describe('delete', () => {
    it('calls del on /api/meta/dict/:pid and resolves void', async () => {
      delMock.mockResolvedValue({ ...SUCCESS, data: null });

      await expect(dictService.delete('p1')).resolves.toBeUndefined();
      expect(delMock).toHaveBeenCalledWith('/api/meta/dict/p1', undefined, undefined, undefined);
    });

    it('throws on failure', async () => {
      delMock.mockResolvedValue({ success: false, message: 'Forbidden', data: null });

      await expect(dictService.delete('p1')).rejects.toThrow('Forbidden');
    });
  });

  // ── findByPid ────────────────────────────────────────────────────────────────

  describe('findByPid', () => {
    it('GETs /api/meta/dict/:pid', async () => {
      const dto = { pid: 'p1', code: 'STATUS' };
      getMock.mockResolvedValue({ ...SUCCESS, data: dto });

      const result = await dictService.findByPid('p1');

      expect(getMock).toHaveBeenCalledWith('/api/meta/dict/p1', undefined, undefined, undefined);
      expect(result).toEqual(dto);
    });
  });

  // ── findByCode ───────────────────────────────────────────────────────────────

  describe('findByCode', () => {
    it('GETs /api/meta/dict/by-code/:code', async () => {
      const dto = { pid: 'p1', code: 'STATUS' };
      getMock.mockResolvedValue({ ...SUCCESS, data: dto });

      const result = await dictService.findByCode('STATUS');

      expect(getMock).toHaveBeenCalledWith('/api/meta/dict/by-code/STATUS', undefined, undefined, undefined);
      expect(result).toEqual(dto);
    });
  });

  // ── query ────────────────────────────────────────────────────────────────────

  describe('query', () => {
    it('builds URLSearchParams and GETs paginated results', async () => {
      const pageResult = { records: [], total: 0, current: 1, size: 10, pages: 0 };
      getMock.mockResolvedValue({ ...SUCCESS, data: pageResult });

      const result = await dictService.query({ pageNum: 1, pageSize: 10, status: 'published' });

      expect(getMock).toHaveBeenCalledWith(
        expect.stringContaining('/api/meta/dict?'),
        undefined,
        undefined,
        undefined,
      );
      const calledUrl: string = getMock.mock.calls[0][0];
      expect(calledUrl).toContain('pageNum=1');
      expect(calledUrl).toContain('pageSize=10');
      expect(calledUrl).toContain('status=published');
      expect(result).toEqual(pageResult);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue({ success: false, message: 'Internal error', data: null });

      await expect(dictService.query({})).rejects.toThrow('Internal error');
    });
  });

  // ── loadData ─────────────────────────────────────────────────────────────────

  describe('loadData', () => {
    it('GETs with versionStrategy=latest by default', async () => {
      const data = { items: [] };
      getMock.mockResolvedValue({ ...SUCCESS, data });

      const result = await dictService.loadData('p1');

      const url: string = getMock.mock.calls[0][0];
      expect(url).toContain('/api/meta/dict/p1/data?');
      expect(url).toContain('versionStrategy=latest');
      expect(result).toEqual(data);
    });

    it('appends pinnedVersion when provided', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: { items: [] } });

      await dictService.loadData('p1', 'pinned', '42');

      const url: string = getMock.mock.calls[0][0];
      expect(url).toContain('pinnedVersion=42');
    });
  });

  // ── publish / unpublish ───────────────────────────────────────────────────────

  describe('publish', () => {
    it('posts to publish URL without versionNote', async () => {
      const dto = { pid: 'p1', code: 'STATUS' };
      postMock.mockResolvedValue({ ...SUCCESS, data: dto });

      const result = await dictService.publish('p1');

      expect(postMock).toHaveBeenCalledWith('/api/meta/dict/p1/publish', undefined, undefined, undefined);
      expect(result).toEqual(dto);
    });

    it('includes versionNote in query string when provided', async () => {
      postMock.mockResolvedValue({ ...SUCCESS, data: { pid: 'p1' } });

      await dictService.publish('p1', 'v2 release');

      const url: string = postMock.mock.calls[0][0];
      expect(url).toContain('versionNote=v2+release');
    });
  });

  describe('unpublish', () => {
    it('posts to /api/meta/dict/:pid/unpublish', async () => {
      const dto = { pid: 'p1', code: 'STATUS' };
      postMock.mockResolvedValue({ ...SUCCESS, data: dto });

      const result = await dictService.unpublish('p1');

      expect(postMock).toHaveBeenCalledWith('/api/meta/dict/p1/unpublish', undefined, undefined, undefined);
      expect(result).toEqual(dto);
    });
  });

  // ── getVersionHistory ─────────────────────────────────────────────────────────

  describe('getVersionHistory', () => {
    it('GETs /:code/versions', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: [] });

      await dictService.getVersionHistory('STATUS');

      expect(getMock).toHaveBeenCalledWith('/api/meta/dict/STATUS/versions', undefined, undefined, undefined);
    });
  });

  // ── getCascadeChildren ────────────────────────────────────────────────────────

  describe('getCascadeChildren', () => {
    it('GETs /cascade/children without parentValue', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: [] });

      await dictService.getCascadeChildren('p1');

      expect(getMock).toHaveBeenCalledWith('/api/meta/dict/p1/cascade/children', undefined, undefined, undefined);
    });

    it('includes parentValue in URL when provided', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: [] });

      await dictService.getCascadeChildren('p1', 'ROOT');

      const url: string = getMock.mock.calls[0][0];
      expect(url).toContain('parentValue=ROOT');
    });
  });

  // ── buildCascadeTree ──────────────────────────────────────────────────────────

  describe('buildCascadeTree', () => {
    it('GETs /cascade/tree', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: { value: 'root', children: [] } });

      const result = await dictService.buildCascadeTree('p1');

      expect(getMock).toHaveBeenCalledWith('/api/meta/dict/p1/cascade/tree', undefined, undefined, undefined);
      expect(result).toEqual({ value: 'root', children: [] });
    });
  });

  // ── getStatistics ─────────────────────────────────────────────────────────────

  describe('getStatistics', () => {
    it('GETs /statistics', async () => {
      const stats = { total: 10, published: 8, draft: 2 };
      getMock.mockResolvedValue({ ...SUCCESS, data: stats });

      const result = await dictService.getStatistics();

      expect(getMock).toHaveBeenCalledWith('/api/meta/dict/statistics', undefined, undefined, undefined);
      expect(result).toEqual(stats);
    });
  });

  // ── validateConfig ────────────────────────────────────────────────────────────

  describe('validateConfig', () => {
    it('GETs /:code/validate', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: { valid: true, errors: [] } });

      const result = await dictService.validateConfig('STATUS');

      expect(getMock).toHaveBeenCalledWith('/api/meta/dict/STATUS/validate', undefined, undefined, undefined);
      expect(result).toEqual({ valid: true, errors: [] });
    });
  });

  // ── checkCodeUnique ───────────────────────────────────────────────────────────

  describe('checkCodeUnique', () => {
    it('GETs /code/:code/unique without excludePid', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: true });

      const result = await dictService.checkCodeUnique('STATUS');

      expect(getMock).toHaveBeenCalledWith('/api/meta/dict/code/STATUS/unique', undefined, undefined, undefined);
      expect(result).toBe(true);
    });

    it('includes excludePid when provided', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: true });

      await dictService.checkCodeUnique('STATUS', 'p-old');

      const url: string = getMock.mock.calls[0][0];
      expect(url).toContain('excludePid=p-old');
    });
  });

  // ── batchDelete ───────────────────────────────────────────────────────────────

  describe('batchDelete', () => {
    it('DELs with pids as repeated query param', async () => {
      delMock.mockResolvedValue({ ...SUCCESS, data: 2 });

      const result = await dictService.batchDelete(['p1', 'p2']);

      const url: string = delMock.mock.calls[0][0];
      expect(url).toContain('/api/meta/dict/batch?');
      expect(url).toContain('pids=p1');
      expect(url).toContain('pids=p2');
      expect(result).toBe(2);
    });
  });

  // ── search ────────────────────────────────────────────────────────────────────

  describe('search', () => {
    it('GETs /search?keyword=<value>', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: [] });

      await dictService.search('status');

      const url: string = getMock.mock.calls[0][0];
      expect(url).toContain('/api/meta/dict/search?');
      expect(url).toContain('keyword=status');
    });
  });

  // ── findAll ───────────────────────────────────────────────────────────────────

  describe('findAll', () => {
    it('queries with published status page 1 size 1000 and returns records array', async () => {
      getMock.mockResolvedValue({
        ...SUCCESS,
        data: { records: [{ pid: 'p1', code: 'STATUS' }], total: 1, current: 1, size: 1000, pages: 1 },
      });

      const result = await dictService.findAll();

      const url: string = getMock.mock.calls[0][0];
      expect(url).toContain('status=published');
      expect(url).toContain('pageNum=1');
      expect(url).toContain('pageSize=1000');
      expect(result).toHaveLength(1);
      expect(result[0]).toEqual({ pid: 'p1', code: 'STATUS' });
    });

    it('returns empty array when records is missing', async () => {
      getMock.mockResolvedValue({ ...SUCCESS, data: { total: 0, current: 1, size: 1000, pages: 0 } });

      const result = await dictService.findAll();

      expect(result).toEqual([]);
    });
  });
});
