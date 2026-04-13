import { describe, it, expect } from 'vitest'
import { definePlugin } from '@auraboot/plugin-sdk'
import { createKernel, getKernel, resetKernel } from '../bootstrap'

describe('bootstrap', () => {
  it('createKernel produces independent instances', () => {
    const a = createKernel()
    const b = createKernel()
    expect(a.routeRegistry).not.toBe(b.routeRegistry)
    expect(a.pluginLoader).not.toBe(b.pluginLoader)
  })

  it('getKernel returns the same singleton', () => {
    resetKernel()
    expect(getKernel()).toBe(getKernel())
  })

  it('resetKernel installs new hasFeature predicate', async () => {
    const k = resetKernel({ hasFeature: (key) => key === 'enabled.feature' })

    k.pluginLoader.install(definePlugin({
      manifest: {
        code: 'test.gated',
        name: 'Gated',
        version: '0.1.0',
        kind: 'enterprise',
        license: { featureKeys: ['disabled.feature'] },
      },
      setup: () => {},
    }))
    k.pluginLoader.enable('test.gated')
    const activated = await k.pluginLoader.activateAll()
    expect(activated).toEqual([])
    expect(k.pluginLoader.list()[0]!.inactiveReason).toMatch(/missing feature/)
  })

  it('end-to-end: install plugin → activate → routes show up', async () => {
    const k = createKernel()
    k.pluginLoader.install(definePlugin({
      manifest: { code: 'core.demo', name: 'Demo', version: '0.1.0', kind: 'core' },
      setup(ctx) {
        ctx.registerNavigationResource({
          key: 'demo.home',
          path: '/demo',
          title: 'Demo',
          source: 'plugin',
          menu: { order: 100 },
        })
      },
    }))
    k.pluginLoader.enable('core.demo')
    await k.pluginLoader.activateAll()

    expect(k.routeRegistry.findByKey('demo.home')?.path).toBe('/demo')
    const menu = k.routeRegistry.buildMenuTree({ permissions: [], features: [] })
    expect(menu.map(m => m.key)).toEqual(['demo.home'])
  })
})
