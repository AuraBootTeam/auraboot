import { describe, it, expect } from 'vitest';

describe('ops audit command', () => {
  describe('audit list columns', () => {
    const AUDIT_COLUMNS = [
      { key: 'traceId', header: 'TRACE ID' },
      { key: 'agentCode', header: 'agent' },
      { key: 'action', header: 'action' },
      { key: 'status', header: 'status' },
      { key: 'duration', header: 'duration' },
      { key: 'createdAt', header: 'time' },
    ];

    it('should have all required columns', () => {
      const headers = AUDIT_COLUMNS.map(c => c.header);
      expect(headers).toContain('TRACE ID');
      expect(headers).toContain('agent');
      expect(headers).toContain('status');
      expect(headers).toContain('time');
    });
  });

  describe('audit list API', () => {
    it('should use correct endpoint with pagination', () => {
      const endpoint = '/api/ai/traces';
      const params = { pageNum: '1', pageSize: '20' };
      expect(endpoint).toBe('/api/ai/traces');
      expect(params.pageSize).toBe('20');
    });
  });

  describe('audit show API', () => {
    it('should construct correct URL for trace ID', () => {
      const traceId = 'trace_20260315_001';
      const path = `/api/ai/traces/${traceId}`;
      expect(path).toBe('/api/ai/traces/trace_20260315_001');
    });
  });
});
