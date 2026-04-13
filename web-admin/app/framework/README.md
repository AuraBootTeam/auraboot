# framework/

> AuraBoot web-admin **kernel** — the stable contract surface plugins program against.

This directory is the core of the platform. Everything here is part of the public API consumed by:

- `app/plugins/core-*` — built-in OSS plugins
- `auraboot-enterprise/web-admin-ext/plugins/ent-*` — enterprise overlay plugins
- Third-party plugins (eventually)

## Layering

| Subdir | Purpose | Stability |
|--------|---------|-----------|
| `routing/` | RouteRegistry impl — single source for routes/menu/breadcrumb derived from `NavigationResource` declarations | Public API |
| `plugins/` | PluginLoader — discovers plugins, runs `setup(ctx)`, manages 5-state lifecycle | Public API |
| `extensions/` | Slot system + extension point registry | Public API |
| `widgets/` | Widget + ColumnRenderer registry with `propsSchema` validation | Public API |
| `access/` | Permission + Feature gate evaluators | Public API |
| `data-source/` | DataSource provider registry — resolves `DataSourceRef` at runtime | Public API |
| `command/` | Command system — registration, dispatch, hooks | Public API |

## Stability rules

- **Plugins MUST NOT import** anything outside `framework/`'s public exports
- **Core MUST NOT import** anything from `app/plugins/**` (other than via the registry)
- ESLint enforces both via `no-restricted-imports` (added in M2)

## Boundary with `app/`

`app/` outside `framework/` contains:
- `app/admin/`, `app/iam/`, etc. — legacy OSS modules being migrated to `app/plugins/core-*`
- `app/components/`, `app/ui/` — shared UI primitives (not framework, not plugin)
- `app/routes/` — route entry files (will become a thin shell that delegates to RouteRegistry)
- `app/server/` — SSR/BFF (orthogonal to plugin kernel)

See `docs/plans/2026-04/cheerful-moseying-whale.md` for the platform pivot roadmap.
