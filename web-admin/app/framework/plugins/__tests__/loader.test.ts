import { describe, it, expect, vi } from 'vitest'
import { definePlugin } from '@auraboot/plugin-sdk'
import { PluginLoader } from '../loader'
import { RouteRegistryImpl } from '../../routing/registry'
import { SlotRegistry } from '../../extensions/slot-registry'
import { WidgetRegistry, ColumnRendererRegistry } from '../../widgets/widget-registry'
import { DataSourceRegistry } from '../../data-source/registry'
import { ContributionRegistry } from '../../extensions/contribution-registry'

const makeLoader = (features: string[] = []) => {
  const featureSet = new Set(features)
  return new PluginLoader({
    routeRegistry: new RouteRegistryImpl(),
    slotRegistry: new SlotRegistry(),
    widgetRegistry: new WidgetRegistry(),
    columnRegistry: new ColumnRendererRegistry(),
    dataSourceRegistry: new DataSourceRegistry(),
    contributionRegistry: new ContributionRegistry((k) => featureSet.has(k)),
    hasFeature: (k) => featureSet.has(k),
  })
}

const stubPlugin = (code: string, opts: { features?: string[]; licenseRequired?: boolean; deps?: string[]; phase?: 'foundation' | 'feature' | 'application'; setup?: (ctx: any) => void } = {}) =>
  definePlugin({
    manifest: {
      code,
      name: code,
      version: '0.1.0',
      kind: 'oss',
      ...(opts.features
        ? {
            license: {
              featureKeys: opts.features,
              ...(opts.licenseRequired === undefined
                ? {}
                : { required: opts.licenseRequired }),
            },
          }
        : {}),
      ...(opts.deps ? { dependencies: { plugins: opts.deps } } : {}),
      ...(opts.phase ? { activationPhase: opts.phase } : {}),
    },
    setup: opts.setup ?? (() => {}),
  })

