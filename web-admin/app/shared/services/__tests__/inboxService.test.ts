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
  return { code: '0', message: '', data };
}

function fail(message = 'Server error') {
  return { code: '1', message, data: null };
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

    it('rejects business failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Unauthorized'));

      await expect(listInboxItems({})).rejects.toThrow('Unauthorized');
    });

    it('rejects missing page', async () => {
      fetchResultMock.mockResolvedValue({ code: '0', data: null });

      await expect(listInboxItems({})).rejects.toThrow('missing data');
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

    it('rejects failed summary', async () => {
      fetchResultMock.mockResolvedValue(fail('Error'));

      await expect(getUnreadSummary()).rejects.toThrow('Error');
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

    it('rejects failed count', async () => {
      fetchResultMock.mockResolvedValue(fail('Error'));

      await expect(getUnreadCount()).rejects.toThrow('Error');
    });

    it('rejects when unread data is null', async () => {
      fetchResultMock.mockResolvedValue({ code: '0', data: null });

      await expect(getUnreadCount()).rejects.toThrow('missing data');
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

    it('rejects business failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Not found'));

      await expect(getInboxItem(999)).rejects.toThrow('Not found');
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

    it('rejects business failure', async () => {
      fetchResultMock.mockResolvedValue(fail('No detail'));

      await expect(getApprovalDetail(1)).rejects.toThrow('No detail');
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


describe('inbox mutation failure boundaries', () => {
  beforeEach(() => fetchResultMock.mockReset());
  const mutations = [
    ['read', () => markRead(1)], ['read-all', () => markAllRead()],
    ['act', () => markActed(1, 'approve')], ['dismiss', () => dismissItem(1)],
    ['approval', () => submitApprovalAction(1, 'approve')],
    ['batch approve', () => batchApprove([1])],
    ['batch reject', () => batchReject([1], 'reason')],
    ['batch read', () => batchMarkRead([1])],
  ] as const;
  it.each(mutations)('%s rejects business errors without refreshing badges', async (_name, invoke) => {
    const listener = vi.fn();
    window.addEventListener('aura:inbox-update', listener);
    try {
      fetchResultMock.mockResolvedValue(fail('Permission denied'));
      await expect(invoke()).rejects.toThrow('Permission denied');
      expect(listener).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener('aura:inbox-update', listener);
    }
  });
  it('publishes zero unread only after successful read-all', async () => {
    const listener = vi.fn();
    window.addEventListener('aura:inbox-update', listener);
    try {
      fetchResultMock.mockResolvedValue(ok(null));
      await markAllRead();
      expect(listener).toHaveBeenCalledOnce();
      expect(listener.mock.calls[0][0].detail).toEqual({ type: 'inbox', count: 0 });
    } finally {
      window.removeEventListener('aura:inbox-update', listener);
    }
  });
  it.each([-1, '7', NaN, 0.5])('rejects invalid unread count %s', async (value) => {
    fetchResultMock.mockResolvedValue(ok(value));
    await expect(getUnreadCount()).rejects.toThrow('invalid');
  });
  it('preserves a valid zero unread count', async () => {
    fetchResultMock.mockResolvedValue(ok(0));
    await expect(getUnreadCount()).resolves.toBe(0);
  });
});
