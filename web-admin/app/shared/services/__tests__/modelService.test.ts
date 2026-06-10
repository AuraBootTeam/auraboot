/**
 * Unit tests for modelService (ModelService class)
 *
 * modelService uses `~/shared/services/http-client` (get, post, put, del).
 * handleResponse: ResultHelper.isSuccess checks code === '0', then returns data.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock, putMock, delMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  putMock: vi.fn(),
  delMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  get: getMock,
  post: postMock,
  put: putMock,
  del: delMock,
}));

import { modelService } from '../modelService';

const ok = <T>(data: T) => ({ code: '0', desc: 'OK', data });
const fail = (desc = 'error') => ({ code: '500', desc, data: null });

describe('modelService', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    putMock.mockReset();
    delMock.mockReset();
  });

  // ── findByPage ───────────────────────────────────────────────────────────────

  describe('findByPage', () => {
    it('GETs /api/meta/models and transforms MyBatis-Plus page to PageResult', async () => {
      const dto = { pid: 'm1', code: 'order', displayName: 'Order' };
      getMock.mockResolvedValue(ok({ records: [dto], total: 1, size: 20, current: 2, pages: 5 }));

      const result = await modelService.findByPage({ pageNum: 2, pageSize: 20 });

      expect(getMock).toHaveBeenCalledWith('/api/meta/models', { pageNum: 2, pageSize: 20 }, undefined, undefined);
      expect(result.data).toEqual([dto]);
      expect(result.total).toBe(1);
      expect(result.page).toBe(2);      // current → page
      expect(result.size).toBe(20);
      expect(result.totalPages).toBe(5); // pages → totalPages
    });

    it('returns empty data with defaults on empty response fields', async () => {
      getMock.mockResolvedValue(ok({ records: null, total: null, size: null, current: null, pages: null }));

      const result = await modelService.findByPage({});

      expect(result.data).toEqual([]);
      expect(result.total).toBe(0);
      expect(result.page).toBe(1);
      expect(result.size).toBe(20);
      expect(result.totalPages).toBe(0);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('DB error'));

      await expect(modelService.findByPage({})).rejects.toThrow('DB error');
    });
  });

  // ── findByPid ────────────────────────────────────────────────────────────────

  describe('findByPid', () => {
    it('GETs /api/meta/models/:pid', async () => {
      const dto = { pid: 'm1', code: 'order' };
      getMock.mockResolvedValue(ok(dto));

      const result = await modelService.findByPid('m1');

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/m1', undefined, undefined, undefined);
      expect(result).toEqual(dto);
    });
  });

  // ── findByCode ───────────────────────────────────────────────────────────────

  describe('findByCode', () => {
    it('GETs /api/meta/models/code/:code', async () => {
      const dto = { pid: 'm1', code: 'order' };
      getMock.mockResolvedValue(ok(dto));

      const result = await modelService.findByCode('order');

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/code/order', undefined, undefined, undefined);
      expect(result).toEqual(dto);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Not found'));

      await expect(modelService.findByCode('bad')).rejects.toThrow('Not found');
    });
  });

  // ── create ───────────────────────────────────────────────────────────────────

  describe('create', () => {
    it('POSTs to /api/meta/models with request body', async () => {
      const dto = { pid: 'm1', code: 'order' };
      postMock.mockResolvedValue(ok(dto));

      const req = { code: 'order', displayName: 'Order', tableName: 'ab_dyn_order' };
      const result = await modelService.create(req);

      expect(postMock).toHaveBeenCalledWith('/api/meta/models', req, undefined, undefined);
      expect(result).toEqual(dto);
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Code already exists'));

      await expect(modelService.create({ code: 'dup', displayName: 'Dup' } as any)).rejects.toThrow('Code already exists');
    });
  });

  // ── update ───────────────────────────────────────────────────────────────────

  describe('update', () => {
    it('PUTs to /api/meta/models/:pid', async () => {
      const dto = { pid: 'm1', code: 'order', displayName: 'Order Updated' };
      putMock.mockResolvedValue(ok(dto));

      const result = await modelService.update('m1', { displayName: 'Order Updated' });

      expect(putMock).toHaveBeenCalledWith('/api/meta/models/m1', { displayName: 'Order Updated' }, undefined, undefined);
      expect(result).toEqual(dto);
    });
  });

  // ── delete ───────────────────────────────────────────────────────────────────

  describe('delete', () => {
    it('DELs /api/meta/models/:pid and resolves void', async () => {
      delMock.mockResolvedValue(ok(null));

      await expect(modelService.delete('m1')).resolves.toBeUndefined();
      expect(delMock).toHaveBeenCalledWith('/api/meta/models/m1', undefined, undefined, undefined);
    });

    it('throws on failure', async () => {
      delMock.mockResolvedValue(fail('Cannot delete published model'));

      await expect(modelService.delete('m1')).rejects.toThrow('Cannot delete published model');
    });
  });

  // ── batchDelete ───────────────────────────────────────────────────────────────

  describe('batchDelete', () => {
    it('POSTs to /api/meta/models/batch-delete with pids array', async () => {
      postMock.mockResolvedValue(ok(null));

      await modelService.batchDelete(['m1', 'm2']);

      expect(postMock).toHaveBeenCalledWith('/api/meta/models/batch-delete', { pids: ['m1', 'm2'] }, undefined, undefined);
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Delete failed'));

      await expect(modelService.batchDelete(['m1'])).rejects.toThrow('Delete failed');
    });
  });

  // ── checkCodeUnique ───────────────────────────────────────────────────────────

  describe('checkCodeUnique', () => {
    it('GETs /api/meta/models/code/:code/unique without excludePid', async () => {
      getMock.mockResolvedValue(ok(true));

      const result = await modelService.checkCodeUnique('order');

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/code/order/unique', {}, undefined, undefined);
      expect(result).toBe(true);
    });

    it('includes excludePid when provided', async () => {
      getMock.mockResolvedValue(ok(true));

      await modelService.checkCodeUnique('order', 'm-old');

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/code/order/unique', { excludePid: 'm-old' }, undefined, undefined);
    });
  });

  // ── getVersionHistory ─────────────────────────────────────────────────────────

  describe('getVersionHistory', () => {
    it('GETs /api/meta/models/code/:code/versions', async () => {
      const versions = [{ version: 1, createdAt: '2024-01-01' }];
      getMock.mockResolvedValue(ok(versions));

      const result = await modelService.getVersionHistory('order');

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/code/order/versions', undefined, undefined, undefined);
      expect(result).toEqual(versions);
    });
  });

  // ── getVersionDetail ──────────────────────────────────────────────────────────

  describe('getVersionDetail', () => {
    it('GETs /api/meta/models/code/:code/versions/:version', async () => {
      const dto = { pid: 'm1', code: 'order', version: 3 };
      getMock.mockResolvedValue(ok(dto));

      const result = await modelService.getVersionDetail('order', 3);

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/code/order/versions/3', undefined, undefined, undefined);
      expect(result).toEqual(dto);
    });
  });

  // ── compareVersions ───────────────────────────────────────────────────────────

  describe('compareVersions', () => {
    it('POSTs to /api/meta/models/code/:code/versions/compare', async () => {
      const diff = { added: [], removed: [], changed: [] };
      postMock.mockResolvedValue(ok(diff));

      const result = await modelService.compareVersions('order', 1, 2);

      expect(postMock).toHaveBeenCalledWith(
        '/api/meta/models/code/order/versions/compare',
        { v1: 1, v2: 2 },
        undefined,
        undefined,
      );
      expect(result).toEqual(diff);
    });
  });

  // ── rollbackToVersion ─────────────────────────────────────────────────────────

  describe('rollbackToVersion', () => {
    it('POSTs to /api/meta/models/code/:code/rollback', async () => {
      const dto = { pid: 'm1', code: 'order', version: 1 };
      postMock.mockResolvedValue(ok(dto));

      const result = await modelService.rollbackToVersion('order', 1);

      expect(postMock).toHaveBeenCalledWith(
        '/api/meta/models/code/order/rollback',
        { version: 1 },
        undefined,
        undefined,
      );
      expect(result).toEqual(dto);
    });
  });

  // ── refreshCache ──────────────────────────────────────────────────────────────

  describe('refreshCache', () => {
    it('POSTs to /api/meta/models/:pid/refresh-cache', async () => {
      postMock.mockResolvedValue(ok(null));

      await modelService.refreshCache('m1');

      expect(postMock).toHaveBeenCalledWith('/api/meta/models/m1/refresh-cache', undefined, undefined, undefined);
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Cache error'));

      await expect(modelService.refreshCache('m1')).rejects.toThrow('Cache error');
    });
  });

  // ── getModelFields ────────────────────────────────────────────────────────────

  describe('getModelFields', () => {
    it('GETs /api/meta/models/:pid/fields', async () => {
      const fields = [{ id: 1, fieldCode: 'name', fieldType: 'text' }];
      getMock.mockResolvedValue(ok(fields));

      const result = await modelService.getModelFields('m1');

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/m1/fields', undefined, undefined, undefined);
      expect(result).toEqual(fields);
    });
  });

  // ── getRelatedPages ───────────────────────────────────────────────────────────

  describe('getRelatedPages', () => {
    it('GETs /api/meta/models/:pid/pages', async () => {
      const pages = [{ pid: 'pg1', kind: 'list', pageKey: 'order_list' }];
      getMock.mockResolvedValue(ok(pages));

      const result = await modelService.getRelatedPages('m1');

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/m1/pages', undefined, undefined, undefined);
      expect(result).toEqual(pages);
    });
  });

  // ── getStatistics ─────────────────────────────────────────────────────────────

  describe('getStatistics', () => {
    it('GETs /api/meta/models/statistics', async () => {
      const stats = { totalModels: 10, publishedModels: 8, draftModels: 2, totalFields: 50 };
      getMock.mockResolvedValue(ok(stats));

      const result = await modelService.getStatistics();

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/statistics', undefined, undefined, undefined);
      expect(result).toEqual(stats);
    });
  });

  // ── validate ─────────────────────────────────────────────────────────────────

  describe('validate', () => {
    it('POSTs to /api/meta/models/validate', async () => {
      const validation = { valid: true, errors: {} };
      postMock.mockResolvedValue(ok(validation));

      const data = { code: 'order', displayName: 'Order' } as any;
      const result = await modelService.validate(data);

      expect(postMock).toHaveBeenCalledWith('/api/meta/models/validate', data, undefined, undefined);
      expect(result).toEqual(validation);
    });
  });

  // ── isGitFirstEnabled ─────────────────────────────────────────────────────────

  describe('isGitFirstEnabled', () => {
    it('GETs /api/git/router/requires-git-first and extracts required', async () => {
      getMock.mockResolvedValue(ok({ required: true }));

      const result = await modelService.isGitFirstEnabled();

      expect(getMock).toHaveBeenCalledWith(
        '/api/git/router/requires-git-first',
        { resourceType: 'model' },
        undefined,
        undefined,
      );
      expect(result).toBe(true);
    });

    it('returns false when required is false', async () => {
      getMock.mockResolvedValue(ok({ required: false }));

      expect(await modelService.isGitFirstEnabled()).toBe(false);
    });
  });

  // ── getReleaseInfo ────────────────────────────────────────────────────────────

  describe('getReleaseInfo', () => {
    it('GETs /api/meta/models/:pid/release', async () => {
      const info = { releaseId: 1, releasePid: 'r1', status: 'RELEASED', version: 3, createdAt: '2024-01-01' };
      getMock.mockResolvedValue(ok(info));

      const result = await modelService.getReleaseInfo('m1');

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/m1/release', undefined, undefined, undefined);
      expect(result).toEqual(info);
    });
  });

  // ── updateFieldsOrder ─────────────────────────────────────────────────────────

  describe('updateFieldsOrder', () => {
    it('PUTs /api/meta/models/:pid/fields/reorder with fieldCode→order map', async () => {
      putMock.mockResolvedValue(ok(2));

      const result = await modelService.updateFieldsOrder('m1', [
        { fieldCode: 'name', displayOrder: 0 },
        { fieldCode: 'status', displayOrder: 1 },
      ]);

      expect(putMock).toHaveBeenCalledWith(
        '/api/meta/models/m1/fields/reorder',
        { name: 0, status: 1 },
        undefined,
        undefined,
      );
      expect(result).toBe(2);
    });
  });

  // ── publish ───────────────────────────────────────────────────────────────────

  describe('publish', () => {
    it('POSTs to /api/meta/models/:pid/publish without versionNote', async () => {
      const dto = { pid: 'm1', code: 'order' };
      postMock.mockResolvedValue(ok(dto));

      const result = await modelService.publish('m1');

      expect(postMock).toHaveBeenCalledWith('/api/meta/models/m1/publish', undefined, undefined, undefined);
      expect(result).toEqual(dto);
    });

    it('appends versionNote as query param', async () => {
      postMock.mockResolvedValue(ok({ pid: 'm1' }));

      await modelService.publish('m1', 'v1 release');

      const url: string = postMock.mock.calls[0][0];
      expect(url).toContain('?versionNote=');
      expect(url).toContain('v1%20release');
    });
  });

  // ── unpublish ─────────────────────────────────────────────────────────────────

  describe('unpublish', () => {
    it('POSTs to /api/meta/models/:pid/unpublish', async () => {
      const dto = { pid: 'm1', code: 'order' };
      postMock.mockResolvedValue(ok(dto));

      const result = await modelService.unpublish('m1');

      expect(postMock).toHaveBeenCalledWith('/api/meta/models/m1/unpublish', undefined, undefined, undefined);
      expect(result).toEqual(dto);
    });
  });

  // ── previewPublishDDL ─────────────────────────────────────────────────────────

  describe('previewPublishDDL', () => {
    it('GETs /api/meta/models/:pid/publish/preview', async () => {
      const preview = {
        modelCode: 'order',
        ddlStatements: ['CREATE TABLE ab_dyn_order (...)'],
        operationType: 'CREATE',
        affectedTables: ['ab_dyn_order'],
        riskAssessment: null,
      };
      getMock.mockResolvedValue(ok(preview));

      const result = await modelService.previewPublishDDL('m1');

      expect(getMock).toHaveBeenCalledWith('/api/meta/models/m1/publish/preview', undefined, undefined, undefined);
      expect(result.ddlStatements).toHaveLength(1);
      expect(result.operationType).toBe('CREATE');
    });
  });

  // ── bindDictToField ───────────────────────────────────────────────────────────

  describe('bindDictToField', () => {
    it('POSTs to /api/meta/fields/:fieldPid/bind-dict', async () => {
      postMock.mockResolvedValue(ok(null));

      await modelService.bindDictToField('f1', 'ORDER_STATUS');

      expect(postMock).toHaveBeenCalledWith('/api/meta/fields/f1/bind-dict', { dictCode: 'ORDER_STATUS' }, undefined, undefined);
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Dict not found'));

      await expect(modelService.bindDictToField('f1', 'BAD')).rejects.toThrow('Dict not found');
    });
  });

  // ── unbindDictFromField ───────────────────────────────────────────────────────

  describe('unbindDictFromField', () => {
    it('DELs /api/meta/fields/:fieldPid/unbind-dict', async () => {
      delMock.mockResolvedValue(ok(null));

      await modelService.unbindDictFromField('f1');

      expect(delMock).toHaveBeenCalledWith('/api/meta/fields/f1/unbind-dict', undefined, undefined, undefined);
    });

    it('throws on failure', async () => {
      delMock.mockResolvedValue(fail('Unbind failed'));

      await expect(modelService.unbindDictFromField('f1')).rejects.toThrow('Unbind failed');
    });
  });

  // ── getBoundDict ──────────────────────────────────────────────────────────────

  describe('getBoundDict', () => {
    it('GETs /api/meta/fields/:fieldPid/bound-dict', async () => {
      const dict = { dictCode: 'ORDER_STATUS', dictName: 'Order Status', items: [] };
      getMock.mockResolvedValue(ok(dict));

      const result = await modelService.getBoundDict('f1');

      expect(getMock).toHaveBeenCalledWith('/api/meta/fields/f1/bound-dict', undefined, undefined, undefined);
      expect(result).toEqual(dict);
    });
  });

  // ── bindFieldToModel ──────────────────────────────────────────────────────────

  describe('bindFieldToModel', () => {
    it('POSTs to /api/meta/models/:modelPid/fields/bind', async () => {
      const binding = { id: 1, fieldCode: 'name' };
      postMock.mockResolvedValue(ok(binding));

      const req = { fieldPid: 'f1', required: true } as any;
      const result = await modelService.bindFieldToModel('m1', req);

      expect(postMock).toHaveBeenCalledWith('/api/meta/models/m1/fields/bind', req, undefined, undefined);
      expect(result).toEqual(binding);
    });
  });

  // ── batchBindFieldsToModel ─────────────────────────────────────────────────────

  describe('batchBindFieldsToModel', () => {
    it('POSTs to /api/meta/models/:modelPid/fields/bind-batch', async () => {
      const bindings = [{ id: 1 }, { id: 2 }];
      postMock.mockResolvedValue(ok(bindings));

      const req = { fieldPids: ['f1', 'f2'], required: false } as any;
      const result = await modelService.batchBindFieldsToModel('m1', req);

      expect(postMock).toHaveBeenCalledWith('/api/meta/models/m1/fields/bind-batch', req, undefined, undefined);
      expect(result).toEqual(bindings);
    });
  });

  // ── exportModels (native fetch) ────────────────────────────────────────────────

  describe('exportModels', () => {
    it('POSTs to /api/meta/models/export via native fetch and returns Blob', async () => {
      const blob = new Blob(['data'], { type: 'application/octet-stream' });
      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
        ok: true,
        blob: async () => blob,
      } as any);

      const result = await modelService.exportModels({ pageNum: 1 }, ['m1']);

      expect(fetchSpy).toHaveBeenCalledWith('/api/meta/models/export', expect.objectContaining({
        method: 'post',
        body: JSON.stringify({ filters: { pageNum: 1 }, selectedIds: ['m1'] }),
      }));
      expect(result).toBe(blob);

      fetchSpy.mockRestore();
    });

    it('throws when response is not ok', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue({
        ok: false,
        statusText: 'Forbidden',
        blob: async () => new Blob(),
      } as any);

      await expect(modelService.exportModels({})).rejects.toThrow('导出失败: Forbidden');

      vi.restoreAllMocks();
    });
  });
});
