import { describe, expect, it } from 'vitest';
import { contextLabel } from '../contextLabel';
import type { PageContext } from '../../hooks/usePageContext';

const base: PageContext = {
  pageType: 'list',
  pageKey: 'tpm_project',
  modelCode: 'tpm_project',
  breadcrumb: ['tpm_project'],
};
const t = (key: string) => (key === 'model.tpm_project._meta.label' ? '项目' : key);
describe('business context presentation', () => {
  it('resolves route-derived technical breadcrumbs without changing query identity', () => {
    expect(contextLabel(base, t, '当前业务页面')).toBe('项目');
    expect(base.modelCode).toBe('tpm_project');
  });
  it('keeps meaningful business navigation', () => {
    expect(
      contextLabel({ ...base, breadcrumb: ['项目交付', 'tpm_project'] }, t, '当前业务页面'),
    ).toBe('项目交付 → 项目');
  });
  it('does not expose an unknown model when translation is unavailable', () => {
    expect(contextLabel(base, (key) => key, '当前业务页面')).toBe('当前业务页面');
    expect(contextLabel({ ...base, breadcrumb: [] }, (key) => key, 'Current business page')).toBe(
      'Current business page',
    );
  });
});
