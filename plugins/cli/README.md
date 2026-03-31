# @auraboot/plugin-cli

AuraBoot Plugin CLI — scaffold, validate, build, and publish plugins.

## Installation

```bash
# Run directly via npx (no install needed)
cd plugins/cli && pnpm install
npx tsx src/index.ts plugin <command>

# Or install globally
npm install -g @auraboot/plugin-cli
aura plugin <command>
```

## Commands

### `aura plugin validate [dir]`

Validate plugin configuration with 3-layer checks.

```bash
aura plugin validate .
aura plugin validate ../pcba-base
```

**Validation layers:**
1. **Structural** — JSON schema validation (plugin.json, models, fields, etc.)
2. **Semantic** — Cross-reference integrity, namespace consistency, executionConfig
3. **Governance** — i18n coverage, circular dependencies, permission completeness

### `aura plugin init [name]`

Create a new plugin from an interactive template.

```bash
aura plugin init my-plugin
```

Generates a complete plugin directory with:
- `plugin.json` manifest
- `config/models.json`, `fields.json`, `bindings.json`
- `config/commands.json`, `pages.json`
- `config/permissions.json`, `roles.json`, `menus.json`
- `config/dicts.json`, `i18n.json`

### `aura plugin build [dir]`

Build and package a plugin into a single JSON file.

```bash
aura plugin build . --output dist
```

Options:
- `-o, --output <dir>` — Output directory (default: `dist`)

### `aura plugin publish [dir]`

Publish a plugin to the AuraBoot platform.

```bash
aura plugin publish . --target http://localhost:5173
aura plugin publish . --target http://localhost:5173 --user admin@example.com --password Test2026x
```

Options:
- `-t, --target <url>` — Target platform URL (default: `http://localhost:5173`)
- `-u, --user <email>` — Login email (or `AURA_USER` env var)
- `-p, --password <password>` — Login password (or `AURA_PASSWORD` env var)
- `--yes` — Skip confirmation prompts

### `aura plugin diff [dir]`

Compare local plugin configuration vs remote platform state.

```bash
aura plugin diff . --target http://localhost:5173
```

Options:
- `-t, --target <url>` — Target platform URL (default: `http://localhost:5173`)
- `-u, --user <email>` — Login email
- `-p, --password <password>` — Login password

## Plugin Directory Structure

```
my-plugin/
  plugin.json              # Plugin manifest
  config/
    models.json            # Model definitions
    fields.json            # Field definitions
    bindings.json          # Model-field bindings
    commands.json          # Command definitions
    pages.json             # Page schema definitions
    permissions.json       # Permission definitions
    roles.json             # Role definitions
    menus.json             # Menu definitions
    dicts.json             # Dictionary definitions
    i18n.json              # i18n resources
```

Both flat file mode (single JSON array per resource type) and directory mode (one JSON file per resource) are supported.

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AURA_USER` | Login email | `admin@example.com` |
| `AURA_PASSWORD` | Login password | `Test2026x` |

## Development

```bash
cd plugins/cli
pnpm install
pnpm build       # Compile TypeScript
pnpm lint        # Type-check without emitting
npx tsx src/index.ts plugin validate ../pcba-base  # Run directly
```
