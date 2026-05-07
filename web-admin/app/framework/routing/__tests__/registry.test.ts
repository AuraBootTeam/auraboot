import { describe, it, expect } from 'vitest'
import type { NavigationResource } from '@auraboot/nav-model'
import { RouteRegistryImpl } from '../registry'

const r = (overrides: Partial<NavigationResource> & Pick<NavigationResource, 'key' | 'path' | 'title'>): NavigationResource => ({
  source: 'core',
  ...overrides,
})

describe('RouteRegistryImpl', () => {
  it('register + findByKey + findByPath', () => {
    const reg = new RouteRegistryImpl()
    reg.register(r({ key: 'sys.users', path: '/system/users', title: 'Users' }))
    expect(reg.findByKey('sys.users')?.path).toBe('/system/users')
    expect(reg.findByPath('/system/users')?.key).toBe('sys.users')
  })

  it('throws on path conflict', () => {
    const reg = new RouteRegistryImpl()
    reg.register(r({ key: 'a', path: '/x', title: 'A' }))
    expect(() => reg.register(r({ key: 'b', path: '/x', title: 'B' }))).toThrow(/path conflict/)
  })

  it('registerBatch is atomic — rolls back on failure', () => {
    const reg = new RouteRegistryImpl()
    reg.register(r({ key: 'a', path: '/x', title: 'A' }))
    expect(() =>
      reg.registerBatch([
        r({ key: 'b', path: '/y', title: 'B' }),
        r({ key: 'c', path: '/x', title: 'C' }), // conflict with /x
      ]),
    ).toThrow(/path conflict/)
    expect(reg.findByKey('b')).toBeUndefined()
  })

  it('buildRouteTree nests by parentKey', () => {
    const reg = new RouteRegistryImpl()
    reg.register(r({ key: 'sys', path: '/system', title: 'System' }))
    reg.register(r({ key: 'sys.users', path: '/system/users', title: 'Users', parentKey: 'sys' }))
    reg.register(r({ key: 'sys.roles', path: '/system/roles', title: 'Roles', parentKey: 'sys' }))
    const tree = reg.buildRouteTree()
    expect(tree).toHaveLength(1)
    expect(tree[0]!.children).toHaveLength(2)
    expect(tree[0]!.children.map(c => c.resource.key)).toEqual(['sys.users', 'sys.roles'])
  })

  it('register inline children auto-sets parentKey', () => {
    const reg = new RouteRegistryImpl()
    reg.register(r({
      key: 'sys',
      path: '/system',
      title: 'System',
      children: [
        r({ key: 'sys.users', path: '/system/users', title: 'Users' }),
      ],
    }))
    expect(reg.findByKey('sys.users')?.parentKey).toBe('sys')
  })

  it('buildBreadcrumb walks parentKey chain', () => {
    const reg = new RouteRegistryImpl()
    reg.register(r({ key: 'sys', path: '/system', title: 'System' }))
    reg.register(r({ key: 'sys.users', path: '/system/users', title: 'Users', parentKey: 'sys' }))
    reg.register(r({ key: 'sys.users.detail', path: '/system/users/:id', title: 'Detail', parentKey: 'sys.users' }))
    const trail = reg.buildBreadcrumb('/system/users/:id')
    expect(trail.map(b => b.key)).toEqual(['sys', 'sys.users', 'sys.users.detail'])
  })

  it('buildBreadcrumb skips items with breadcrumb=false', () => {
    const reg = new RouteRegistryImpl()
    reg.register(r({ key: 'a', path: '/a', title: 'A' }))
    reg.register(r({ key: 'b', path: '/b', title: 'B', parentKey: 'a', breadcrumb: false }))
    reg.register(r({ key: 'c', path: '/c', title: 'C', parentKey: 'b' }))
    const trail = reg.buildBreadcrumb('/c')
    expect(trail.map(t => t.key)).toEqual(['a', 'c'])
  })

  it('buildBreadcrumb returns empty for unknown path', () => {
    const reg = new RouteRegistryImpl()
    expect(reg.buildBreadcrumb('/nope')).toEqual([])
  })

  it('updates reverse path index when re-registering an existing key', () => {
    const reg = new RouteRegistryImpl()
    reg.register(r({ key: 'sys.users', path: '/system/users', title: 'Users' }))
    reg.register(r({ key: 'sys.users', path: '/system/people', title: 'Users' }))

    expect(reg.findByPath('/system/users')).toBeUndefined()
    expect(reg.findByPath('/system/people')?.key).toBe('sys.users')
    expect(() =>
      reg.register(r({ key: 'sys.legacy-users', path: '/system/users', title: 'Legacy Users' })),
    ).not.toThrow()
  })
})
