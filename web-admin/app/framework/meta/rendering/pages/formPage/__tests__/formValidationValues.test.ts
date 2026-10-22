import { describe, expect, it } from 'vitest';
import { resolveCommandValidationSummary } from '../formValidationValues';

const messages: Record<string, string> = {
  'bom.error.dielectric_alias_target': 'The alias must map to an enabled dielectric standard code.',
  'bom.error.dielectric_alias_required': 'Enter a dielectric alias.',
};
const translate = (key: string) => messages[key] ?? key;

describe('command validation summaries', () => {
  it('localizes the wrapped handler error returned in context.error', () => {
    expect(resolveCommandValidationSummary(
      'Plugin handler execution failed: bom.error.dielectric_alias_target',
      'bom:create_material_rule_dict', translate, 'en-US',
    )).toEqual(['The alias must map to an enabled dielectric standard code.']);
  });

  it('keeps separate validation messages and removes duplicate source reasons', () => {
    expect(resolveCommandValidationSummary(
      'bom.error.dielectric_alias_target; bom.error.dielectric_alias_required; bom.error.dielectric_alias_required',
      'bom:create_material_rule_dict', translate, 'en-US',
    )).toEqual(['The alias must map to an enabled dielectric standard code.', 'Enter a dielectric alias.']);
  });

  it('uses actionable localized fallback when a handler translation is unavailable', () => {
    expect(resolveCommandValidationSummary(
      'Plugin handler execution failed: bom.error.missing_translation',
      'bom:create_material_rule_dict', translate, 'zh-CN',
    )).toEqual(['操作未完成，请检查输入后重试。']);
  });

  it('retains plain business validation messages without the platform wrapper', () => {
    expect(resolveCommandValidationSummary(
      'Plugin handler execution failed: Enter a valid quantity; Choose a unit',
      'material:create', translate, 'en-US',
    )).toEqual(['Enter a valid quantity', 'Choose a unit']);
  });
});
