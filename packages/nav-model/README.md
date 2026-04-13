# @auraboot/nav-model

> Unified Navigation Resource model — one declaration drives router, menu, breadcrumb, tabs, permission, and feature gate.

**Status**: 0.x — unstable.

## Why this package exists

Today AuraBoot has navigation-related state spread across four sources:

- `menus.json` (per-plugin menu declarations)
- `permissions.json` (RBAC codes)
- React Router config (component bindings)
- `EntitlementContext` (feature gates)

Adding one menu requires touching 3-4 files; missing any one is a bug. This package introduces a single `NavigationResource` type that combines all four concerns, plus a `RouteRegistry` contract that derives router tree, menu tree, and breadcrumb from the registered resources.

## Usage

```ts
import { definePlugin } from '@auraboot/plugin-sdk'

export default definePlugin({
  manifest: { code: 'core.system', name: 'System', version: '0.1.0', kind: 'core' },
  setup(ctx) {
    ctx.registerNavigationResource({
      key: 'system.users',
      path: '/system/users',
      title: { en: 'Users', zh: '用户' },
      icon: 'users',
      component: () => import('./pages/UserListPage'),
      menu: { order: 10, group: 'system' },
      breadcrumb: true,
      permission: 'system.user.read',
      source: 'core',
      plugin: 'core.system',
    })
  },
})
```

## Concepts

| Concept | Purpose |
|---------|---------|
| `NavigationResource` | A page declaration combining route + menu + permission + feature |
| `RouteRegistry` | Contract for the kernel-side registry implementation |
| `MenuNode` | Derived menu tree node (built by registry from resources) |
| `BreadcrumbItem` | Derived breadcrumb item |

The actual `RouteRegistry` implementation lives in the kernel (`framework/routing/registry.ts`); this package only declares the contract.
