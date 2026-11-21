import { describe, expect, it } from 'vitest';

import { deriveContextFromRoute } from '../usePageContext';

/**
 * /p/:pageKey routes carry the PAGE key ({model_code}_list on lists), while
 * scene tables key by bare model code. Before the fix, modelCode was the full
 * page key, so every list-page suggestion lookup missed — the AuraBot
 * context-suggestion strip never rendered on any DSL list page.
 */
describe('usePageContext deriveContextFromRoute modelCode', () => {
  it('strips the _list kind suffix on list routes', () => {
    const context = deriveContextFromRoute('/p/crm_lead_common_list', { pageKey: 'crm_lead_common_list' });
    expect(context.pageType).toBe('list');
    expect(context.pageKey).toBe('crm_lead_common_list');
    expect(context.modelCode).toBe('crm_lead_common');
  });

  it('strips kind suffixes on detail and form routes', () => {
    const detail = deriveContextFromRoute('/p/crm_lead_common_list/view/01ABC', {
      pageKey: 'crm_lead_common_list',
      recordPid: '01ABC',
    });
    expect(detail.pageType).toBe('detail');
    expect(detail.modelCode).toBe('crm_lead_common');
    expect(detail.pageKey).toBe('crm_lead_common_list');

    const form = deriveContextFromRoute('/p/crm_lead_common_list/new', { pageKey: 'crm_lead_common_list' });
    expect(form.pageType).toBe('form');
    expect(form.modelCode).toBe('crm_lead_common');
  });

  it('leaves page keys without a kind suffix untouched', () => {
    const context = deriveContextFromRoute('/p/crm_sales_home', { pageKey: 'crm_sales_home' });
    expect(context.modelCode).toBe('crm_sales_home');
  });
});
