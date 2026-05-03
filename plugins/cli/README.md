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

### `aura mcp serve`

Start the AuraBoot MCP stdio server so Cursor / Claude Code / Codex can use AuraBoot tools directly from the IDE.

The server refuses to start unless the current session has a tenant pinned (see `aura login`). Every tool invocation is logged to `~/.aura/mcp-audit.log`.

**Cursor** — `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` (global):

```jsonc
{
  "mcpServers": {
    "auraboot": {
      "command": "aura",
      "args": ["mcp", "serve"],
      "env": {
        "AURA_API_URL": "http://localhost:6443",
        "AURA_TOKEN": "${env:AURA_TOKEN}"
      }
    }
  }
}
```

**Claude Code** — `claude mcp add auraboot --command aura --args "mcp serve"` or `~/.claude/mcp_servers.json`:

```jsonc
{ "auraboot": { "command": "aura", "args": ["mcp", "serve"] } }
```

After editing the config, fully quit and relaunch Cursor / Claude Code — the MCP server list is read once at process start.

Ready-made copies live in [`examples/cursor.mcp.json.example`](./examples/cursor.mcp.json.example) and [`examples/claude-code.mcp_servers.json.example`](./examples/claude-code.mcp_servers.json.example).

The server REFUSES to start if the JWT carries no `tenantId` claim. Run `aura login --tenant <name>` first so writes always land on the intended tenant. Every tool invocation is journaled to `~/.aura/mcp-audit.log` so you can review what an AI session actually did.

**Tools currently exposed** (10 total, expanding to 12 in W2):

| Tool | Purpose |
|---|---|
| `query_entity` | Fetch records from any entity model with filters / sort. |
| `run_named_query` | Run a NamedQuery for aggregations / dashboards. |
| `list_agents` / `list_tools` | Inspect the ACP agent registry. |
| `dispatch_agent` | Hand a task off to an agent (Professional license). |
| `ask_aurabot` | Forward a natural-language question to AuraBot. |
| `query_dsl_capabilities` | Canonical map of supported kinds / blocks / data types — call before generating any schema. |
| `query_existing_models` | List models in tenant; call before `create_model` to avoid collisions. |
| `query_page_schemas` | List V2 page schemas; call before `create_page_schema`. |
| `describe_command_pipeline` | The 20+4-stage pipeline reference (no HTTP, pure docs). |

### `aura mcp list / add / remove / test / tools`

Manage *external* MCP server connections (the CLI as a client of other servers — distinct from `aura mcp serve` which makes AuraBoot itself a server).

```bash
aura mcp list
aura mcp add slack --transport sse --url http://localhost:3001
aura mcp test slack
aura mcp tools slack
```

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
| `AURA_TOKEN` | JWT for non-interactive auth; bypasses login and `~/.aura/credentials.json` | — |
| `AURA_API_URL` | Overrides backend base URL for all API commands | resolved from `~/.aura/config.json` |
| `AURA_USER` | Login email (used only when `AURA_TOKEN` is not set) | `admin@example.com` |
| `AURA_PASSWORD` | Login password (used only when `AURA_TOKEN` is not set) | `Test2026x` |
| `AURA_DEBUG` | When non-empty, prints a stderr debug line confirming `AURA_TOKEN` is active | — |

Credential priority: `--token` flag > `AURA_TOKEN` env var > `~/.aura/credentials.json` > interactive login.

Example (CI / `reset-and-init.sh`):

```bash
export AURA_TOKEN=$(curl -s -X POST http://localhost:6443/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Test2026x"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['jwt'])")

aura status
aura plugin publish plugins/showcase --yes
```

## Development

```bash
cd plugins/cli
pnpm install
pnpm build       # Compile TypeScript
pnpm lint        # Type-check without emitting
npx tsx src/index.ts plugin validate ../pcba-base  # Run directly
```
