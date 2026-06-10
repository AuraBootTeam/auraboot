/**
 * Unit tests for inboxService
 * Validates URL construction, payload forwarding, and response handling.
 * inboxService uses fetchResult + ResultHelper.isSuccess (code==='0').
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { fetchResultMock } = vi.hoisted(() => ({
  fetchResultMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: fetchResultMock,
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

import {
  listInboxItems,
  getUnreadSummary,
  getUnreadCount,
  getInboxItem,
  getApprovalDetail,
  markRead,
  markAllRead,
  markActed,
  dismissItem,
  submitApprovalAction,
  batchApprove,
  batchReject,
  batchMarkRead,
} from '../inboxService';

function ok<T>(data: T) {
  return { code: '0', desc: '', data };
}

function fail(desc = 'Server error') {
  return { code: '1', desc, data: null };
}

const ITEM = {
  id: 1,
  tenantId: 10,
  userId: 42,
  itemType: 'approval',
  title: 'Approve order',
  priority: 'high',
  status: 'pending',
  isRead: false,
  createdAt: '2024-01-01',
};

const PAGE = {
  records: [ITEM],
  total: 1,
  current: 1,
  size: 20,
  pages: 1,
};

describe('inboxService', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
  });

  // ── listInboxItems ────────────────────────────────────────────────────────────

  describe('listInboxItems', () => {
    it('GETs /api/inbox with params and returns page', async () => {
      fetchResultMock.mockResolvedValue(ok(PAGE));

      const result = await listInboxItems({ itemType: 'approval', pageNum: 1, pageSize: 20 });

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox', {
        method: 'get',
        params: { itemType: 'approval', pageNum: 1, pageSize: 20 },
      });
      expect(result.records).toHaveLength(1);
      expect(result.total).toBe(1);
    });

    it('returns empty page on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Unauthorized'));

      const result = await listInboxItems({});

      expect(result).toEqual({ records: [], total: 0, current: 1, size: 20, pages: 0 });
    });

    it('returns empty page when data is null', async () => {
      fetchResultMock.mockResolvedValue({ code: '0', data: null });

      const result = await listInboxItems({});

      expect(result.records).toEqual([]);
    });
  });

  // ── getUnreadSummary ──────────────────────────────────────────────────────────

  describe('getUnreadSummary', () => {
    it('GETs /api/inbox/unread-summary', async () => {
      const summary = { approval: 3, task: 1 };
      fetchResultMock.mockResolvedValue(ok(summary));

      const result = await getUnreadSummary();

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/unread-summary', {
        method: 'get',
      });
      expect(result).toEqual(summary);
    });

    it('returns empty object on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Error'));

      const result = await getUnreadSummary();

      expect(result).toEqual({});
    });
  });

  // ── getUnreadCount ────────────────────────────────────────────────────────────

  describe('getUnreadCount', () => {
    it('GETs /api/inbox/unread-count and returns number', async () => {
      fetchResultMock.mockResolvedValue(ok(7));

      const result = await getUnreadCount();

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/unread-count', { method: 'get' });
      expect(result).toBe(7);
    });

    it('returns 0 on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Error'));

      const result = await getUnreadCount();

      expect(result).toBe(0);
    });

    it('returns 0 when data is null', async () => {
      fetchResultMock.mockResolvedValue({ code: '0', data: null });

      const result = await getUnreadCount();

      expect(result).toBe(0);
    });
  });

  // ── getInboxItem ──────────────────────────────────────────────────────────────

  describe('getInboxItem', () => {
    it('GETs /api/inbox/:id and returns item', async () => {
      fetchResultMock.mockResolvedValue(ok(ITEM));

      const result = await getInboxItem(1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/1', { method: 'get' });
      expect(result).toEqual(ITEM);
    });

    it('returns null on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Not found'));

      const result = await getInboxItem(999);

      expect(result).toBeNull();
    });

    it('returns null when data is null', async () => {
      fetchResultMock.mockResolvedValue({ code: '0', data: null });

      const result = await getInboxItem(1);

      expect(result).toBeNull();
    });
  });

  // ── getApprovalDetail ─────────────────────────────────────────────────────────

  describe('getApprovalDetail', () => {
    it('GETs /api/inbox/:id/approval-detail', async () => {
      const detail = { taskId: 'bpm-1', processName: 'Order Approval' };
      fetchResultMock.mockResolvedValue(ok(detail));

      const result = await getApprovalDetail(1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/1/approval-detail', {
        method: 'get',
      });
      expect(result).toEqual(detail);
    });

    it('returns null on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('No detail'));

      const result = await getApprovalDetail(1);

      expect(result).toBeNull();
    });
  });

  // ── markRead ──────────────────────────────────────────────────────────────────

  describe('markRead', () => {
    it('PUTs /api/inbox/:id/read', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await markRead(1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/1/read', { method: 'put' });
    });
  });

  // ── markAllRead ───────────────────────────────────────────────────────────────

  describe('markAllRead', () => {
    it('PUTs /api/inbox/read-all', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await markAllRead();

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/read-all', { method: 'put' });
    });
  });

  // ── markActed ─────────────────────────────────────────────────────────────────

  describe('markActed', () => {
    it('PUTs /api/inbox/:id/act with action and comment', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await markActed(1, 'approve', 'Looks good');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/1/act', {
        method: 'put',
        params: { action: 'approve', comment: 'Looks good' },
      });
    });

    it('omits comment when not provided', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await markActed(1, 'reject');

      const callArgs = fetchResultMock.mock.calls[0][1];
      expect(callArgs.params).not.toHaveProperty('comment');
      expect(callArgs.params.action).toBe('reject');
    });
  });

  // ── dismissItem ───────────────────────────────────────────────────────────────

  describe('dismissItem', () => {
    it('PUTs /api/inbox/:id/dismiss', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await dismissItem(1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/1/dismiss', { method: 'put' });
    });
  });

  // ── submitApprovalAction ──────────────────────────────────────────────────────

  describe('submitApprovalAction', () => {
    it('POSTs to /api/inbox/:id/approval-action with action and comment', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await submitApprovalAction(1, 'approve', 'All good');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/1/approval-action', {
        method: 'post',
        params: { action: 'approve', comment: 'All good' },
      });
    });

    it('omits comment when not provided', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await submitApprovalAction(1, 'reject');

      const callArgs = fetchResultMock.mock.calls[0][1];
      expect(callArgs.params).not.toHaveProperty('comment');
    });
  });

  // ── batchApprove ──────────────────────────────────────────────────────────────

  describe('batchApprove', () => {
    it('POSTs to /api/inbox/batch/approve with ids', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await batchApprove([1, 2, 3]);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/batch/approve', {
        method: 'post',
        params: { ids: [1, 2, 3] },
      });
    });
  });

  // ── batchReject ───────────────────────────────────────────────────────────────

  describe('batchReject', () => {
    it('POSTs to /api/inbox/batch/reject with ids and comment', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await batchReject([1, 2], 'Not approved');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/batch/reject', {
        method: 'post',
        params: { ids: [1, 2], comment: 'Not approved' },
      });
    });
  });

  // ── batchMarkRead ─────────────────────────────────────────────────────────────

  describe('batchMarkRead', () => {
    it('PUTs /api/inbox/batch/read with ids', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await batchMarkRead([5, 6, 7]);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/inbox/batch/read', {
        method: 'put',
        params: { ids: [5, 6, 7] },
      });
    });
  });
});
