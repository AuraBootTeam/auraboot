import { describe, expect, it } from 'vitest';
import { hasCanonicalListResult, toolQueryPresentation } from '../toolQueryPresentation';

describe('DSL list result presentation', () => {
  it('retains business evidence and its model while excluding storage columns from presentation', () => {
    const records = [{ tenant_id: 'private-tenant', pid: 'private-pid', e2et_cust_name: '客户', e2et_cust_contact: '联系人', active: false }];
    const result = { records, total: 1 };
    const displayed = toolQueryPresentation(result, 'list_e2et_customer');
    expect(displayed).toEqual({ ...result, modelCode: 'e2et_customer', chartType: 'table', columns: ['e2et_cust_name', 'e2et_cust_contact', 'active'] });
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

it('uses a table for canonical list contracts and hides all storage-only columns', () => {
  expect(toolQueryPresentation({ records: [{ tenant_id: 4, pid: 'private', created_at: 'private', row_version: 7 }] }, 'list:customer')).toMatchObject({ modelCode: 'customer', chartType: 'table', columns: [] });
});
it('deduplicates only a matching adjacent successful list contract', () => {
  expect(hasCanonicalListResult('list_customer', { type: 'result_contract', resultContract: { skillCode: 'list:customer', status: 'success' } })).toBe(true);
  for (const next of [undefined, { type: 'text' }, { type: 'result_contract', resultContract: { skillCode: 'list:other', status: 'success' } }, { type: 'result_contract', resultContract: { skillCode: 'list:customer', status: 'failed' } }]) expect(hasCanonicalListResult('list_customer', next)).toBe(false);
});
