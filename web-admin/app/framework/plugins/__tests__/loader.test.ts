import { describe, it, expect, vi } from 'vitest'
import { definePlugin } from '@auraboot/plugin-sdk'
import { PluginLoader } from '../loader'
import { RouteRegistryImpl } from '../../routing/registry'
import { SlotRegistry } from '../../extensions/slot-registry'
import { WidgetRegistry, ColumnRendererRegistry } from '../../widgets/widget-registry'
import { DataSourceRegistry } from '../../data-source/registry'

const makeLoader = (features: string[] = []) => {
  const featureSet = new Set(features)
  return new PluginLoader({
    routeRegistry: new RouteRegistryImpl(),
    slotRegistry: new SlotRegistry(),
    widgetRegistry: new WidgetRegistry(),
    columnRegistry: new ColumnRendererRegistry(),
    dataSourceRegistry: new DataSourceRegistry(),
    hasFeature: (k) => featureSet.has(k),
  })
}

const stubPlugin = (code: string, opts: { features?: string[]; deps?: string[]; setup?: (ctx: any) => void } = {}) =>
  definePlugin({
    manifest: {
      code,
      name: code,
      version: '0.1.0',
      kind: 'oss',
      ...(opts.features ? { license: { featureKeys: opts.features } } : {}),
      ...(opts.deps ? { dependencies: { plugins: opts.deps } } : {}),
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

  it('marks dependent inactive when dependency missing', async () => {
    const loader = makeLoader()
    loader.install(stubPlugin('b', { deps: ['a'] }))
    loader.enable('b')
    await loader.activateAll()
    expect(loader.list()[0]!.state).toBe('enabled')
    expect(loader.list()[0]!.inactiveReason).toMatch(/missing dependency: a/)
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
