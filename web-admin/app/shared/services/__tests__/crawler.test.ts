/**
 * Unit tests for crawler service
 * Validates URL construction, payload forwarding, and response handling.
 * Uses fetchResult + ResultHelper.isSuccess (code==='0') and result.desc.
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

vi.mock('~/shared/services/session', () => ({
  getTokenFromRequest: getTokenMock,
}));

import { getTasks, createTask, executeTask, getArticles } from '../crawler';

function ok<T>(data: T) {
  return { code: '0', desc: '', data };
}

function fail(desc = 'Server error') {
  return { code: '1', desc, data: null };
}

const FAKE_REQUEST = new Request('http://localhost/');
const TOKEN = 'test-jwt-token';

const TASK = {
  id: 'task-1',
  name: 'My Crawler',
  site: 'example.com',
  config: {},
  enabled: true,
  createdAt: '2024-01-01',
};

const ARTICLE = {
  id: 1,
  source: 'example.com',
  url: 'https://example.com/article/1',
  title: 'Test Article',
  contentText: 'Content here',
  createdAt: '2024-01-01',
};

describe('crawler service', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
    getTokenMock.mockReset();
    getTokenMock.mockResolvedValue(TOKEN);
  });

  // ── getTasks ──────────────────────────────────────────────────────────────────

  describe('getTasks', () => {
    it('GETs /api/crawler/tasks/templates with default page and size', async () => {
      fetchResultMock.mockResolvedValue(ok({ records: [TASK], total: 1 }));

      const result = await getTasks(FAKE_REQUEST);

      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/crawler/tasks/templates?page=1&size=100',
        { method: 'get', token: TOKEN },
      );
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe('task-1');
    });

    it('uses provided page and size', async () => {
      fetchResultMock.mockResolvedValue(ok({ records: [], total: 0 }));

      await getTasks(FAKE_REQUEST, 2, 50);

      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/crawler/tasks/templates?page=2&size=50',
        { method: 'get', token: TOKEN },
      );
    });

    it('returns empty array on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Unauthorized'));

      const result = await getTasks(FAKE_REQUEST);

      expect(result).toEqual([]);
    });

    it('returns empty array when data.records is missing', async () => {
      fetchResultMock.mockResolvedValue(ok({ total: 0 }));

      const result = await getTasks(FAKE_REQUEST);

      expect(result).toEqual([]);
    });
  });

  // ── createTask ────────────────────────────────────────────────────────────────

  describe('createTask', () => {
    it('POSTs to /api/crawler/tasks/templates with task data', async () => {
      fetchResultMock.mockResolvedValue(ok(TASK));

      const taskData = { name: 'My Crawler', site: 'example.com', config: {} };
      const result = await createTask(FAKE_REQUEST, taskData);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/crawler/tasks/templates', {
        method: 'post',
        params: taskData,
        token: TOKEN,
      });
      expect(result).toEqual(TASK);
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Duplicate name'));

      await expect(
        createTask(FAKE_REQUEST, { name: 'dup', site: 'x.com', config: {} }),
      ).rejects.toThrow('Duplicate name');
    });
  });

  // ── executeTask ───────────────────────────────────────────────────────────────

  describe('executeTask', () => {
    it('POSTs to /api/crawler/tasks/templates/:id/execute', async () => {
      fetchResultMock.mockResolvedValue(ok({ id: 'run-1' }));

      await executeTask(FAKE_REQUEST, 'task-1');

      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/crawler/tasks/templates/task-1/execute',
        { method: 'post', token: TOKEN },
      );
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Task not found'));

      await expect(executeTask(FAKE_REQUEST, 'bad-id')).rejects.toThrow('Task not found');
    });
  });

  // ── getArticles ───────────────────────────────────────────────────────────────

  describe('getArticles', () => {
    it('GETs /api/crawler/articles without filters', async () => {
      fetchResultMock.mockResolvedValue(ok({ records: [ARTICLE], total: 1 }));

      const result = await getArticles(FAKE_REQUEST);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/crawler/articles', {
        method: 'get',
        token: TOKEN,
      });
      expect(result).toHaveLength(1);
      expect(result[0].title).toBe('Test Article');
    });

    it('appends source filter to URL', async () => {
      fetchResultMock.mockResolvedValue(ok({ records: [], total: 0 }));

      await getArticles(FAKE_REQUEST, 'example.com');

      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/crawler/articles?source=example.com',
        { method: 'get', token: TOKEN },
      );
    });

    it('appends both source and stock filters', async () => {
      fetchResultMock.mockResolvedValue(ok({ records: [], total: 0 }));

      await getArticles(FAKE_REQUEST, 'site.com', 'AAPL');

      const calledUrl: string = fetchResultMock.mock.calls[0][0];
      expect(calledUrl).toContain('source=site.com');
      expect(calledUrl).toContain('stock=AAPL');
    });

    it('returns empty array on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('DB error'));

      const result = await getArticles(FAKE_REQUEST);

      expect(result).toEqual([]);
    });

    it('returns empty array when data.records is missing', async () => {
      fetchResultMock.mockResolvedValue(ok({ total: 0 }));

      const result = await getArticles(FAKE_REQUEST);

      expect(result).toEqual([]);
    });
  });
});
