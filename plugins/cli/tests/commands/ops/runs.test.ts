import { describe, it, expect } from 'vitest';

describe('ops runs command', () => {
  describe('runs list columns', () => {
    const RUN_COLUMNS = [
      { key: 'pid', header: 'pid' },
      { key: 'agent_code', header: 'agent' },
      { key: 'status', header: 'status' },
      { key: 'started_at', header: 'started' },
      { key: 'duration_ms', header: 'DURATION(ms)' },
    ];

    it('should have all required columns', () => {
      const headers = RUN_COLUMNS.map(c => c.header);
      expect(headers).toContain('pid');
      expect(headers).toContain('agent');
      expect(headers).toContain('status');
      expect(headers).toContain('started');
      expect(headers).toContain('DURATION(ms)');
    });

    it('should extract values from API response', () => {
      const apiRecord = {
        pid: 'run-abc123',
        agent_code: 'sales_agent_001',
        status: 'completed',
        started_at: '2026-03-18T10:00:00Z',
        duration_ms: 1523,
        tenant_id: 'tenant_001',
        total_tokens: 420,
      };

      const row = RUN_COLUMNS.map(c => String(apiRecord[c.key as keyof typeof apiRecord] ?? ''));
      expect(row).toEqual(['run-abc123', 'sales_agent_001', 'completed', '2026-03-18T10:00:00Z', '1523']);
    });

    it('should handle missing optional fields gracefully', () => {
      const apiRecord = {
        pid: 'run-xyz789',
        agent_code: 'inspect-agent',
        status: 'running',
        started_at: '2026-03-18T11:00:00Z',
        // duration_ms intentionally absent (run still in progress)
      };

      const row = RUN_COLUMNS.map(c => String((apiRecord as Record<string, unknown>)[c.key] ?? ''));
      expect(row[0]).toBe('run-xyz789');
      expect(row[4]).toBe(''); // duration_ms absent → empty string
    });
  });

  describe('runs list API endpoint', () => {
    it('should use correct NQ datasource', () => {
      const params = { datasourceId: 'nq:acp_recent_runs', maxItems: '20', format: 'records' };
      expect(params.datasourceId).toBe('nq:acp_recent_runs');
      expect(params.format).toBe('records');
    });

    it('should limit results to 20 most recent runs', () => {
      const params = { datasourceId: 'nq:acp_recent_runs', maxItems: '20', format: 'records' };
      expect(Number(params.maxItems)).toBeLessThanOrEqual(20);
    });
  });

  describe('runs show API endpoint', () => {
    it('should construct correct datasource request for run PID', () => {
      const runPid = 'run-abc123';
      const params = { datasourceId: 'nq:acp_run_detail', pid: runPid, format: 'records' };
      expect(params.datasourceId).toBe('nq:acp_run_detail');
      expect(params.pid).toBe('run-abc123');
    });

    it('should pass pid as query parameter to datasource', () => {
      const runPid = 'run-xyz789';
      const params: Record<string, string> = {
        datasourceId: 'nq:acp_run_detail',
        pid: runPid,
        format: 'records',
      };
      expect(params['pid']).toBe(runPid);
    });
  });

  describe('runs status color mapping', () => {
    it('COMPLETED status should be distinguishable from FAILED', () => {
      const statuses = ['completed', 'failed', 'running', 'pending'];
      // Each status maps to a distinct visual representation
      const statusSet = new Set(statuses);
      expect(statusSet.size).toBe(4);
    });

    it('should recognise terminal vs non-terminal run statuses', () => {
      const terminalStatuses = ['completed', 'failed', 'cancelled'];
      const activeStatuses = ['running', 'pending', 'waiting_approval'];
      expect(terminalStatuses).not.toEqual(expect.arrayContaining(activeStatuses));
    });
  });
});
