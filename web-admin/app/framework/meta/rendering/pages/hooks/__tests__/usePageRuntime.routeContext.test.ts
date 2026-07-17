import { describe, expect, it } from 'vitest';
import { decodeRouteContextFromSearch } from '../usePageRuntime';

describe('decodeRouteContextFromSearch', () => {
  it('decodes JSON route context from URL search params', () => {
    const context = {
      source: 'ALARM',
      recordId: 'ALM-PLC01-HBLOSS',
      deviceCode: 'PLC-01-GW',
    };
    const search = `?routeContext=${encodeURIComponent(JSON.stringify(context))}`;

    expect(decodeRouteContextFromSearch(search)).toEqual(context);
  });

  it('ignores malformed or non-object route context values', () => {
    expect(decodeRouteContextFromSearch('?routeContext=not-json')).toBeNull();
    expect(
      decodeRouteContextFromSearch(`?routeContext=${encodeURIComponent(JSON.stringify(['bad']))}`),
    ).toBeNull();
  });
});
