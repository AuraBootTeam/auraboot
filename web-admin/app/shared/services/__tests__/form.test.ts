/**
 * Unit tests for form service
 * Tests fetchResult-based functions (getFormSchema, getFormData, submitFormData,
 * saveFormDesign, getItemList, submitSearchQuery).
 * Translation reads exercise authenticated and anonymous request boundaries.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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
  getI18nData,
  getFormSchema,
  getFormData,
  submitFormData,
  saveFormDesign,
  getItemList,
  submitSearchQuery,
} from '../form';

function ok<T>(data: T) {
  return { code: '0', desc: '', data };
}

function fail(desc = 'Error') {
  return { code: '1', desc, data: null };
}

const FAKE_REQUEST = new Request('http://localhost/');
const TOKEN = 'test-jwt';

describe('form service', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
    getTokenMock.mockReset();
    getTokenMock.mockResolvedValue(TOKEN);
  });

  // ── getFormSchema ─────────────────────────────────────────────────────────────

  describe('getFormSchema', () => {
    it('GETs /api/view/new/:id and returns schema on success', async () => {
      const schema = { fields: [], layout: {} };
      fetchResultMock.mockResolvedValue(ok(schema));

      const result = await getFormSchema('form-1', FAKE_REQUEST);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/view/new/form-1', {
        method: 'get',
        params: {},
        token: TOKEN,
      });
      expect(result).toEqual(schema);
    });

    it('returns null on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Not found'));

      const result = await getFormSchema('bad-id', FAKE_REQUEST);

      expect(result).toBeNull();
    });

    it('returns null when data is null', async () => {
      fetchResultMock.mockResolvedValue({ code: '0', data: null });

      const result = await getFormSchema('form-1', FAKE_REQUEST);

      expect(result).toBeNull();
    });
  });

  // ── getFormData ───────────────────────────────────────────────────────────────

  describe('getFormData', () => {
    it('GETs /api/view/:id and returns data on success', async () => {
      const formData = { name: 'Alice', email: 'alice@example.com' };
      fetchResultMock.mockResolvedValue(ok(formData));

      const result = await getFormData('record-1', FAKE_REQUEST);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/view/record-1', {
        method: 'get',
        params: {},
        token: TOKEN,
      });
      expect(result).toEqual(formData);
    });

    it('returns null on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Forbidden'));

      const result = await getFormData('record-1', FAKE_REQUEST);

      expect(result).toBeNull();
    });
  });

  // ── submitFormData ────────────────────────────────────────────────────────────

  describe('submitFormData', () => {
    it('POSTs to /api/view/create with form data', async () => {
      const responseData = { id: 'new-1', name: 'Alice' };
      fetchResultMock.mockResolvedValue(ok(responseData));

      const data = { name: 'Alice', email: 'alice@example.com' };
      const result = await submitFormData(data, FAKE_REQUEST);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/view/create', {
        method: 'post',
        params: data,
        token: TOKEN,
      });
      // submitFormData returns the raw result (not just .data)
      expect(result).toEqual(ok(responseData));
    });

    it('returns the result object even on failure (callers check code)', async () => {
      const errResult = fail('Validation error');
      fetchResultMock.mockResolvedValue(errResult);

      const result = await submitFormData({ name: '' }, FAKE_REQUEST);

      expect(result).toEqual(errResult);
    });
  });

  // ── saveFormDesign ────────────────────────────────────────────────────────────

  describe('saveFormDesign', () => {
    it('POSTs to /api/page/schema/create with design data', async () => {
      const saved = { pid: 'schema-1', fields: [] };
      fetchResultMock.mockResolvedValue(ok(saved));

      const designData = { name: 'Order Form', fields: [] };
      const result = await saveFormDesign(designData, FAKE_REQUEST);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/page/schema/create', {
        method: 'post',
        params: designData,
        token: TOKEN,
      });
      expect(result).toEqual(ok(saved));
    });
  });

  // ── getItemList ───────────────────────────────────────────────────────────────

  describe('getItemList', () => {
    it('GETs /api/page/list/{id} with id and returns list on success', async () => {
      const listData = [{ id: '1', name: 'Item A' }];
      fetchResultMock.mockResolvedValue(ok(listData));

      const result = await getItemList(FAKE_REQUEST, 'page-1');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/page/list/{id}', {
        method: 'get',
        params: { id: 'page-1' },
        token: TOKEN,
      });
      expect(result).toEqual(listData);
    });

    it('returns null on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Not found'));

      const result = await getItemList(FAKE_REQUEST, 'bad-id');

      expect(result).toBeNull();
    });
  });

  // ── submitSearchQuery ─────────────────────────────────────────────────────────

  describe('submitSearchQuery', () => {
    it('GETs /api/view/list/{id} with form data entries and id', async () => {
      const searchResult = [{ id: '1', name: 'Result A' }];
      fetchResultMock.mockResolvedValue(ok(searchResult));

      const formData = new FormData();
      formData.append('keyword', 'test');
      formData.append('status', 'active');

      const result = await submitSearchQuery(formData, FAKE_REQUEST, 'list-1');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/view/list/{id}', {
        method: 'get',
        params: {
          id: 'list-1',
          keyword: 'test',
          status: 'active',
        },
        token: TOKEN,
      });
      expect(result).toEqual(searchResult);
    });

    it('returns null on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Error'));

      const result = await submitSearchQuery(new FormData(), FAKE_REQUEST);

      expect(result).toBeNull();
    });
  });
});


describe('translation request identity', () => {
  beforeEach(() => {
    getTokenMock.mockReset();
    getTokenMock.mockResolvedValue(TOKEN);
    vi.stubEnv('BFF_INTERNAL_URL', 'http://127.0.0.1:5445');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0', data: { label: 'translated' } }),
    }));
  });
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('forwards the current token for tenant translations', async () => {
    expect(await getI18nData('zh-CN', FAKE_REQUEST)).toEqual({ label: 'translated' });
    expect(fetch).toHaveBeenCalledWith('http://127.0.0.1:5445/api/i18n/zh-CN',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer ' + TOKEN }) }));
  });

  it('public shell translations never read or forward an admin token', async () => {
    expect(await getI18nData('zh-CN', FAKE_REQUEST, false)).toEqual({ label: 'translated' });
    expect(getTokenMock).not.toHaveBeenCalled();
    const options = vi.mocked(fetch).mock.calls[0][1];
    expect(options?.headers).not.toHaveProperty('Authorization');
  });

  it('an anonymous tenant-shell request has no authorization header', async () => {
    getTokenMock.mockResolvedValue(null);
    await getI18nData('zh-CN', FAKE_REQUEST);
    expect(vi.mocked(fetch).mock.calls[0][1]?.headers).not.toHaveProperty('Authorization');
  });
});
