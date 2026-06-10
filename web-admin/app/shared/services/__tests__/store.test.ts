/**
 * Unit tests for store service
 * Validates URL construction, payload forwarding, and response handling.
 * store.ts uses fetchResult + ResultHelper.isSuccess + result.message (not result.desc).
 * getTokenFromRequest is mocked to return a fixed token.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { fetchResultMock, getTokenMock } = vi.hoisted(() => ({
  fetchResultMock: vi.fn(),
  getTokenMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: fetchResultMock,
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('~/shared/services/session.js', () => ({
  getTokenFromRequest: getTokenMock,
}));

import {
  getStoreList,
  getStoreByPid,
  createStore,
  updateStore,
  deleteStore,
  batchDeleteStores,
  getStoresByTenant,
  checkStoreCodeUnique,
} from '../store';

function ok<T>(data: T) {
  // store.ts checks result.message (not result.desc)
  return { code: '0', message: '', data };
}

function fail(message = 'Server error') {
  return { code: '1', message, data: null };
}

const FAKE_REQUEST = new Request('http://localhost/');
const TOKEN = 'test-jwt-token';

const STORE = {
  pid: 's1',
  name: 'Main Store',
  code: 'MAIN',
  type: 'retail',
  status: 'active',
  createdAt: '2024-01-01',
  updatedAt: '2024-01-01',
};

const PAGINATION = { pageNum: 1, pageSize: 20 };

describe('store service', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
    getTokenMock.mockReset();
    getTokenMock.mockResolvedValue(TOKEN);
  });

  // ── getStoreList ──────────────────────────────────────────────────────────────

  describe('getStoreList', () => {
    it('GETs /api/stores with pagination params', async () => {
      const pageResult = { records: [STORE], total: 1, page: 1, pageSize: 20, totalPages: 1 };
      fetchResultMock.mockResolvedValue(ok(pageResult));

      const result = await getStoreList(FAKE_REQUEST, PAGINATION);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/stores', {
        method: 'get',
        params: { pageNum: 1, pageSize: 20, keyword: undefined },
        token: TOKEN,
      });
      expect(result).toEqual(pageResult);
    });

    it('passes keyword when provided', async () => {
      fetchResultMock.mockResolvedValue(ok({ records: [], total: 0 }));

      await getStoreList(FAKE_REQUEST, { pageNum: 1, pageSize: 10, keyword: 'main' });

      const params = fetchResultMock.mock.calls[0][1].params;
      expect(params.keyword).toBe('main');
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Unauthorized'));

      await expect(getStoreList(FAKE_REQUEST, PAGINATION)).rejects.toThrow('Unauthorized');
    });
  });

  // ── getStoreByPid ─────────────────────────────────────────────────────────────

  describe('getStoreByPid', () => {
    it('GETs /api/stores/:pid', async () => {
      fetchResultMock.mockResolvedValue(ok(STORE));

      const result = await getStoreByPid(FAKE_REQUEST, 's1');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/stores/s1', {
        method: 'get',
        params: {},
        token: TOKEN,
      });
      expect(result).toEqual(STORE);
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Not found'));

      await expect(getStoreByPid(FAKE_REQUEST, 'x')).rejects.toThrow('Not found');
    });
  });

  // ── createStore ───────────────────────────────────────────────────────────────

  describe('createStore', () => {
    it('POSTs to /api/stores with store data', async () => {
      fetchResultMock.mockResolvedValue(ok(STORE));

      const storeData = { name: 'Main Store', code: 'MAIN', type: 'retail', status: 'active' };
      const result = await createStore(FAKE_REQUEST, storeData);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/stores', {
        method: 'post',
        params: storeData,
        token: TOKEN,
      });
      expect(result).toEqual(STORE);
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Code already exists'));

      await expect(
        createStore(FAKE_REQUEST, { name: 'X', code: 'DUP', type: 'retail', status: 'active' }),
      ).rejects.toThrow('Code already exists');
    });
  });

  // ── updateStore ───────────────────────────────────────────────────────────────

  describe('updateStore', () => {
    it('PUTs to /api/stores/:pid with update data', async () => {
      const updated = { ...STORE, name: 'Updated Store' };
      fetchResultMock.mockResolvedValue(ok(updated));

      const result = await updateStore(FAKE_REQUEST, 's1', { name: 'Updated Store' });

      expect(fetchResultMock).toHaveBeenCalledWith('/api/stores/s1', {
        method: 'put',
        params: { name: 'Updated Store' },
        token: TOKEN,
      });
      expect(result?.name).toBe('Updated Store');
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Conflict'));

      await expect(updateStore(FAKE_REQUEST, 's1', {})).rejects.toThrow('Conflict');
    });
  });

  // ── deleteStore ───────────────────────────────────────────────────────────────

  describe('deleteStore', () => {
    it('DELETEs /api/stores/:pid', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await deleteStore(FAKE_REQUEST, 's1');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/stores/s1', {
        method: 'delete',
        params: {},
        token: TOKEN,
      });
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Has dependencies'));

      await expect(deleteStore(FAKE_REQUEST, 's1')).rejects.toThrow('Has dependencies');
    });
  });

  // ── batchDeleteStores ─────────────────────────────────────────────────────────

  describe('batchDeleteStores', () => {
    it('DELETEs /api/stores/batch with pids', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await batchDeleteStores(FAKE_REQUEST, ['s1', 's2']);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/stores/batch', {
        method: 'delete',
        params: { pids: ['s1', 's2'] },
        token: TOKEN,
      });
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Some stores have orders'));

      await expect(batchDeleteStores(FAKE_REQUEST, ['s1'])).rejects.toThrow(
        'Some stores have orders',
      );
    });
  });

  // ── getStoresByTenant ──────────────────────────────────────────────────────────

  describe('getStoresByTenant', () => {
    it('GETs /api/stores/tenant/:tenantId', async () => {
      fetchResultMock.mockResolvedValue(ok([STORE]));

      const result = await getStoresByTenant(FAKE_REQUEST, 'tenant-1');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/stores/tenant/tenant-1', {
        method: 'get',
        params: {},
        token: TOKEN,
      });
      expect(result).toHaveLength(1);
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Tenant not found'));

      await expect(getStoresByTenant(FAKE_REQUEST, 'bad')).rejects.toThrow('Tenant not found');
    });
  });

  // ── checkStoreCodeUnique ───────────────────────────────────────────────────────

  describe('checkStoreCodeUnique', () => {
    it('GETs /api/stores/check-code/:code without excludePid', async () => {
      fetchResultMock.mockResolvedValue(ok(true));

      const result = await checkStoreCodeUnique(FAKE_REQUEST, 'MAIN');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/stores/check-code/MAIN', {
        method: 'get',
        params: {},
        token: TOKEN,
      });
      expect(result).toBe(true);
    });

    it('includes excludePid when provided', async () => {
      fetchResultMock.mockResolvedValue(ok(true));

      await checkStoreCodeUnique(FAKE_REQUEST, 'MAIN', 'old-s1');

      const params = fetchResultMock.mock.calls[0][1].params;
      expect(params.excludePid).toBe('old-s1');
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Check failed'));

      await expect(checkStoreCodeUnique(FAKE_REQUEST, 'MAIN')).rejects.toThrow('Check failed');
    });
  });
});
