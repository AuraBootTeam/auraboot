import { describe, expect, it } from 'vitest';
import { resolveFeatureEntitlement } from '../EntitlementContext';

const entitlement = (
  status: string,
  features: string[],
) => ({
  pluginId: 'ent.demo',
  status,
  planCode: 'test',
  features,
});

describe('resolveFeatureEntitlement', () => {
  it('fails closed before the first entitlement snapshot resolves', () => {
    expect(
      resolveFeatureEntitlement(false, false, [], 'ent_demo'),
    ).toBe(false);
  });

  it('fails closed when entitlement enforcement is unavailable or disabled', () => {
    expect(
      resolveFeatureEntitlement(true, false, [], 'ent_demo'),
    ).toBe(false);
  });

  it('requires both an active entitlement and the requested feature', () => {
    expect(
      resolveFeatureEntitlement(
        true,
        true,
        [entitlement('active', ['ent_demo'])],
        'ent_demo',
      ),
    ).toBe(true);
    expect(
      resolveFeatureEntitlement(
        true,
        true,
        [entitlement('expired', ['ent_demo'])],
        'ent_demo',
      ),
    ).toBe(false);
    expect(
      resolveFeatureEntitlement(
        true,
        true,
        [entitlement('active', ['ent_other'])],
        'ent_demo',
      ),
    ).toBe(false);
  });
});
