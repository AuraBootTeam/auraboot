import { describe, expect, it } from 'vitest';
import { buildRouteContextState, decodeRouteContextFromSearch } from '../usePageRuntime';

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

  it('restores an explicitly carried workbench state without dropping route metadata', () => {
    expect(
      buildRouteContextState({
        returnTo: '/p/c/crm_opportunity_workspace',
        state: { searchKeyword: '华东智造云', viewFilter: 'proposal' },
      }),
    ).toMatchObject({
      searchKeyword: '华东智造云',
      viewFilter: 'proposal',
      routeContext: {
        returnTo: '/p/c/crm_opportunity_workspace',
      },
    });
  });

  it('ignores malformed or non-object route context values', () => {
    expect(decodeRouteContextFromSearch('?routeContext=not-json')).toBeNull();
    expect(
      decodeRouteContextFromSearch(`?routeContext=${encodeURIComponent(JSON.stringify(['bad']))}`),
    ).toBeNull();
  });
});
