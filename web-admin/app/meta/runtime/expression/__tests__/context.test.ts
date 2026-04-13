import { describe, it, expect } from 'vitest';
import { createExpressionContext } from '../context';

describe('createExpressionContext', () => {
  it('creates default context with empty $ aliases', () => {
    const ctx = createExpressionContext();
    expect(ctx.$user).toBeUndefined(); // global.user is undefined by default
    expect(ctx.$form).toBeUndefined();
    expect(ctx.$state).toEqual({});
    expect(ctx.$record).toBeUndefined();
    expect(ctx.$page).toEqual({});
  });

  it('$form aliases form data', () => {
    const ctx = createExpressionContext({ form: { status: 'draft', name: 'Test' } });
    expect(ctx.$form).toBe(ctx.form); // same reference
    expect(ctx.$form.status).toBe('draft');
    expect(ctx.$form.name).toBe('Test');
  });

  it('$user aliases global.user', () => {
    const user = { id: '1', name: 'Admin', email: 'a@b.com', roles: ['ADMIN'], permissions: ['all'] };
    const ctx = createExpressionContext({ global: { locale: 'en', theme: 'light', user } });
    expect(ctx.$user).toBe(ctx.global.user); // same reference
    expect(ctx.$user.name).toBe('Admin');
    expect(ctx.$user.roles).toContain('ADMIN');
  });

  it('$state aliases state', () => {
    const ctx = createExpressionContext({ state: { filters: { status: 'active' }, selectedIds: ['1', '2'] } });
    expect(ctx.$state).toBe(ctx.state);
    expect(ctx.$state.filters.status).toBe('active');
    expect(ctx.$state.selectedIds).toHaveLength(2);
  });

  it('$record aliases row', () => {
    const ctx = createExpressionContext({ row: { id: '42', name: 'Record' } });
    expect(ctx.$record).toBe(ctx.row);
    expect(ctx.$record.id).toBe('42');
  });

  it('$page defaults to empty object', () => {
    const ctx = createExpressionContext();
    expect(ctx.$page).toEqual({});
  });

  it('$page preserves injected metadata', () => {
    const ctx = createExpressionContext({ $page: { kind: 'list', modelCode: 'crm_lead' } } as any);
    expect(ctx.$page.kind).toBe('list');
    expect(ctx.$page.modelCode).toBe('crm_lead');
  });

  it('backward compatibility: form.status still works', () => {
    const ctx = createExpressionContext({ form: { status: 'draft' } });
    expect(ctx.form?.status).toBe('draft');
    expect(ctx.$form.status).toBe('draft');
  });
});
