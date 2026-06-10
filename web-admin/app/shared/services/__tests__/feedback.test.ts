/**
 * Unit tests for feedback service
 * Validates URL construction, payload forwarding, and response handling.
 * Uses fetchResult (code==='0') + getTokenFromRequest.
 * Note: listFeedback checks result.code !== '0' and returns fallback (no throw).
 *       createFeedback / toggleVote / addComment / updateFeedbackStatus / deleteFeedback throw on code !== '0'.
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

vi.mock('../session', () => ({
  getTokenFromRequest: getTokenMock,
}));

import {
  listFeedback,
  createFeedback,
  toggleVote,
  getComments,
  addComment,
  updateFeedbackStatus,
  deleteFeedback,
} from '../feedback';

function ok<T>(data: T) {
  return { code: '0', desc: 'OK', data };
}

function fail(desc = 'Server error') {
  return { code: '1', desc, data: null };
}

const FAKE_REQUEST = new Request('http://localhost/');
const TOKEN = 'test-jwt';

const FEEDBACK_ITEM = {
  id: 1,
  pid: 'fb-1',
  userId: 42,
  userName: 'Alice',
  type: 'feature' as const,
  title: 'Add dark mode',
  description: 'Please add dark mode',
  status: 'open' as const,
  priority: 'medium' as const,
  voteCount: 10,
  votedByCurrentUser: false,
  commentCount: 2,
  metadata: null,
  createdAt: '2024-01-01',
  updatedAt: '2024-01-01',
};

const COMMENT = {
  id: 1,
  feedbackId: 1,
  userId: 42,
  userName: 'Alice',
  content: 'Great idea!',
  createdAt: '2024-01-01',
};

describe('feedback service', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
    getTokenMock.mockReset();
    getTokenMock.mockResolvedValue(TOKEN);
  });

  // ── listFeedback ──────────────────────────────────────────────────────────────

  describe('listFeedback', () => {
    it('GETs /api/feedback with default params', async () => {
      const page = {
        records: [FEEDBACK_ITEM],
        total: 1,
        size: 20,
        current: 1,
        pages: 1,
      };
      fetchResultMock.mockResolvedValue(ok(page));

      const result = await listFeedback(FAKE_REQUEST);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/feedback', {
        method: 'get',
        params: {
          pageNum: 1,
          pageSize: 20,
          sortBy: 'voteCount',
          sortOrder: 'desc',
        },
        token: TOKEN,
      });
      expect(result.records).toHaveLength(1);
      expect(result.total).toBe(1);
    });

    it('passes optional filters when provided', async () => {
      fetchResultMock.mockResolvedValue(ok({ records: [], total: 0, size: 10, current: 1, pages: 0 }));

      await listFeedback(FAKE_REQUEST, {
        type: 'bug',
        status: 'open',
        pageNum: 2,
        pageSize: 10,
        sortBy: 'createdAt',
        sortOrder: 'asc',
      });

      const params = fetchResultMock.mock.calls[0][1].params;
      expect(params.type).toBe('bug');
      expect(params.status).toBe('open');
      expect(params.pageNum).toBe(2);
      expect(params.pageSize).toBe(10);
      expect(params.sortBy).toBe('createdAt');
    });

    it('omits undefined optional filters', async () => {
      fetchResultMock.mockResolvedValue(ok({ records: [], total: 0, size: 20, current: 1, pages: 0 }));

      await listFeedback(FAKE_REQUEST);

      const params = fetchResultMock.mock.calls[0][1].params;
      expect(params).not.toHaveProperty('type');
      expect(params).not.toHaveProperty('status');
    });

    it('returns empty page on failure (no throw)', async () => {
      fetchResultMock.mockResolvedValue(fail('Unauthorized'));

      const result = await listFeedback(FAKE_REQUEST);

      expect(result).toEqual({ records: [], total: 0, size: 20, current: 1, pages: 0 });
    });

    it('returns empty page when data is null', async () => {
      fetchResultMock.mockResolvedValue({ code: '0', data: null });

      const result = await listFeedback(FAKE_REQUEST);

      expect(result.records).toEqual([]);
    });
  });

  // ── createFeedback ────────────────────────────────────────────────────────────

  describe('createFeedback', () => {
    it('POSTs to /api/feedback with feedback data', async () => {
      fetchResultMock.mockResolvedValue(ok(FEEDBACK_ITEM));

      const data = {
        type: 'feature' as const,
        title: 'Add dark mode',
        description: 'Please',
      };
      const result = await createFeedback(FAKE_REQUEST, data);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/feedback', {
        method: 'post',
        params: data,
        token: TOKEN,
      });
      expect(result).toEqual(FEEDBACK_ITEM);
    });

    it('throws on failure (String(data) is "null" when data is null)', async () => {
      // The service uses String(result.data) || fallback; String(null) === "null" (truthy)
      // so the thrown message is "null", not the fallback string.
      fetchResultMock.mockResolvedValue(fail('Validation error'));

      await expect(
        createFeedback(FAKE_REQUEST, { type: 'bug', title: 'x' }),
      ).rejects.toThrow('null');
    });
  });

  // ── toggleVote ────────────────────────────────────────────────────────────────

  describe('toggleVote', () => {
    it('POSTs to /api/feedback/:id/vote and returns voted boolean', async () => {
      fetchResultMock.mockResolvedValue(ok({ voted: true }));

      const result = await toggleVote(FAKE_REQUEST, 1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/feedback/1/vote', {
        method: 'post',
        token: TOKEN,
      });
      expect(result).toBe(true);
    });

    it('returns false when voted is false', async () => {
      fetchResultMock.mockResolvedValue(ok({ voted: false }));

      const result = await toggleVote(FAKE_REQUEST, 1);

      expect(result).toBe(false);
    });

    it('throws on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Already voted'));

      await expect(toggleVote(FAKE_REQUEST, 1)).rejects.toThrow('Failed to toggle vote');
    });
  });

  // ── getComments ───────────────────────────────────────────────────────────────

  describe('getComments', () => {
    it('GETs /api/feedback/:id/comments', async () => {
      fetchResultMock.mockResolvedValue(ok([COMMENT]));

      const result = await getComments(FAKE_REQUEST, 1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/feedback/1/comments', {
        method: 'get',
        token: TOKEN,
      });
      expect(result).toHaveLength(1);
    });

    it('returns empty array on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Error'));

      const result = await getComments(FAKE_REQUEST, 1);

      expect(result).toEqual([]);
    });
  });

  // ── addComment ────────────────────────────────────────────────────────────────

  describe('addComment', () => {
    it('POSTs to /api/feedback/:id/comments with comment data', async () => {
      fetchResultMock.mockResolvedValue(ok(COMMENT));

      const data = { content: 'Great idea!' };
      const result = await addComment(FAKE_REQUEST, 1, data);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/feedback/1/comments', {
        method: 'post',
        params: data,
        token: TOKEN,
      });
      expect(result).toEqual(COMMENT);
    });

    it('throws on failure (String(data) is "null" when data is null)', async () => {
      fetchResultMock.mockResolvedValue(fail('Bad request'));

      await expect(addComment(FAKE_REQUEST, 1, { content: '' })).rejects.toThrow('null');
    });
  });

  // ── updateFeedbackStatus ──────────────────────────────────────────────────────

  describe('updateFeedbackStatus', () => {
    it('PUTs to /api/feedback/:id/status with new status', async () => {
      const updated = { ...FEEDBACK_ITEM, status: 'resolved' as const };
      fetchResultMock.mockResolvedValue(ok(updated));

      const result = await updateFeedbackStatus(FAKE_REQUEST, 1, 'resolved');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/feedback/1/status', {
        method: 'put',
        params: { status: 'resolved' },
        token: TOKEN,
      });
      expect(result?.status).toBe('resolved');
    });

    it('throws on failure (String(data) is "null" when data is null)', async () => {
      fetchResultMock.mockResolvedValue(fail('Forbidden'));

      await expect(updateFeedbackStatus(FAKE_REQUEST, 1, 'closed')).rejects.toThrow('null');
    });
  });

  // ── deleteFeedback ────────────────────────────────────────────────────────────

  describe('deleteFeedback', () => {
    it('DELETEs /api/feedback/:id', async () => {
      fetchResultMock.mockResolvedValue(ok(true));

      await deleteFeedback(FAKE_REQUEST, 1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/feedback/1', {
        method: 'delete',
        token: TOKEN,
      });
    });

    it('throws on failure (String(data) is "null" when data is null)', async () => {
      fetchResultMock.mockResolvedValue(fail('Not found'));

      await expect(deleteFeedback(FAKE_REQUEST, 1)).rejects.toThrow('null');
    });
  });
});
