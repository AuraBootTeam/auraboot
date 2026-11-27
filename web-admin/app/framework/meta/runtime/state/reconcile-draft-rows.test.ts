import { describe, expect, it } from 'vitest';
import { reconcileDraftRows } from './reconcile-draft-rows';

const rule = {identityFields: ['source', 'quote'], preserveFields: ['quantity']};
describe('draft rows reconcile against fresh facts', () => {
  it('keeps exact editable values by typed composite identity and adopts fresh order and facts', () => {
    const old = [{source: 'a', quote: 'q', quantity: '0.000001', revision: 1}, {source: 'b', quote: 'q', quantity: '', revision: 1}];
    const fresh = [{source: 'b', quote: 'q', quantity: '99', revision: 2}, {source: 'a', quote: 'q', quantity: '99', revision: 3}];
    expect(reconcileDraftRows(fresh, old, rule)).toEqual([{...fresh[0], quantity: ''}, {...fresh[1], quantity: '0.000001'}]);
    expect(old[0].revision).toBe(1); expect(fresh[0].quantity).toBe('99');
  });
  it('drops absent rows, initializes new rows and resets ineligible drafts to the server value', () => {
    const old = [{source: 'a', quote: 'q', quantity: '40'}, {source: 'removed', quote: 'q', quantity: '60'}];
    const fresh = [{source: 'a', quote: 'q', quantity: '0', eligible: false}, {source: 'new', quote: 'q', quantity: '25', eligible: true}];
    expect(reconcileDraftRows(fresh, old, rule, row => row.eligible === true)).toEqual(fresh);
  });
  it('never treats a numeric identity as the equivalent text identity', () => {
    expect(reconcileDraftRows([{source: '1', quote: 'q', quantity: '20'}], [{source: 1, quote: 'q', quantity: '99'}], rule)[0].quantity).toBe('20');
  });
  it('rejects duplicate/missing identities and unsafe or overlapping fields', () => {
    const row = {source: 'a', quote: 'q', quantity: '40'};
    for (const [fresh, old] of [[[row, row], [row]], [[row], [row, row]], [[{quote: 'q'}], []]] as any)
      expect(() => reconcileDraftRows(fresh, old, rule)).toThrow();
    for (const invalid of [{...rule, identityFields: []}, {...rule, preserveFields: ['source']}, {...rule, preserveFields: ['__proto__']}, {...rule, preserveFields: ['nested.value']}])
      expect(() => reconcileDraftRows([row], [row], invalid)).toThrow();
  });
});
