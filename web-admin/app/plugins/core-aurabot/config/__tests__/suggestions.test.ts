import { describe, expect, it } from 'vitest';

import { getSuggestions } from '../suggestions';

describe('core AuraBot suggestions', () => {
  it('offers the PCBA procurement comparison evidence-first prompt on the draft list page', () => {
    const suggestions = getSuggestions('list', 'pe_procurement_comparison');
    const comparison = suggestions.find((item) => item.label === 'Compare suppliers');

    expect(comparison).toBeDefined();
    expect(comparison?.labelZh).toBe('生成供应商比价建议');
    expect(comparison?.prompt).toContain('pe_procurement_comparison_supplier_options');
    expect(comparison?.prompt).toContain('确认后再生成采购比价草稿');
  });
});
