import {describe, expect, it} from 'vitest';
import {resolveDetailRelatedLinks} from './detailLayoutValues';
const config = {field: 'orders', labelField: 'code', to: '/p/orders/view/{pid}'};
describe('related detail links', () => {
  it('uses only explicit records and an internal detail route', () => {
    expect(resolveDetailRelatedLinks(config, {orders: [{pid: 'order1', code: 'PO-001'}]})).toEqual([{pid: 'order1', label: 'PO-001', to: '/p/orders/view/order1'}]);
    expect(resolveDetailRelatedLinks(config, {orders: []})).toEqual([]);
    expect(resolveDetailRelatedLinks(undefined, {})).toEqual([]);
  });
  it('rejects unsafe navigation, missing facts, duplicate records and object labels', () => {
    for (const to of ['https://external.invalid/{pid}', '//external.invalid/{pid}', '/p/orders/view/{pid}?token=x', '/p/orders/view/{unknown}'])
      expect(() => resolveDetailRelatedLinks({...config, to}, {orders: []})).toThrow();
    for (const record of [{}, {orders: {}}, {orders: [{pid: '../escape', code: 'PO'}]}, {orders: [{pid: 'id', code: {name: 'PO'}}]}, {orders: [{pid: 'id', code: 'PO'}, {pid: 'id', code: 'PO'}]}])
      expect(() => resolveDetailRelatedLinks(config, record)).toThrow();
  });
});
