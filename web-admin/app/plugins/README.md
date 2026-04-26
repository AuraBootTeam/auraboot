# app/plugins/

> Built-in OSS plugins, written against `@auraboot/plugin-sdk`.

## Convention

Each plugin lives in its own directory:

```
plugins/
├── core-demo/          ← canonical example
│   ├── manifest.ts     ← PluginManifest
│   ├── index.ts        ← definePlugin({ manifest, setup })
│   ├── pages/          ← React components used by registered routes
│   ├── components/     ← internal components
│   ├── hooks/
│   ├── services/
│   └── README.md
└── core-system/        ← (M3) users / roles / tenants
└── core-iam/           ← (M3) permissions
└── core-platform/      ← (M3) plugin manager
└── ...
```

## Naming

| Prefix | Distribution | Repo |
|--------|--------------|------|
| `core-*` | OSS, ships with kernel | `auraboot/web-admin/app/plugins/` |
| `ent-*` | Enterprise commercial | `auraboot-enterprise/web-admin-ext/plugins/` (overlay) |
| `sol-*` | Industry/vertical solution | `auraboot-solutions/...` |

Current examples:
- `core-dashboard` now ships both `DashboardViewer` and `DashboardDesigner` in OSS.
- Enterprise no longer provides `ent-dashboard-designer`; paid analytics deltas live in `ent-charts-pro`, and enterprise-only workbench additions live in `ent-dashboard-workbench`.

## Boundaries

A plugin **may**:
- Import from `@auraboot/plugin-sdk`, `@auraboot/dsl-types`, `@auraboot/nav-model`
- Import from `~/framework` (the kernel public API)
- Import from `~/ui` (shared UI primitives)
- Import its own internal modules

A plugin **must not**:
- Import from `~/framework/internal/*` (M3.3 ESLint enforces)
- Import from another plugin's internal modules (M3.3 ESLint enforces)
- Modify kernel singletons except via the registration APIs in `PluginContext`

## Adding a plugin

1. `mkdir core-my-feature`
2. Write `manifest.ts` and `index.ts`
3. Add the plugin to the boot manifest (`app/framework/boot-manifest.ts` once it exists)
4. Run `pnpm test:unit` to validate

See [core-demo](./core-demo) for the canonical shape.
