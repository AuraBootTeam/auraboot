import { expect, it } from 'vitest';
import { getBlockLabel } from '../utils/blockLabel';
const fields = [{ modelCode: 'tpm_project', code: 'tpm_pj_budget', label: { 'zh-CN': '项目预算', en: 'Project budget' } }];
it('uses localized live model names for unlabelled designer fields without mutating the DSL', () => {
  const block = { id: 'budget', blockType: 'field', field: 'tpm_pj_budget' };
  expect(getBlockLabel(block, 'zh-CN', undefined, fields)).toBe('项目预算');
  expect(getBlockLabel(block, 'en', undefined, fields)).toBe('Project budget');
  expect(block).not.toHaveProperty('title');
});
it('keeps an explicit DSL field label ahead of model metadata and unknown-field diagnostics', () => {
  expect(getBlockLabel({ id: 'budget', blockType: 'field', field: 'tpm_pj_budget', title: '交付预算' }, 'zh-CN', undefined, fields)).toBe('交付预算');
  expect(getBlockLabel({ id: 'unknown', blockType: 'field', field: 'missing' }, 'zh-CN', undefined, fields)).toBe('missing');
});
