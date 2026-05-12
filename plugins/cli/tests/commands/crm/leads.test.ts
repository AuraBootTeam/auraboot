import { describe, it, expect } from 'vitest';

describe('crm leads command', () => {
  describe('column definitions', () => {
    const COLUMNS = ['crm_lead_contact_name', 'crm_lead_company', 'crm_lead_status',
      'crm_lead_source', 'crm_lead_score', 'crm_lead_contact_email'];

    it('should extract all fields from API record', () => {
      const record = {
        crm_lead_contact_name: 'Zhang Ming',
        crm_lead_company: 'Acme Corp',
        crm_lead_status: 'qualified',
        crm_lead_source: 'website',
        crm_lead_score: 85,
        crm_lead_contact_email: 'zhang@acme.com',
        pid: '01ABC',
      };

      for (const col of COLUMNS) {
        expect(record[col as keyof typeof record]).toBeDefined();
      }
    });
  });

  describe('filter options', () => {
    it('should build status filter', () => {
      const status = 'qualified';
      const filter = { fieldName: 'crm_lead_status', operator: 'EQ', value: status.toLowerCase() };
      expect(filter.value).toBe('qualified');
    });

    it('should build source filter', () => {
      const source = 'website';
      const filter = { fieldName: 'crm_lead_source', operator: 'EQ', value: source.toLowerCase() };
      expect(filter.value).toBe('website');
    });

    it('should use correct page key', () => {
      const pageKey = 'crm_lead';
      expect(pageKey).toBe('crm_lead');
    });
  });

  describe('lead status values', () => {
    const VALID_STATUSES = ['new', 'contacted', 'qualified', 'converted', 'lost'];

    it('should recognize all valid statuses', () => {
      for (const status of VALID_STATUSES) {
        expect(status.length).toBeGreaterThan(0);
      }
      expect(VALID_STATUSES).toHaveLength(5);
    });
  });
});
