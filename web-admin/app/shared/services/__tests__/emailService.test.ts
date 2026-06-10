/**
 * Unit tests for emailService
 *
 * emailService calls `fetchResult` from `~/shared/services/http-client`.
 * ResultHelper.isSuccess checks code === '0'.
 *
 * Pattern: success result → code:'0', data: <value>
 *           failure result → code:'500', data: null
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
  getOAuthUrl,
  listAccounts,
  updateSyncMode,
  disconnectAccount,
  triggerSync,
  listMembers,
  addMember,
  removeMember,
  listMessages,
  getThread,
  getMessage,
  sendEmail,
  markMessageRead,
  linkToRecord,
  unlinkRecord,
  getMessagesByRecord,
  getMessageLinks,
  getTrackingStats,
  listSequences,
  createSequence,
  getSequence,
  updateSequence,
  updateSequenceStatus,
  addStep,
  updateStep,
  deleteStep,
  listSteps,
  enrollContacts,
  listEnrollments,
  pauseEnrollment,
  resumeEnrollment,
} from '../emailService';

const ok = <T>(data: T) => ({ code: '0', desc: 'OK', data });
const fail = (desc = 'error') => ({ code: '500', desc, data: null });

describe('emailService', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
  });

  // ── Account APIs ─────────────────────────────────────────────────────────────

  describe('getOAuthUrl', () => {
    it('calls GET /api/email/accounts/oauth/url and returns data', async () => {
      fetchResultMock.mockResolvedValue(ok('https://accounts.google.com/oauth?...'));

      const result = await getOAuthUrl();

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/accounts/oauth/url', { method: 'get' });
      expect(result).toBe('https://accounts.google.com/oauth?...');
    });

    it('returns null when response is not success', async () => {
      fetchResultMock.mockResolvedValue(fail('Not configured'));

      const result = await getOAuthUrl();

      expect(result).toBeNull();
    });
  });

  describe('listAccounts', () => {
    it('calls GET /api/email/accounts and returns accounts', async () => {
      const accounts = [{ id: 1, emailAddress: 'user@test.com' }];
      fetchResultMock.mockResolvedValue(ok(accounts));

      const result = await listAccounts();

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/accounts', { method: 'get' });
      expect(result).toEqual(accounts);
    });

    it('returns empty array on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Auth error'));

      const result = await listAccounts();

      expect(result).toEqual([]);
    });
  });

  describe('updateSyncMode', () => {
    it('calls PUT /api/email/accounts/:id/sync-mode with syncMode param', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await updateSyncMode(5, 'full');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/accounts/5/sync-mode', { method: 'put', params: { syncMode: 'full' } });
    });
  });

  describe('disconnectAccount', () => {
    it('calls POST /api/email/accounts/:id/disconnect', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await disconnectAccount(3);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/accounts/3/disconnect', { method: 'post' });
    });
  });

  describe('triggerSync', () => {
    it('calls POST /api/email/accounts/:id/sync', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await triggerSync(7);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/accounts/7/sync', { method: 'post' });
    });
  });

  describe('listMembers', () => {
    it('calls GET /api/email/accounts/:id/members and returns data', async () => {
      const members = [{ id: 1, accountId: 5, userId: 2, role: 'owner' }];
      fetchResultMock.mockResolvedValue(ok(members));

      const result = await listMembers(5);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/accounts/5/members', { method: 'get' });
      expect(result).toEqual(members);
    });

    it('returns empty array when no data', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await listMembers(5)).toEqual([]);
    });
  });

  describe('addMember', () => {
    it('calls POST /api/email/accounts/:id/members with userId and role', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await addMember(5, 10, 'member');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/accounts/5/members', {
        method: 'post',
        params: { userId: 10, role: 'member' },
      });
    });
  });

  describe('removeMember', () => {
    it('calls DELETE /api/email/accounts/:accountId/members/:memberId', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await removeMember(5, 20);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/accounts/5/members/20', { method: 'delete' });
    });
  });

  // ── Message APIs ──────────────────────────────────────────────────────────────

  describe('listMessages', () => {
    it('calls GET /api/email/messages with params and returns page', async () => {
      const page = { records: [], total: 0, current: 1, size: 20, pages: 0 };
      fetchResultMock.mockResolvedValue(ok(page));

      const result = await listMessages({ pageNum: 1, pageSize: 20 });

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/messages', {
        method: 'get',
        params: { pageNum: 1, pageSize: 20 },
      });
      expect(result).toEqual(page);
    });

    it('returns empty page on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      const result = await listMessages({});

      expect(result).toEqual({ records: [], total: 0, current: 1, size: 20, pages: 0 });
    });
  });

  describe('getThread', () => {
    it('GETs /api/email/threads/:threadId', async () => {
      const thread = { threadId: 'thread-1', messages: [] };
      fetchResultMock.mockResolvedValue(ok(thread));

      const result = await getThread('thread-1');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/threads/thread-1', { method: 'get' });
      expect(result).toEqual(thread);
    });

    it('returns null on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await getThread('bad')).toBeNull();
    });
  });

  describe('getMessage', () => {
    it('GETs /api/email/messages/:id', async () => {
      const msg = { id: 1, subject: 'Hello' };
      fetchResultMock.mockResolvedValue(ok(msg));

      const result = await getMessage(1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/messages/1', { method: 'get' });
      expect(result).toEqual(msg);
    });

    it('returns null when not found', async () => {
      fetchResultMock.mockResolvedValue(fail('Not found'));

      expect(await getMessage(999)).toBeNull();
    });
  });

  describe('sendEmail', () => {
    it('POSTs to /api/email/messages/send and returns true on success', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      const result = await sendEmail({
        accountId: 1,
        to: ['recipient@test.com'],
        subject: 'Hello',
        body: 'World',
      });

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/messages/send', {
        method: 'post',
        params: expect.objectContaining({ accountId: 1, subject: 'Hello' }),
      });
      expect(result).toBe(true);
    });

    it('returns false on failure', async () => {
      fetchResultMock.mockResolvedValue(fail('Send failed'));

      const result = await sendEmail({
        accountId: 1,
        to: ['x@test.com'],
        subject: 'Hi',
        body: 'Body',
      });

      expect(result).toBe(false);
    });
  });

  describe('markMessageRead', () => {
    it('PUTs /api/email/messages/:id/read', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await markMessageRead(42);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/messages/42/read', { method: 'put' });
    });
  });

  describe('linkToRecord', () => {
    it('POSTs /api/email/messages/:id/links with modelCode + recordId', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await linkToRecord(5, 'order', 100);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/messages/5/links', {
        method: 'post',
        params: { modelCode: 'order', recordId: 100 },
      });
    });
  });

  describe('unlinkRecord', () => {
    it('DELETEs /api/email/messages/:id/links/:linkId', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await unlinkRecord(5, 99);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/messages/5/links/99', { method: 'delete' });
    });
  });

  describe('getMessagesByRecord', () => {
    it('GETs /api/email/messages/by-record with modelCode + recordId', async () => {
      const msgs = [{ id: 1, subject: 'Hi' }];
      fetchResultMock.mockResolvedValue(ok(msgs));

      const result = await getMessagesByRecord('order', 50);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/messages/by-record', {
        method: 'get',
        params: { modelCode: 'order', recordId: 50 },
      });
      expect(result).toEqual(msgs);
    });

    it('returns empty array on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await getMessagesByRecord('order', 1)).toEqual([]);
    });
  });

  describe('getMessageLinks', () => {
    it('GETs /api/email/messages/:id/links', async () => {
      const links = [{ id: 1, messageId: 5, modelCode: 'order', recordId: 1, createdAt: '2024-01-01' }];
      fetchResultMock.mockResolvedValue(ok(links));

      const result = await getMessageLinks(5);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/messages/5/links', { method: 'get' });
      expect(result).toEqual(links);
    });

    it('returns empty array on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await getMessageLinks(5)).toEqual([]);
    });
  });

  describe('getTrackingStats', () => {
    it('GETs /api/email/messages/:id/tracking and returns stats', async () => {
      fetchResultMock.mockResolvedValue(ok({ opens: 3, clicks: 1 }));

      const result = await getTrackingStats(10);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/messages/10/tracking', { method: 'get' });
      expect(result).toEqual({ opens: 3, clicks: 1 });
    });

    it('returns zero stats on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await getTrackingStats(10)).toEqual({ opens: 0, clicks: 0 });
    });
  });

  // ── Sequence APIs ─────────────────────────────────────────────────────────────

  describe('listSequences', () => {
    it('GETs /api/email/sequences', async () => {
      const seqs = [{ id: 1, name: 'Onboarding', status: 'active', createdBy: 1, createdAt: '2024-01-01' }];
      fetchResultMock.mockResolvedValue(ok(seqs));

      const result = await listSequences();

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences', { method: 'get' });
      expect(result).toEqual(seqs);
    });

    it('returns empty array on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await listSequences()).toEqual([]);
    });
  });

  describe('createSequence', () => {
    it('POSTs to /api/email/sequences and returns created sequence', async () => {
      const seq = { id: 2, name: 'Nurture', status: 'draft', createdBy: 1, createdAt: '2024-01-01' };
      fetchResultMock.mockResolvedValue(ok(seq));

      const result = await createSequence({ name: 'Nurture', description: 'Desc' });

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences', {
        method: 'post',
        params: { name: 'Nurture', description: 'Desc' },
      });
      expect(result).toEqual(seq);
    });

    it('returns null on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await createSequence({ name: 'X' })).toBeNull();
    });
  });

  describe('getSequence', () => {
    it('GETs /api/email/sequences/:id', async () => {
      const seq = { id: 1, name: 'Onboarding', status: 'active', createdBy: 1, createdAt: '2024-01-01' };
      fetchResultMock.mockResolvedValue(ok(seq));

      const result = await getSequence(1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1', { method: 'get' });
      expect(result).toEqual(seq);
    });

    it('returns null when not found', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await getSequence(999)).toBeNull();
    });
  });

  describe('updateSequence', () => {
    it('PUTs /api/email/sequences/:id with updated data', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await updateSequence(1, { name: 'New Name' });

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1', {
        method: 'put',
        params: { name: 'New Name' },
      });
    });
  });

  describe('updateSequenceStatus', () => {
    it('PUTs /api/email/sequences/:id/status', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await updateSequenceStatus(1, 'active');

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1/status', {
        method: 'put',
        params: { status: 'active' },
      });
    });
  });

  describe('addStep', () => {
    it('POSTs to /api/email/sequences/:id/steps', async () => {
      const step = { id: 1, sequenceId: 1, stepOrder: 1, delayDays: 0, subjectTemplate: 'Hi', bodyTemplate: 'Body' };
      fetchResultMock.mockResolvedValue(ok(step));

      const data = { stepOrder: 1, delayDays: 0, subjectTemplate: 'Hi', bodyTemplate: 'Body' };
      const result = await addStep(1, data);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1/steps', {
        method: 'post',
        params: data,
      });
      expect(result).toEqual(step);
    });

    it('returns null on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await addStep(1, { stepOrder: 1, delayDays: 0, subjectTemplate: '', bodyTemplate: '' })).toBeNull();
    });
  });

  describe('updateStep', () => {
    it('PUTs /api/email/sequences/:seqId/steps/:stepId', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await updateStep(1, 5, { delayDays: 3 });

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1/steps/5', {
        method: 'put',
        params: { delayDays: 3 },
      });
    });
  });

  describe('deleteStep', () => {
    it('DELETEs /api/email/sequences/:seqId/steps/:stepId', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await deleteStep(1, 5);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1/steps/5', { method: 'delete' });
    });
  });

  describe('listSteps', () => {
    it('GETs /api/email/sequences/:id/steps', async () => {
      const steps = [{ id: 1, sequenceId: 1, stepOrder: 1, delayDays: 0, subjectTemplate: 'Hi', bodyTemplate: 'Body' }];
      fetchResultMock.mockResolvedValue(ok(steps));

      const result = await listSteps(1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1/steps', { method: 'get' });
      expect(result).toEqual(steps);
    });

    it('returns empty array on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await listSteps(1)).toEqual([]);
    });
  });

  describe('enrollContacts', () => {
    it('POSTs to /api/email/sequences/:id/enroll', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      const enrollments = [{ accountId: 1, contactEmail: 'x@test.com' }];
      await enrollContacts(1, enrollments);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1/enroll', {
        method: 'post',
        params: { enrollments },
      });
    });
  });

  describe('listEnrollments', () => {
    it('GETs /api/email/sequences/:id/enrollments', async () => {
      const enrollments = [{ id: 1, sequenceId: 1, accountId: 1, contactEmail: 'x@test.com', currentStep: 0, status: 'active', enrolledAt: '2024-01-01' }];
      fetchResultMock.mockResolvedValue(ok(enrollments));

      const result = await listEnrollments(1);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1/enrollments', { method: 'get' });
      expect(result).toEqual(enrollments);
    });

    it('returns empty array on failure', async () => {
      fetchResultMock.mockResolvedValue(fail());

      expect(await listEnrollments(1)).toEqual([]);
    });
  });

  describe('pauseEnrollment', () => {
    it('PUTs /api/email/sequences/:seqId/enrollments/:enrollId/pause', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await pauseEnrollment(1, 10);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1/enrollments/10/pause', { method: 'put' });
    });
  });

  describe('resumeEnrollment', () => {
    it('PUTs /api/email/sequences/:seqId/enrollments/:enrollId/resume', async () => {
      fetchResultMock.mockResolvedValue(ok(null));

      await resumeEnrollment(1, 10);

      expect(fetchResultMock).toHaveBeenCalledWith('/api/email/sequences/1/enrollments/10/resume', { method: 'put' });
    });
  });
});
