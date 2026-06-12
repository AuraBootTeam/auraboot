import { afterEach, describe, expect, it } from 'vitest';
import type { DslBlockV3 } from '../../types';
import {
  registerPageTemplate,
  getPageTemplates,
  getPageTemplate,
  clearPageTemplates,
} from '../pageTemplateRegistry';

const inspection = {
  id: 'qr_inspection',
  label: '设备巡检',
  category: 'qr',
  title: { 'zh-CN': '设备巡检' },
  build: (): DslBlockV3[] => [
    { id: 'form', blockType: 'form', blocks: [{ id: 'f1', blockType: 'field', field: 'note' }] },
  ],
};

describe('page template registry (D6 — scenario template library)', () => {
  afterEach(() => clearPageTemplates());

  it('registers a template and lists it', () => {
    expect(getPageTemplates()).toHaveLength(0);
    registerPageTemplate(inspection);
    expect(getPageTemplates().map((t) => t.id)).toEqual(['qr_inspection']);
    expect(getPageTemplate('qr_inspection')?.label).toBe('设备巡检');
  });

  it('build() returns a fresh block tree on each call (no shared mutation)', () => {
    registerPageTemplate(inspection);
    const a = getPageTemplate('qr_inspection')!.build();
    const b = getPageTemplate('qr_inspection')!.build();
    expect(a).not.toBe(b);
    a[0].blocks![0].field = 'mutated';
    expect(b[0].blocks![0].field).toBe('note'); // second build unaffected
  });

  it('returns undefined for unknown / nullish ids', () => {
    expect(getPageTemplate('nope')).toBeUndefined();
    expect(getPageTemplate(null)).toBeUndefined();
  });
});
