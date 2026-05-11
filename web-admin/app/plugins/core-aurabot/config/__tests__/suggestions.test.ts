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

  it('offers the PCBA quality anomaly evidence-first prompt on the defect list page', () => {
    const suggestions = getSuggestions('list', 'qc_defect_record');
    const anomaly = suggestions.find((item) => item.label === 'Analyze anomalies');

    expect(anomaly).toBeDefined();
    expect(anomaly?.labelZh).toBe('生成质量异常分析');
    expect(anomaly?.prompt).toContain('qc_quality_anomaly_trend');
    expect(anomaly?.prompt).toContain('qc_quality_batch_correlation');
    expect(anomaly?.prompt).toContain('qc_quality_capa_context');
    expect(anomaly?.prompt).toContain('才生成 CAPA 草稿');
    expect(anomaly?.prompt).toContain('不要自动放行、拒收、关闭或处置任何记录');
  });
});
