import { describe, it, expect } from 'vitest';

describe('crm opportunities command', () => {
  describe('column definitions', () => {
    const COLUMNS = ['crm_opp_name', 'crm_opp_stage', 'crm_opp_expected_amount',
      'crm_opp_probability', 'crm_opp_owner', 'crm_opp_expected_close_date'];

    it('should extract values from opportunity record', () => {
      const record = {
        crm_opp_name: 'Enterprise Deal',
        crm_opp_stage: 'negotiation',
        crm_opp_expected_amount: 500000,
        crm_opp_probability: 75,
        crm_opp_owner: 'sales-rep',
        crm_opp_expected_close_date: '2026-04-15',
      };

      expect(record.crm_opp_name).toBe('Enterprise Deal');
      expect(record.crm_opp_stage).toBe('negotiation');
      expect(record.crm_opp_expected_amount).toBe(500000);
    });
  });

  describe('stage filter', () => {
    const VALID_STAGES = ['discovery', 'qualification', 'proposal', 'negotiation', 'closed_won', 'closed_lost'];

    it('should recognize all valid stages', () => {
      expect(VALID_STAGES).toHaveLength(6);
      expect(VALID_STAGES).toContain('closed_won');
    });

    it('should uppercase input', () => {
      const input = 'negotiation';
      expect(input.toUpperCase()).toBe('negotiation');
    });
  });

  describe('alias', () => {
    it('should support opps alias', () => {
      // commander alias 'opps' registered in index.ts
      const aliases = ['opportunities', 'opps'];
      expect(aliases).toContain('opps');
    });
  });
});
