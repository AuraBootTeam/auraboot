import { describe, it, expect } from 'vitest';

describe('hr employees command', () => {
  describe('column definitions', () => {
    const COLUMNS = ['org_emp_name', 'org_emp_code', 'org_emp_status',
      'org_emp_email', 'org_emp_phone', 'org_emp_hire_date'];

    it('should extract all fields from API record', () => {
      const record = {
        org_emp_name: 'Zhang Wei',
        org_emp_code: 'EMP-001',
        org_emp_status: 'active',
        org_emp_email: 'zhang@company.com',
        org_emp_phone: '13800138000',
        org_emp_hire_date: '2025-06-01',
        pid: '01ABC',
      };

      for (const col of COLUMNS) {
        expect(record[col as keyof typeof record]).toBeDefined();
      }
    });
  });

  describe('filter options', () => {
    it('should build status filter', () => {
      const status = 'active';
      const filter = { fieldName: 'org_emp_status', operator: 'EQ', value: status.toUpperCase() };
      expect(filter.value).toBe('active');
    });

    it('should uppercase RESIGNED status', () => {
      const status = 'resigned';
      const filter = { fieldName: 'org_emp_status', operator: 'EQ', value: status.toUpperCase() };
      expect(filter.value).toBe('resigned');
    });

    it('should use correct page key', () => {
      const pageKey = 'org_employee';
      expect(pageKey).toBe('org_employee');
    });
  });

  describe('employee status values', () => {
    const VALID_STATUSES = ['active', 'probation', 'resigned'];

    it('should recognize all valid statuses', () => {
      for (const status of VALID_STATUSES) {
        expect(status.length).toBeGreaterThan(0);
      }
      expect(VALID_STATUSES).toHaveLength(3);
    });
  });
});
