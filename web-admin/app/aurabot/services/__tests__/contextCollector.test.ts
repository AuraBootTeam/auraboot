import { describe, expect, it } from 'vitest';
import { collectContext, inferModelCodeFromRoute } from '../contextCollector';

describe('collectContext', () => {
  it('fills nullable fields with safe defaults', () => {
    const ctx = collectContext({ route: '/dashboard' });
    expect(ctx).toEqual({
      route: '/dashboard',
      modelCode: null,
      pageId: null,
      selectedElement: null,
      recentOperations: [],
      lastCreatedResources: [],
    });
  });

  it('passes through provided fields verbatim', () => {
    const ctx = collectContext({
      route: '/p/order',
      modelCode: 'order',
      pageId: 'p_1',
      selectedElement: { id: 'b1' },
      recentOperations: [{ skill: 'foo' }],
      lastCreatedResources: [{ type: 'model' }],
    });
    expect(ctx.modelCode).toBe('order');
    expect(ctx.pageId).toBe('p_1');
    expect(ctx.selectedElement).toEqual({ id: 'b1' });
    expect(ctx.recentOperations).toHaveLength(1);
    expect(ctx.lastCreatedResources).toHaveLength(1);
  });
});

describe('inferModelCodeFromRoute', () => {
  it('extracts modelCode from /p/:modelCode', () => {
    expect(inferModelCodeFromRoute('/p/order')).toBe('order');
    expect(inferModelCodeFromRoute('/p/order/view/123')).toBe('order');
    expect(inferModelCodeFromRoute('/p/order?foo=bar')).toBe('order');
  });

  it('returns null for non-/p routes', () => {
    expect(inferModelCodeFromRoute('/dashboard')).toBeNull();
    expect(inferModelCodeFromRoute('/')).toBeNull();
  });
});
