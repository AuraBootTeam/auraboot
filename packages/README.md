# AuraBoot Framework Packages

Independent npm packages forming the AuraBoot platform contract layer.

| Package | Purpose | Stability |
|---------|---------|-----------|
| [`@auraboot/plugin-sdk`](./plugin-sdk) | `definePlugin`, `PluginContext`, registration APIs | 0.x — unstable |
| [`@auraboot/dsl-types`](./dsl-types) | PageSchema / BlockSchema / FieldSchema / ColumnSchema strong types | 0.x — unstable |
| [`@auraboot/nav-model`](./nav-model) | NavigationResource model + RouteRegistry contract | 0.x — unstable |

## Why separate packages

These three packages are **the contract surface between the platform kernel and plugins** (core, enterprise, third-party). They must:

- Be importable by plugin authors who do not have access to the web-admin source
- Have stable types independent of the web-admin runtime
- Be publishable to npm so external developers can build plugins against versioned APIs

Putting them under `packages/` (a top-level pnpm workspace) keeps them decoupled from the web-admin app while sharing tooling.

## Versioning

All three packages move together during 0.x. Each minor (0.X.0) may break APIs. Once 1.0 is reached (see [platform-pivot roadmap](../docs/plans/2026-04/cheerful-moseying-whale.md)), semver applies strictly.

## Build

```bash
pnpm -r --filter './packages/*' build
pnpm -r --filter './packages/*' typecheck
```
