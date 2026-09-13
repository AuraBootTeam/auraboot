import { describe, it, expect } from 'vitest';
import { listTemplates, loadTemplate } from '../../src/pipe/templates.js';

describe('workflow templates', () => {
  describe('listTemplates', () => {
    it('should return at least 2 platform templates', () => {
      const templates = listTemplates();
      expect(templates.length).toBeGreaterThanOrEqual(2);
    });

    it('should include overdue-tasks-alert template', () => {
      const templates = listTemplates();
      const names = templates.map(t => t.name);
      expect(names).toContain('overdue-tasks-alert');
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
      const wf = loadTemplate('overdue-tasks-alert');
      expect(wf!.name).toBeTruthy();
      expect(wf!.steps).toBeInstanceOf(Array);
      for (const step of wf!.steps) {
        expect(['query', 'analyze', 'create', 'notify']).toContain(step.type);
      }
    });
  });
});
