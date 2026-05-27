import { describe, expect, it } from 'vitest';
import {
  automationTemplates,
  templateCategories,
  resolveLocalizedText,
  searchTemplates,
  filterTemplatesByCategory,
} from '../automationTemplates';

describe('automationTemplates i18n', () => {
  it('every template has both en-US and zh-CN for name and description', () => {
    for (const t of automationTemplates) {
      expect(t.name['en-US'], `${t.id} missing name en-US`).toBeTruthy();
      expect(t.name['zh-CN'], `${t.id} missing name zh-CN`).toBeTruthy();
      expect(t.description['en-US'], `${t.id} missing description en-US`).toBeTruthy();
      expect(t.description['zh-CN'], `${t.id} missing description zh-CN`).toBeTruthy();
    }
  });

  it('en-US and zh-CN are actually different (catches placeholder copies)', () => {
    for (const t of automationTemplates) {
      expect(
        t.name['en-US'],
        `${t.id} name en-US and zh-CN identical — likely missing translation`,
      ).not.toBe(t.name['zh-CN']);
    }
  });

  it('every category entry has both en-US and zh-CN label', () => {
    for (const cat of templateCategories) {
      expect(cat.label['en-US'], `category ${cat.key} missing en-US`).toBeTruthy();
      expect(cat.label['zh-CN'], `category ${cat.key} missing zh-CN`).toBeTruthy();
    }
  });

  it('resolveLocalizedText returns requested locale when present', () => {
    const tpl = automationTemplates[0];
    expect(resolveLocalizedText(tpl.name, 'en-US')).toBe(tpl.name['en-US']);
    expect(resolveLocalizedText(tpl.name, 'zh-CN')).toBe(tpl.name['zh-CN']);
  });

  it('resolveLocalizedText falls back to zh-CN then en-US when locale missing', () => {
    expect(resolveLocalizedText({ 'en-US': 'EN', 'zh-CN': 'ZH' }, 'fr-FR')).toBe('ZH');
    expect(resolveLocalizedText({ 'en-US': 'EN' }, 'fr-FR')).toBe('EN');
  });

  it('searchTemplates matches against both locales', () => {
    const enHits = searchTemplates('lead');
    const zhHits = searchTemplates('线索');
    expect(enHits.find((t) => t.id === 'tpl-new-lead-notification')).toBeTruthy();
    expect(zhHits.find((t) => t.id === 'tpl-new-lead-notification')).toBeTruthy();
  });

  it('filterTemplatesByCategory still works after i18n migration', () => {
    const sales = filterTemplatesByCategory('sales');
    expect(sales.length).toBeGreaterThan(0);
    expect(sales.every((t) => t.category === 'sales')).toBe(true);
  });
});
