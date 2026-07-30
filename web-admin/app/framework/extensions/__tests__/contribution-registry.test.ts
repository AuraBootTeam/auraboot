import { describe, expect, it } from 'vitest'

import { ContributionRegistry } from '../contribution-registry'

describe('ContributionRegistry', () => {
  it('fails closed on duplicate IDs and reports both the key and owners', () => {
    const registry = new ContributionRegistry()
    registry.register('component-loader', 'ent.bom', {
      id: 'bom-review',
      load: async () => ({}),
    })

    expect(() =>
      registry.register('component-loader', 'ent.goods', {
        id: 'bom-review',
        load: async () => ({}),
      }),
    ).toThrow(
      "component-loader 'bom-review' from 'ent.goods' conflicts with 'ent.bom'",
    )
  })

  it('gates item-level features without hiding the diagnostic owner', () => {
    const registry = new ContributionRegistry((key) => key !== 'paid.bom')
    registry.register('renderer', 'ent.bom', {
      id: 'bom-diff',
      component: {},
      featureKey: 'paid.bom',
    })

    expect(registry.getRenderer('bom-diff')).toBeUndefined()
    expect(registry.listDiagnostics()).toEqual([
      expect.objectContaining({
        id: 'bom-diff',
        plugin: 'ent.bom',
        status: 'gated',
      }),
    ])
  })

  it('can receive the entitlement gate before activation without changing identity', () => {
    const registry = new ContributionRegistry()
    registry.setFeatureGate(() => false)
    registry.register('renderer', 'ent.bom', {
      id: 'bom-diff',
      component: {},
      featureKey: 'paid.bom',
    })

    expect(registry.getRenderer('bom-diff')).toBeUndefined()
    expect(registry.listDiagnostics()[0]?.status).toBe('gated')
  })

  it('orders hooks and decorators by explicit priority then stable identity', () => {
    const registry = new ContributionRegistry()
    registry.register('page-runtime-hook', 'ent.z', {
      id: 'late',
      phase: 'after-create',
      priority: 1,
      run: () => undefined,
    })
    registry.register('page-runtime-hook', 'ent.a', {
      id: 'early',
      phase: 'after-create',
      priority: 10,
      run: () => undefined,
    })

    expect(
      registry.listPageRuntimeHooks('after-create').map((hook) => hook.id),
    ).toEqual(['early', 'late'])
  })

  it('allows one primary service and multiple explicitly identified decorators', () => {
    const registry = new ContributionRegistry()
    registry.register('service-provider', 'core.org', {
      id: 'default',
      token: 'organization',
      provider: {},
    })
    registry.register('service-provider', 'ent.org', {
      id: 'audit',
      token: 'organization',
      provider: {},
      mode: 'decorator',
    })

    expect(registry.getPrimaryService('organization')?.id).toBe('default')
    expect(registry.getServiceDecorators('organization').map((item) => item.id))
      .toEqual(['audit'])
  })
})