describe('PluginLoader', () => {
  it('install + enable + activate happy path', async () => {
    const loader = makeLoader()
    const setup = vi.fn()
    loader.install(stubPlugin('core.test', { setup }))
    loader.enable('core.test')
    const activated = await loader.activateAll()
    expect(activated).toEqual(['core.test'])
    expect(setup).toHaveBeenCalledOnce()
    expect(loader.list()[0]!.state).toBe('active')
  })

  it('does not activate if not enabled', async () => {
    const loader = makeLoader()
    loader.install(stubPlugin('core.test'))
    expect(await loader.activateAll()).toEqual([])
    expect(loader.list()[0]!.state).toBe('installed')
  })

  it('refuses installation with duplicate code', () => {
    const loader = makeLoader()
    loader.install(stubPlugin('a'))
    expect(() => loader.install(stubPlugin('a'))).toThrow(/already installed/)
  })

  it('blocks activation when feature not entitled', async () => {
    const loader = makeLoader([]) // no features entitled
    loader.install(stubPlugin('ent.x', { features: ['ent_x'] }))
    loader.enable('ent.x')
    await loader.activateAll()
    const rec = loader.list()[0]!
    expect(rec.state).toBe('enabled')
    expect(rec.inactiveReason).toMatch(/missing feature: ent_x/)
  })

  it('activates when feature entitled', async () => {
    const loader = makeLoader(['ent_x'])
    loader.install(stubPlugin('ent.x', { features: ['ent_x'] }))
    loader.enable('ent.x')
    expect(await loader.activateAll()).toEqual(['ent.x'])
  })

  it('does not gate a plugin whose license requirement is explicitly optional', async () => {
    const loader = makeLoader([])
    loader.install(stubPlugin('ent.optional', {
      features: ['ent_optional'],
      licenseRequired: false,
    }))
    loader.enable('ent.optional')

    expect(await loader.activateAll()).toEqual(['ent.optional'])
  })

  it('respects plugin dependencies (topological)', async () => {
    const loader = makeLoader()
    const order: string[] = []
    loader.install(stubPlugin('a', { setup: () => order.push('a') }))
    loader.install(stubPlugin('b', { deps: ['a'], setup: () => order.push('b') }))
    loader.install(stubPlugin('c', { deps: ['b'], setup: () => order.push('c') }))
    loader.enable('a'); loader.enable('b'); loader.enable('c')
    await loader.activateAll()
    expect(order).toEqual(['a', 'b', 'c'])
  })

  it('orders activation phases and rejects dependencies on a later phase', async () => {
    const loader = makeLoader()
    const order: string[] = []
    loader.install(stubPlugin('app', {
      phase: 'application',
      setup: () => order.push('app'),
    }))
    loader.install(stubPlugin('foundation', {
      phase: 'foundation',
      setup: () => order.push('foundation'),
    }))
    loader.install(stubPlugin('invalid', {
      phase: 'foundation',
      deps: ['app'],
      setup: () => order.push('invalid'),
    }))
    loader.enable('app')
    loader.enable('foundation')
    loader.enable('invalid')

    await loader.activateAll()

    expect(order).toEqual(['foundation', 'app'])
    expect(
      loader.list().find((record) => record.definition.manifest.code === 'invalid')
        ?.inactiveReason,
    ).toMatch(/later activation phase/)
  })

  it('marks dependent inactive when dependency missing', async () => {
    const loader = makeLoader()
    loader.install(stubPlugin('b', { deps: ['a'] }))
    loader.enable('b')
    await loader.activateAll()
    expect(loader.list()[0]!.state).toBe('enabled')
    expect(loader.list()[0]!.inactiveReason).toMatch(/missing dependency: a/)
  })

  it('does not activate a plugin whose installed dependency is disabled', async () => {
    const loader = makeLoader()
    loader.install(stubPlugin('a'))
    loader.install(stubPlugin('b', { deps: ['a'] }))
    loader.enable('b')

    expect(await loader.activateAll()).toEqual([])
    expect(
      loader.list().find((record) => record.definition.manifest.code === 'b')
        ?.inactiveReason,
    ).toMatch(/dependency not enabled: a/)
  })

  it('does not activate a dependent after its dependency setup fails', async () => {
    const loader = makeLoader()
    const dependentSetup = vi.fn()
    loader.install(stubPlugin('a', {
      setup: () => {
        throw new Error('dependency setup failed')
      },
    }))
    loader.install(stubPlugin('b', { deps: ['a'], setup: dependentSetup }))
    loader.enable('a')
    loader.enable('b')

    expect(await loader.activateAll()).toEqual([])
    expect(dependentSetup).not.toHaveBeenCalled()
    expect(
      loader.list().find((record) => record.definition.manifest.code === 'b')
        ?.inactiveReason,
    ).toMatch(/dependency inactive: a/)
  })

  it('records setup error and keeps state at licensed', async () => {
    const loader = makeLoader()
    loader.install(stubPlugin('boom', { setup: () => { throw new Error('kaboom') } }))
    loader.enable('boom')
    await loader.activateAll()
    const rec = loader.list()[0]!
    expect(rec.state).toBe('licensed')
    expect(rec.inactiveReason).toMatch(/setup failed: kaboom/)
  })

  it('plugin context registers navigation resources with plugin attribution', async () => {
    const routes = new RouteRegistryImpl()
    const loader = new PluginLoader({
      routeRegistry: routes,
      slotRegistry: new SlotRegistry(),
      widgetRegistry: new WidgetRegistry(),
      columnRegistry: new ColumnRendererRegistry(),
      dataSourceRegistry: new DataSourceRegistry(),
      contributionRegistry: new ContributionRegistry(),
      hasFeature: () => true,
    })
    loader.install(definePlugin({
      manifest: { code: 'core.demo', name: 'Demo', version: '0.1.0', kind: 'core' },
      setup(ctx) {
        ctx.registerNavigationResource({
          key: 'demo.home',
          path: '/demo',
          title: 'Demo',
          source: 'plugin',
        })
      },
    }))
    loader.enable('core.demo')
    await loader.activateAll()
    expect(routes.findByKey('demo.home')?.plugin).toBe('core.demo')
  })

  it('action registration + invoke', async () => {
    const loader = makeLoader()
    loader.install(definePlugin({
      manifest: { code: 'core.actions', name: 'A', version: '0.1.0', kind: 'core' },
      setup(ctx) {
        ctx.registerAction({
          code: 'demo.add',
          handler: async (...args: unknown[]) => (args[0] as number) + (args[1] as number),
        })
      },
    }))
    loader.enable('core.actions')
    await loader.activateAll()
    expect(await loader.invoke('demo.add', 2, 3)).toBe(5)
  })
})
