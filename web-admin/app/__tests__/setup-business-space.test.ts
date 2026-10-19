import { describe, expect, it } from 'vitest';
import { resolveSetupBusinessSpace } from '../../tests/api/setup/_helpers';

describe('setup business-space identity', () => {
  it('selects the named business tenant independently of platform/default order', () => {
    const spaces = [
      { tenantName:'System',spaceType:'platform',tenantId:1 },
      { tenantName:'Other business',spaceType:'business',tenantId:2,isDefault:true },
      { tenantName:'AuraBoot Dev',spaceType:'business',tenantId:'public-tenant' },
    ];
    expect(resolveSetupBusinessSpace(spaces,'AuraBoot Dev')).toEqual({tenantId:'public-tenant'});
    expect(resolveSetupBusinessSpace([...spaces].reverse(),'AuraBoot Dev')).toEqual({tenantId:'public-tenant'});
  });

  it('honors an explicit declared tenant id and never falls back when it is missing', () => {
    const spaces=[{tenantName:'Other business',spaceType:'business',tenantId:'tenant-other'},
      {tenantName:'AuraBoot Dev',spaceType:'business',tenantId:'tenant-bootstrap'}];
    expect(resolveSetupBusinessSpace(spaces,'AuraBoot Dev','tenant-other')).toEqual({tenantId:'tenant-other'});
    expect(() => resolveSetupBusinessSpace(spaces,'AuraBoot Dev','absent')).toThrow(/exactly one/);
  });

  it('accepts an exact safe numeric tenant identity without coercing it', () => {
    expect(resolveSetupBusinessSpace([{tenantName:'Owned',spaceType:'business',tenantId:42}],'Owned')).toEqual({tenantId:42});
  });

  it('refuses a missing business tenant rather than using a platform or unrelated tenant', () => {
    expect(() => resolveSetupBusinessSpace([{tenantName:'Owned',spaceType:'platform',tenantId:1}],'Owned')).toThrow(/exactly one/);
    expect(() => resolveSetupBusinessSpace([{tenantName:'Other',spaceType:'business',tenantId:2}],'Owned')).toThrow(/exactly one/);
  });

  it('refuses ambiguous names, absent lists and an empty declared name', () => {
    const space={tenantName:'Owned',spaceType:'business',tenantId:1};
    expect(() => resolveSetupBusinessSpace([space,space],'Owned')).toThrow(/exactly one/);
    expect(() => resolveSetupBusinessSpace(null,'Owned')).toThrow(/space list/);
    expect(() => resolveSetupBusinessSpace([space],'')).toThrow(/space list/);
  });

  it('rejects absent, empty, nonpositive and unsafe numeric tenant identities', () => {
    for (const tenantId of [null,undefined,'',0,-1,Number.MAX_SAFE_INTEGER+1,{}]) {
      expect(() => resolveSetupBusinessSpace([{tenantName:'Owned',spaceType:'business',tenantId}],'Owned')).toThrow(/safe tenant identity/);
    }
  });
});
