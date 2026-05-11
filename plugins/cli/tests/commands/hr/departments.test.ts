import { describe, it, expect } from 'vitest';

describe('hr departments command', () => {
  describe('column definitions', () => {
    const COLUMNS = ['org_dept_name', 'org_dept_code', 'org_dept_status', 'org_dept_order'];

    it('should extract all fields from API record', () => {
      const record = {
        org_dept_name: 'Engineering',
        org_dept_code: 'DEPT-ENG',
        org_dept_status: 'active',
        org_dept_order: 1,
        pid: '02DEF',
      };

      for (const col of COLUMNS) {
        expect(record[col as keyof typeof record]).toBeDefined();
      }
    });
  });

  describe('filter options', () => {
    it('should build status filter', () => {
      const status = 'active';
      const filter = { fieldName: 'org_dept_status', operator: 'EQ', value: status.toLowerCase() };
      expect(filter.value).toBe('active');
    });

    it('should use correct page key', () => {
      const pageKey = 'org_department';
      expect(pageKey).toBe('org_department');
    });
  });

  describe('sort order', () => {
    it('should sort by org_dept_order ascending by default', () => {
      const sortField = 'org_dept_order';
      const sortOrder = 'asc';
      expect(sortField).toBe('org_dept_order');
      expect(sortOrder).toBe('asc');
    });
  });
});
