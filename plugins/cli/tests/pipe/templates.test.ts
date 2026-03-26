import { describe, it, expect } from 'vitest';
import { listTemplates, loadTemplate } from '../../src/pipe/templates.js';

describe('workflow templates', () => {
  describe('listTemplates', () => {
    it('should return at least 4 built-in templates', () => {
      const templates = listTemplates();
      expect(templates.length).toBeGreaterThanOrEqual(4);
    });

    it('should include daily-sales-report template', () => {
      const templates = listTemplates();
      const names = templates.map(t => t.name);
      expect(names).toContain('daily-sales-report');
    });

    it('should include overdue-tasks-alert template', () => {
      const templates = listTemplates();
      const names = templates.map(t => t.name);
      expect(names).toContain('overdue-tasks-alert');
    });

    it('should include new-leads-digest template', () => {
      const templates = listTemplates();
      const names = templates.map(t => t.name);
      expect(names).toContain('new-leads-digest');
    });

    it('should include inventory-restock-check template', () => {
      const templates = listTemplates();
      const names = templates.map(t => t.name);
      expect(names).toContain('inventory-restock-check');
    });

    it('should have description for each template', () => {
      const templates = listTemplates();
      for (const t of templates) {
        expect(t.description).toBeTruthy();
        expect(t.description).not.toBe('(parse error)');
      }
    });
  });

  describe('loadTemplate', () => {
    it('should load daily-sales-report template', () => {
      const wf = loadTemplate('daily-sales-report');
      expect(wf).not.toBeNull();
      expect(wf!.name).toBe('daily-sales-report');
      expect(wf!.steps.length).toBeGreaterThanOrEqual(2);
    });

    it('should load overdue-tasks-alert template', () => {
      const wf = loadTemplate('overdue-tasks-alert');
      expect(wf).not.toBeNull();
      expect(wf!.steps.length).toBeGreaterThanOrEqual(2);
    });

    it('should return null for unknown template', () => {
      const wf = loadTemplate('non-existent-template');
      expect(wf).toBeNull();
    });

    it('should return valid workflow with all required fields', () => {
      const wf = loadTemplate('daily-sales-report');
      expect(wf!.name).toBeTruthy();
      expect(wf!.steps).toBeInstanceOf(Array);
      for (const step of wf!.steps) {
        expect(['query', 'analyze', 'create', 'notify']).toContain(step.type);
      }
    });
  });
});
