# @auraboot/plugin-sdk

> AuraBoot Plugin SDK — the contract surface plugin authors program against.

**Status**: 0.x — unstable. APIs may break between minor versions.

## Install

```bash
pnpm add @auraboot/plugin-sdk
```

## Usage

```ts
import { definePlugin } from '@auraboot/plugin-sdk'

export default definePlugin({
  manifest: {
    code: 'my-plugin',
    name: 'My Plugin',
    version: '0.1.0',
    kind: 'oss',
  },
  setup(ctx) {
    ctx.registerNavigationResource({
      key: 'my.page',
      path: '/my-page',
      title: { en: 'My Page', zh: '我的页面' },
      menu: true,
    })
  },
})
```

## What's in here

- `definePlugin(definition)` — type-safe plugin entrypoint
- `PluginManifest` — manifest interface (code, version, kind, dependencies, license, features)
- `PluginContext` — registration APIs available during plugin setup
- `PluginState` — five-state lifecycle (`discovered` / `installed` / `enabled` / `licensed` / `active`)

See [the platform pivot roadmap](https://github.com/AuraBootTeam/auraboot/blob/main/docs/plans/2026-04/cheerful-moseying-whale.md) for the full architecture.

## Stability

Until 1.0, breaking changes happen at minor versions. Pin exact versions in production.
