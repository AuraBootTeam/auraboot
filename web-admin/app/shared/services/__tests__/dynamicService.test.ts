/**
 * Unit tests for dynamicService
 * Validates URL construction, payload forwarding, and response handling.
 * dynamicService uses ResultHelper.isSuccess (code==='0') and result.desc.
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

import { dynamicService } from '../dynamicService';

// ResultHelper.isSuccess checks code === '0'
const SUCCESS = { code: '0', desc: '', data: null as any };

function ok<T>(data: T) {
  return { ...SUCCESS, data };
}

function fail(desc = 'Server error') {
  return { code: '1', desc, data: null };
}

describe('dynamicService', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    putMock.mockReset();
    delMock.mockReset();
  });

  // ── findByPage ──────────────────────────────────────────────────────────────

  describe('findByPage', () => {
    it('GETs /api/dynamic/:code/list with default page/size', async () => {
      const raw = { records: [{ id: 'r1' }], total: 1, page: 0, pageSize: 20, totalPages: 1 };
      getMock.mockResolvedValue(ok(raw));

      const result = await dynamicService.findByPage('order', { page: 0, size: 20 });

      expect(getMock).toHaveBeenCalledWith(
        '/api/dynamic/order/list',
        { page: '0', size: '20' },
        undefined,
        undefined,
      );
      expect(result.records).toHaveLength(1);
      expect(result.total).toBe(1);
    });

    it('appends optional keyword / sort params', async () => {
      getMock.mockResolvedValue(ok({ records: [], total: 0, page: 0, pageSize: 10, totalPages: 0 }));

      await dynamicService.findByPage('order', {
        page: 1,
        size: 10,
        keyword: 'foo',
        sortBy: 'name',
        sortDirection: 'asc',
      });

      const params = getMock.mock.calls[0][1];
      expect(params.keyword).toBe('foo');
      expect(params.sortBy).toBe('name');
      expect(params.sortDirection).toBe('asc');
    });

    it('normalises missing backend fields to defaults', async () => {
      getMock.mockResolvedValue(ok({}));

      const result = await dynamicService.findByPage('order', {});

      expect(result.records).toEqual([]);
      expect(result.total).toBe(0);
      expect(result.pageSize).toBe(20);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('DB error'));

      await expect(dynamicService.findByPage('order', {})).rejects.toThrow('DB error');
    });
  });

  // ── findById ────────────────────────────────────────────────────────────────

  describe('findById', () => {
    it('GETs /api/dynamic/:code/:id', async () => {
      const entity = { id: 'e1', name: 'Test' };
      getMock.mockResolvedValue(ok(entity));

      const result = await dynamicService.findById('order', 'e1');

      expect(getMock).toHaveBeenCalledWith(
        '/api/dynamic/order/e1',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual(entity);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Not found'));

      await expect(dynamicService.findById('order', 'x')).rejects.toThrow('Not found');
    });
  });

  // ── create ──────────────────────────────────────────────────────────────────

  describe('create', () => {
    it('POSTs to /api/dynamic/:code and returns entity', async () => {
      const entity = { id: 'new', name: 'New Order' };
      postMock.mockResolvedValue(ok(entity));

      const result = await dynamicService.create('order', { name: 'New Order' });

      expect(postMock).toHaveBeenCalledWith(
        '/api/dynamic/order',
        { name: 'New Order' },
        undefined,
        undefined,
      );
      expect(result).toEqual(entity);
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Validation failed'));

      await expect(dynamicService.create('order', {})).rejects.toThrow('Validation failed');
    });
  });

  // ── update ──────────────────────────────────────────────────────────────────

  describe('update', () => {
    it('PUTs to /api/dynamic/:code/:id', async () => {
      const entity = { id: 'e1', name: 'Updated' };
      putMock.mockResolvedValue(ok(entity));

      const result = await dynamicService.update('order', 'e1', { name: 'Updated' });

      expect(putMock).toHaveBeenCalledWith(
        '/api/dynamic/order/e1',
        { name: 'Updated' },
        undefined,
        undefined,
      );
      expect(result).toEqual(entity);
    });

    it('throws on failure', async () => {
      putMock.mockResolvedValue(fail('Conflict'));

      await expect(dynamicService.update('order', 'e1', {})).rejects.toThrow('Conflict');
    });
  });

  // ── deleteById ───────────────────────────────────────────────────────────────

  describe('deleteById', () => {
    it('DELs /api/dynamic/:code/:id and resolves void', async () => {
      delMock.mockResolvedValue(ok(null));

      await expect(dynamicService.deleteById('order', 'e1')).resolves.toBeUndefined();
      expect(delMock).toHaveBeenCalledWith(
        '/api/dynamic/order/e1',
        undefined,
        undefined,
        undefined,
      );
    });

    it('throws on failure', async () => {
      delMock.mockResolvedValue(fail('Forbidden'));

      await expect(dynamicService.deleteById('order', 'e1')).rejects.toThrow('Forbidden');
    });
  });

  // ── batchCreate ──────────────────────────────────────────────────────────────

  describe('batchCreate', () => {
    it('POSTs dataList to /api/dynamic/:code/batch', async () => {
      const entities = [{ id: 'b1' }, { id: 'b2' }];
      postMock.mockResolvedValue(ok(entities));

      const result = await dynamicService.batchCreate('order', [{ name: 'A' }, { name: 'B' }]);

      expect(postMock).toHaveBeenCalledWith(
        '/api/dynamic/order/batch',
        { dataList: [{ name: 'A' }, { name: 'B' }] },
        undefined,
        undefined,
      );
      expect(result).toHaveLength(2);
    });
  });

  // ── batchUpdate ──────────────────────────────────────────────────────────────

  describe('batchUpdate', () => {
    it('PUTs updates to /api/dynamic/:code/batch', async () => {
      const entities = [{ id: 'b1', name: 'Updated A' }];
      putMock.mockResolvedValue(ok(entities));

      const updates = [{ id: 'b1', data: { name: 'Updated A' } }];
      const result = await dynamicService.batchUpdate('order', updates);

      expect(putMock).toHaveBeenCalledWith(
        '/api/dynamic/order/batch',
        { updates },
        undefined,
        undefined,
      );
      expect(result).toHaveLength(1);
    });
  });

  // ── batchDelete ──────────────────────────────────────────────────────────────

  describe('batchDelete', () => {
    it('DELs /api/dynamic/:code/batch with ids payload', async () => {
      delMock.mockResolvedValue(ok(null));

      await expect(
        dynamicService.batchDelete('order', ['b1', 'b2']),
      ).resolves.toBeUndefined();

      expect(delMock).toHaveBeenCalledWith(
        '/api/dynamic/order/batch',
        { ids: ['b1', 'b2'] },
        undefined,
        undefined,
      );
    });

    it('throws on failure', async () => {
      delMock.mockResolvedValue(fail('Batch delete failed'));

      await expect(dynamicService.batchDelete('order', ['x'])).rejects.toThrow(
        'Batch delete failed',
      );
    });
  });

  // ── validate ─────────────────────────────────────────────────────────────────

  describe('validate', () => {
    it('POSTs to /api/dynamic/:code/validate', async () => {
      const validationResult = { valid: true, errors: {} };
      postMock.mockResolvedValue(ok(validationResult));

      const result = await dynamicService.validate('order', { amount: -1 });

      expect(postMock).toHaveBeenCalledWith(
        '/api/dynamic/order/validate',
        { amount: -1 },
        undefined,
        undefined,
      );
      expect(result.valid).toBe(true);
    });

    it('returns invalid result when validation fails on server', async () => {
      const validationResult = { valid: false, errors: { amount: 'Must be positive' } };
      postMock.mockResolvedValue(ok(validationResult));

      const result = await dynamicService.validate('order', { amount: -1 });

      expect(result.valid).toBe(false);
      expect(result.errors.amount).toBe('Must be positive');
    });
  });

  // ── getFieldOptions ──────────────────────────────────────────────────────────

  describe('getFieldOptions', () => {
    it('POSTs to /api/dynamic/:entityCode/fields/:fieldName/options', async () => {
      const options = [{ value: 'ACTIVE', label: 'Active' }];
      postMock.mockResolvedValue(ok(options));

      const result = await dynamicService.getFieldOptions({
        entityCode: 'order',
        fieldName: 'status',
        keyword: 'act',
      });

      expect(postMock).toHaveBeenCalledWith(
        '/api/dynamic/order/fields/status/options',
        { keyword: 'act' },
        undefined,
        undefined,
      );
      expect(result).toEqual(options);
    });
  });

  // ── executeCustomQuery ───────────────────────────────────────────────────────

  describe('executeCustomQuery', () => {
    it('POSTs to /api/dynamic/query', async () => {
      const pageResult = { records: [], total: 0, page: 0, pageSize: 20, totalPages: 0 };
      postMock.mockResolvedValue(ok(pageResult));

      const queryReq = { filters: [{ field: 'status', value: 'ACTIVE' }], page: 0, size: 20 };
      const result = await dynamicService.executeCustomQuery(queryReq as any);

      expect(postMock).toHaveBeenCalledWith('/api/dynamic/query', queryReq, undefined, undefined);
      expect(result).toEqual(pageResult);
    });
  });

  // ── executeCustomAction ──────────────────────────────────────────────────────

  describe('executeCustomAction', () => {
    it('POSTs to /api/dynamic/:code/actions/:action', async () => {
      const actionResult = { triggered: true };
      postMock.mockResolvedValue(ok(actionResult));

      const result = await dynamicService.executeCustomAction('order', 'approve', { note: 'ok' });

      expect(postMock).toHaveBeenCalledWith(
        '/api/dynamic/order/actions/approve',
        { note: 'ok' },
        undefined,
        undefined,
      );
      expect(result).toEqual(actionResult);
    });
  });

  // ── getStats ─────────────────────────────────────────────────────────────────

  describe('getStats', () => {
    it('GETs /api/dynamic/:code/stats', async () => {
      const stats = { total: 42, active: 30, inactive: 12 };
      getMock.mockResolvedValue(ok(stats));

      const result = await dynamicService.getStats('order');

      expect(getMock).toHaveBeenCalledWith(
        '/api/dynamic/order/stats',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual(stats);
    });
  });

  // ── getRelatedData ────────────────────────────────────────────────────────────

  describe('getRelatedData', () => {
    it('POSTs to /api/dynamic/:entity/relations/:field/:target', async () => {
      const pageResult = { records: [{ id: 'r1' }], total: 1, page: 0, pageSize: 20, totalPages: 1 };
      postMock.mockResolvedValue(ok(pageResult));

      const result = await dynamicService.getRelatedData({
        entityCode: 'order',
        relationField: 'items',
        targetEntityCode: 'product',
        page: 0,
        size: 20,
      } as any);

      expect(postMock).toHaveBeenCalledWith(
        '/api/dynamic/order/relations/items/product',
        { page: 0, size: 20 },
        undefined,
        undefined,
      );
      expect(result.records).toHaveLength(1);
    });
  });

  // ── getPageMetadata ───────────────────────────────────────────────────────────

  describe('getPageMetadata', () => {
    it('GETs /api/schemas/:code/page', async () => {
      const schema = { entityCode: 'order', fields: [] };
      getMock.mockResolvedValue(ok(schema));

      const result = await dynamicService.getPageMetadata('order');

      expect(getMock).toHaveBeenCalledWith(
        '/api/schemas/order/page',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual(schema);
    });
  });
});
