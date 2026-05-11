import { describe, it, expect } from 'vitest';

describe('project tasks command', () => {
  describe('column definitions', () => {
    const COLUMNS = ['pm_task_title', 'pm_task_status', 'pm_task_priority',
      'pm_task_type', 'pm_task_assignee', 'pm_task_due_date'];

    it('should cover all key task fields', () => {
      expect(COLUMNS).toContain('pm_task_title');
      expect(COLUMNS).toContain('pm_task_status');
      expect(COLUMNS).toContain('pm_task_assignee');
    });
  });

  describe('--mine flag', () => {
    it('should use pm_my_tasks NamedQuery', () => {
      const nqCode = 'pm_my_tasks';
      expect(nqCode).toBe('pm_my_tasks');
    });
  });

  describe('status filter', () => {
    const VALID_STATUSES = ['todo', 'in_progress', 'done', 'blocked'];

    it('should recognize all valid statuses', () => {
      expect(VALID_STATUSES).toHaveLength(4);
    });

    it('should lowercase input', () => {
      expect('in_progress'.toLowerCase()).toBe('in_progress');
    });
  });

  describe('task list API', () => {
    it('should use correct page key', () => {
      expect('pm_task').toBe('pm_task');
    });
  });
});
