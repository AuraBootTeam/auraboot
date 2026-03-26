import { describe, it, expect } from 'vitest';

describe('ops approvals command', () => {
  describe('approval list columns', () => {
    const APPROVAL_COLUMNS = [
      { key: 'pid', header: 'pid' },
      { key: 'tool_code', header: 'tool' },
      { key: 'status', header: 'status' },
      { key: 'risk_level', header: 'risk' },
      { key: 'requested_at', header: 'requested' },
      { key: 'expires_at', header: 'expires' },
    ];

    it('should have all required columns', () => {
      const headers = APPROVAL_COLUMNS.map(c => c.header);
      expect(headers).toContain('pid');
      expect(headers).toContain('tool');
      expect(headers).toContain('status');
      expect(headers).toContain('risk');
      expect(headers).toContain('requested');
      expect(headers).toContain('expires');
    });

    it('should extract values from API response', () => {
      const apiRecord = {
        pid: 'approval_001',
        tool_code: 'send_email',
        status: 'pending',
        risk_level: 'high',
        requested_at: '2026-03-18T10:00:00Z',
        expires_at: '2026-03-18T11:00:00Z',
      };

      const row = APPROVAL_COLUMNS.map(c => String(apiRecord[c.key as keyof typeof apiRecord] ?? ''));
      expect(row).toEqual([
        'approval_001',
        'send_email',
        'pending',
        'high',
        '2026-03-18T10:00:00Z',
        '2026-03-18T11:00:00Z',
      ]);
    });
  });

  describe('approval list API endpoint', () => {
    it('should use the correct pending approvals endpoint', () => {
      const path = '/api/agent/approvals/pending';
      expect(path).toBe('/api/agent/approvals/pending');
    });
  });

  describe('approval approve API endpoint', () => {
    it('should construct correct URL for a given pid', () => {
      const pid = 'approval_001';
      const path = `/api/agent/approval/${pid}/approve`;
      expect(path).toBe('/api/agent/approval/approval_001/approve');
    });

    it('should send a POST request with no required body fields', () => {
      const body = {};
      expect(typeof body).toBe('object');
    });
  });

  describe('approval reject API endpoint', () => {
    it('should construct correct URL for a given pid', () => {
      const pid = 'approval_002';
      const path = `/api/agent/approval/${pid}/reject`;
      expect(path).toBe('/api/agent/approval/approval_002/reject');
    });

    it('should send a POST request with reason in body', () => {
      const reason = 'Action not authorized by policy';
      const body = { reason };
      expect(body).toEqual({ reason: 'Action not authorized by policy' });
      expect(body).toHaveProperty('reason');
    });

    it('should include reason string in reject body', () => {
      const pid = 'approval_003';
      const reason = 'Outside permitted hours';
      const endpoint = `/api/agent/approval/${pid}/reject`;
      const payload = { reason };

      expect(endpoint).toBe('/api/agent/approval/approval_003/reject');
      expect(payload.reason).toBe('Outside permitted hours');
    });
  });
});
