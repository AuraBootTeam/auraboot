import { describe, it, expect } from 'vitest'
import { routesFromResources, collectPluginRoutes } from '../react-router-bridge'
import type { NavigationResource } from '@auraboot/nav-model'

describe('routesFromResources', () => {
  it('emits route entries for resources with meta.file', () => {
    const resources: NavigationResource[] = [
      {
        key: 'bpm.task-center',
        path: '/bpm/task-center',
        title: 'Task Center',
        source: 'plugin',
        meta: { file: './plugins/core-bpm/pages/TaskCenter.tsx' },
      },
    ]
    const out = routesFromResources(resources)
    expect(out).toHaveLength(1)
    // RouteConfigEntry shape comes from React Router 7 — assert structural fields.
    expect(out[0]).toMatchObject({
      path: '/bpm/task-center',
      file: './plugins/core-bpm/pages/TaskCenter.tsx',
    })
  })

  it('skips resources without meta.file', () => {
    const resources: NavigationResource[] = [
      { key: 'menu-only', path: '/x', title: 'X', source: 'plugin' },
    ]
    expect(routesFromResources(resources)).toHaveLength(0)
  })

  it('preserves order', () => {
    const resources: NavigationResource[] = [
      { key: 'a', path: '/a', title: 'A', source: 'plugin', meta: { file: './a.tsx' } },
      { key: 'b', path: '/b', title: 'B', source: 'plugin', meta: { file: './b.tsx' } },
      { key: 'c', path: '/c', title: 'C', source: 'plugin', meta: { file: './c.tsx' } },
    ]
    const out = routesFromResources(resources)
    expect(out.map((r: any) => r.path)).toEqual(['/a', '/b', '/c'])
  })
})

describe('collectPluginRoutes', () => {
  it('flattens routes from multiple providers', () => {
    const a = { routes: () => [{ path: '/a', file: './a.tsx' } as any] }
    const b = { routes: () => [{ path: '/b', file: './b.tsx' } as any, { path: '/c', file: './c.tsx' } as any] }
    expect(collectPluginRoutes([a, b]).map((r: any) => r.path)).toEqual(['/a', '/b', '/c'])
  })
})
