import { describe, expect, it } from 'vitest';
import { primaryModelPreview, resolveModelPageRoute } from '../modelPageNavigation';

describe('model page navigation uses actual dynamic route contracts', () => {
  const list = { pid: 'list', kind: 'list', modelCode: 'customer', pageKey: 'customer_list' };
  const form = { pid: 'form', kind: 'form', modelCode: 'customer', pageKey: 'customer_form' };
  const detail = { pid: 'detail', kind: 'detail', modelCode: 'customer', pageKey: 'customer_detail' };

  it('opens the default primary list without double-appending a page-kind suffix', () => {
    const selected = primaryModelPreview({ detail, form, list });
    expect(selected).toBe(list);
    expect(resolveModelPageRoute(selected!)).toBe('/p/customer');
  });

  it('uses the record-free new route when only a standard form is available', () => {
    expect(primaryModelPreview({ detail, form })).toBe(form);
    expect(resolveModelPageRoute(form)).toBe('/p/customer/new');
  });

  it('resolves a stored detail key directly without inventing a record identity', () => {
    expect(primaryModelPreview({ detail })).toBe(detail);
    expect(resolveModelPageRoute(detail)).toBe('/p/c/customer_detail');
  });

  it('preserves an explicit application route', () => {
    expect(resolveModelPageRoute({ ...list, route: '/p/customer?view=sales' }))
      .toBe('/p/customer?view=sales');
  });

  it('keeps a nonstandard list key exact instead of inferring a different model', () => {
    expect(resolveModelPageRoute({ ...list, pageKey: 'customer_priority_list' }))
      .toBe('/p/c/customer_priority_list');
    expect(resolveModelPageRoute({ pid: 'legacy', kind: 'list', pageKey: 'legacy_list' }))
      .toBe('/p/c/legacy_list');
  });

  it('reports no primary preview for an empty page set', () => {
    expect(primaryModelPreview({})).toBeUndefined();
    expect(resolveModelPageRoute({ pid: 'missing', kind: 'list' })).toBe('/p/page_schema');
  });
});
