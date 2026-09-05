import { beforeEach, describe, expect, it, vi } from 'vitest'

// The 16 bundled core plugins are replaced with minimal manifest stand-ins so the
// boot loop (not each plugin's own setup graph) is the unit under test.
vi.mock('~/plugins/core-demo', () => ({ default: { manifest: { code: 'core-demo' } } }))
vi.mock('~/plugins/core-bpm', () => ({ default: { manifest: { code: 'core-bpm' } } }))
vi.mock('~/plugins/core-designer', () => ({ default: { manifest: { code: 'core-designer' } } }))
vi.mock('~/plugins/core-automation', () => ({ default: { manifest: { code: 'core-automation' } } }))
vi.mock('~/plugins/core-organization', () => ({ default: { manifest: { code: 'core-organization' } } }))
vi.mock('~/plugins/core-meta', () => ({ default: { manifest: { code: 'core-meta' } } }))
vi.mock('~/plugins/core-aurabot', () => ({ default: { manifest: { code: 'core-aurabot' } } }))
vi.mock('~/plugins/core-ai-colleagues', () => ({ default: { manifest: { code: 'core-ai-colleagues' } } }))
vi.mock('~/plugins/core-personal', () => ({ default: { manifest: { code: 'core-personal' } } }))
vi.mock('~/plugins/core-settings', () => ({ default: { manifest: { code: 'core-settings' } } }))
vi.mock('~/plugins/core-platform', () => ({ default: { manifest: { code: 'core-platform' } } }))
vi.mock('~/plugins/core-admin', () => ({ default: { manifest: { code: 'core-admin' } } }))
vi.mock('~/plugins/core-ops', () => ({ default: { manifest: { code: 'core-ops' } } }))
vi.mock('~/plugins/core-dashboard', () => ({ default: { manifest: { code: 'core-dashboard' } } }))
vi.mock('~/plugins/core-email', () => ({ default: { manifest: { code: 'core-email' } } }))
vi.mock('~/plugins/core-decisionops', () => ({ default: { manifest: { code: 'core-decisionops' } } }))

const pluginLoader = {
  setFeatureGate: vi.fn(),
  list: vi.fn(() => []),
  install: vi.fn(),
  enable: vi.fn(),
  activateAll: vi.fn(async () => ['core-demo']),
}

vi.mock('../bootstrap.js', () => ({
  getKernel: vi.fn(() => ({ pluginLoader })),
  resetKernel: vi.fn(),
}))

import { getKernel, resetKernel } from '../bootstrap.js'
import { _resetBootState, bootCorePlugins } from '../boot-plugins.js'

describe('bootCorePlugins', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    pluginLoader.list.mockReturnValue([])
    pluginLoader.activateAll.mockImplementation(async () => ['core-demo'])
    vi.spyOn(console, 'info').mockImplementation(() => {})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    _resetBootState()
  })

  it('installs and enables every bundled core plugin on first boot', async () => {
    const activated = await bootCorePlugins()

    expect(pluginLoader.install).toHaveBeenCalledTimes(16)
    expect(pluginLoader.enable).toHaveBeenCalledTimes(16)
    expect(pluginLoader.enable).toHaveBeenCalledWith('core-demo')
    expect(pluginLoader.enable).toHaveBeenCalledWith('core-decisionops')
    expect(pluginLoader.activateAll).toHaveBeenCalledTimes(1)
    expect(activated).toEqual(['core-demo'])
    expect(getKernel).toHaveBeenCalledTimes(2)
  })

  it('is idempotent: a second boot short-circuits to already-active plugin codes', async () => {
    await bootCorePlugins()

    pluginLoader.list.mockReturnValue([
      { state: 'active', definition: { manifest: { code: 'core-demo' } } },
      { state: 'disabled', definition: { manifest: { code: 'core-bpm' } } },
    ])

    const second = await bootCorePlugins()

    expect(second).toEqual(['core-demo'])
    expect(pluginLoader.install).toHaveBeenCalledTimes(16) // only from the first boot
    expect(pluginLoader.activateAll).toHaveBeenCalledTimes(1)
  })

  it('passes the entitlement check to the loader before the short-circuit return', async () => {
    await bootCorePlugins()

    const hasFeature = (key: string) => key === 'bpm'
    await bootCorePlugins({ hasFeature })

    expect(pluginLoader.setFeatureGate).toHaveBeenCalledWith(hasFeature)
    expect(pluginLoader.activateAll).toHaveBeenCalledTimes(1)
  })

  it('force reboot resets the kernel with the entitlement check and boots from scratch', async () => {
    await bootCorePlugins()
    pluginLoader.list.mockReturnValue([
      { state: 'active', definition: { manifest: { code: 'core-meta' } } },
    ])

    const hasFeature = () => true
    const second = await bootCorePlugins({ hasFeature, force: true })

    expect(resetKernel).toHaveBeenCalledWith({ hasFeature })
    expect(pluginLoader.install).toHaveBeenCalledTimes(32)
    expect(pluginLoader.activateAll).toHaveBeenCalledTimes(2)
    expect(second).toEqual(['core-demo'])
  })

  it('continues booting the remaining plugins when one install throws', async () => {
    pluginLoader.install.mockImplementation((plugin: { manifest: { code: string } }) => {
      if (plugin.manifest.code === 'core-designer') {
        throw new Error('duplicate plugin code')
      }
    })

    await bootCorePlugins()

    expect(pluginLoader.enable).toHaveBeenCalledTimes(15)
    expect(console.warn).toHaveBeenCalledWith(
      '[boot-plugins] install failed for core-designer:',
      expect.any(Error),
    )
    expect(pluginLoader.activateAll).toHaveBeenCalledTimes(1)
  })

  it('reports an empty enterprise contribution list alongside the core count', async () => {
    await bootCorePlugins()

    expect(console.info).toHaveBeenCalledWith(
      expect.stringContaining('16 core + 0 ent'),
      'core-demo',
    )
  })
})
