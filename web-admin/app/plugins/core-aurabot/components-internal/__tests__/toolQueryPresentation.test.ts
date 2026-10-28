import { describe, expect, it } from 'vitest';
import { toolQueryPresentation } from '../toolQueryPresentation';

describe('DSL list result presentation', () => {
  it('retains business evidence and its model while excluding storage columns from presentation', () => {
    const records = [{ tenant_id: 'private-tenant', pid: 'private-pid', e2et_cust_name: '客户', e2et_cust_contact: '联系人', active: false }];
    const result = { records, total: 1 };
    const displayed = toolQueryPresentation(result, 'list_e2et_customer');
    expect(displayed).toEqual({ ...result, modelCode: 'e2et_customer', columns: ['e2et_cust_name', 'e2et_cust_contact', 'active'] });
    expect(displayed.records).toBe(records);
    expect(result).not.toHaveProperty('columns');
  });
  it('preserves explicit business columns and model identity', () => {
    expect(toolQueryPresentation({ modelCode: 'customer', columns: ['tenant_id', 'amount'], records: [{ amount: 0 }] }, 'list_customer')).toMatchObject({ modelCode: 'customer', columns: ['amount'] });
  });
  it('does not reinterpret named queries or analytics aggregates as model lists', () => {
    const aggregate = { columns: ['tenant_id', 'total'], records: [{ tenant_id: 'group', total: 4 }] };
    for (const name of ['query_customer', 'aurabot:chat-bi', 'custom_list_customer', undefined]) expect(toolQueryPresentation(aggregate, name)).toBe(aggregate);
  });
});
