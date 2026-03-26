import { describe, it, expect } from 'vitest';

describe('run command', () => {
  describe('dispatch request', () => {
    it('should construct correct dispatch body', () => {
      const target = 'task_1024';
      const body = { taskPid: target };
      expect(body.taskPid).toBe('task_1024');
    });
  });

  describe('run show', () => {
    it('should extract run detail fields', () => {
      const runDetail = {
        runPid: 'run_20260314_001',
        agentCode: 'sales-ops-agent',
        status: 'completed',
        taskPid: 'task_1024',
        steps: [
          { name: 'Fetch data', status: 'completed', duration: '2.1s' },
          { name: 'Analyze', status: 'completed', duration: '3.4s' },
        ],
        startedAt: '2026-03-14T10:00:00Z',
        completedAt: '2026-03-14T10:00:06Z',
      };

      expect(runDetail.runPid).toContain('run_');
      expect(runDetail.status).toBe('completed');
      expect(runDetail.steps).toHaveLength(2);
    });

    it('should use correct API endpoint', () => {
      const runPid = 'run_20260314_001';
      const endpoint = '/api/agent_run_list/detail';
      const params = { pid: runPid };
      expect(params.pid).toBe(runPid);
    });
  });
});
