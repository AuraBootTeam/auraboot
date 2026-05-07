import { describe, it, expect } from 'vitest';
import { fieldConfigSchemas } from '../fieldConfigSchemas';

describe('fieldConfigSchemas', () => {
  it('has unique top-level keys', () => {
    const keys = fieldConfigSchemas.map((s: any) => s.key);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it("every dependsOn.field references a sibling key", () => {
    const keys = new Set(fieldConfigSchemas.map((s: any) => s.key));
    for (const s of fieldConfigSchemas as any[]) {
      const dep = s.dependsOn;
      if (dep) expect(keys.has(dep.field)).toBe(true);
      if (s.type === 'array' && s.itemSchema) {
        const itemKeys = new Set(s.itemSchema.map((f: any) => f.key));
        for (const f of s.itemSchema) {
          if (f.dependsOn) expect(itemKeys.has(f.dependsOn.field)).toBe(true);
        }
      }
    }
  });

  it("validation rule type options match the ValidationRule['type'] union", () => {
    const validationRulesField = (fieldConfigSchemas as any[]).find((s) => s.key === 'validationRules');
    expect(validationRulesField).toBeDefined();
    const typeField = validationRulesField.itemSchema.find((f: any) => f.key === 'type');
    expect(typeField).toBeDefined();
    const optionValues = typeField.options.map((o: any) => o.value).sort();
    expect(optionValues).toEqual(
      ['custom', 'max', 'maxLength', 'min', 'minLength', 'pattern', 'required'],
    );
  });

  it('every label is an I18nText with at least zh-CN and en-US', () => {
    const visit = (s: any) => {
      expect(s.label?.['zh-CN']).toBeTruthy();
      expect(s.label?.['en-US']).toBeTruthy();
      if (s.options) {
        for (const o of s.options) {
          expect(o.label?.['zh-CN']).toBeTruthy();
          expect(o.label?.['en-US']).toBeTruthy();
        }
      }
      if (s.itemSchema) s.itemSchema.forEach(visit);
    };
    fieldConfigSchemas.forEach(visit);
  });
});
