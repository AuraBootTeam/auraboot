# @auraboot/cli

AuraBoot Plugin CLI — scaffold, validate, build, and publish plugins.

## Installation

```bash
# Run directly via npx (no install needed)
cd plugins/cli && pnpm install
npx tsx src/index.ts plugin <command>

# Or install globally
npm install -g @auraboot/cli
aura plugin <command>
```

## Commands

### `aura plugin validate [dir]`

Validate plugin configuration with 3-layer checks.

```bash
aura plugin validate .
aura plugin validate ../pcba-base
aura plugin validate . --agent-mode   # aggregated JSON for AI agents
```

Add `--agent-mode` (or `--format json`) to emit a single aggregated report —
`{ ok, errorCount, warningCount, errors[], warnings[] }` — so an AI agent can fix
a whole batch of problems in one pass, then retry. Each entry carries `code`,
`message`, and (where the validator provides them) `path`, `expected`, and an
imperative `agentInstruction`. Exit code is non-zero when there are errors.

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
aura plugin publish . --target http://localhost:5173 --user admin@auraboot.com --password Test2026x
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

**Tool profiles** — the server exposes a **minimal default** and requires high-risk
capabilities to be enabled explicitly (mirrors NocoBase's `x-mcp-packages`). Select
a profile with `aura mcp serve --profile <name>` or the `AURA_MCP_PROFILE` env var:

| Profile | Tools | Use when |
|---|---|---|
| `read` (default) | the 10 read/discovery tools | querying, dashboards, analysis |
| `dsl-authoring` | `read` + `create_model` / `create_page_schema` / `create_command` | building models & pages |
| `full` | everything, incl. `import_plugin` / `rollback_import` | trusted deploy / rollback sessions |

**Tools** (15 total: 10 read + 5 write, all writes default to `dryRun:true`):

| Tool | Tier | Purpose |
|---|---|---|
| `query_entity` | read | Fetch records from any entity model with filters / sort. |
| `run_named_query` | read | Run a NamedQuery for aggregations / dashboards. |
| `list_agents` / `list_tools` | read | Inspect the ACP agent registry. |
| `dispatch_agent` | read | Hand a task off to an agent (Professional license). |
| `ask_aurabot` | read | Forward a natural-language question to AuraBot. |
| `query_dsl_capabilities` | read | Canonical map of supported kinds / blocks / data types — call before generating any schema. |
| `query_existing_models` | read | List models in tenant; call before `create_model` to avoid collisions. |
| `query_page_schemas` | read | List V2 page schemas; call before `create_page_schema`. |
| `describe_command_pipeline` | read | The 20+4-stage pipeline reference (no HTTP, pure docs). |
| `create_model` | author | Create a dynamic model (dryRun-safe). |
| `create_page_schema` | author | Create a V2 page schema (dryRun-safe). |
| `create_command` | author | Create a command definition (dryRun-safe). |
| `import_plugin` | admin | Import a plugin config bundle. |
| `rollback_import` | admin | Roll back a prior plugin import. |

### `aura mcp list / add / remove / test / tools`

Manage *external* MCP server connections (the CLI as a client of other servers — distinct from `aura mcp serve` which makes AuraBoot itself a server).

```bash
aura mcp list
aura mcp add slack --transport sse --url http://localhost:3001
aura mcp test slack
aura mcp tools slack
```

### `aura init`

One-shot onboarding for a workspace: install the end-user Skills into your agent
clients **and** wire up the AuraBoot MCP server config, in one command.

```bash
aura init                                   # all clients, under cwd
aura init --client claude,cursor --root .   # explicit clients + root
```

Writes the `mcpServers.auraboot` entry into `.cursor/mcp.json` (Cursor) and
`.mcp.json` (Claude Code), merging with any existing servers. Codex (TOML config)
is reported as a manual step. Then run `aura doctor` to verify.

### `aura doctor`

Verify the setup end to end:

```bash
aura doctor            # human-readable
aura doctor --agent-mode   # JSON: { ok, checks: [{ name, ok, detail }] }
```

Checks: **skills** installed & fresh, **credentials** present, **tenant** pinned,
**backend** reachable (`/actuator/health`). Exits non-zero if any check fails;
offline checks (skills) still report correctly when the backend is down.

### `aura skills`

Install AuraBoot's **end-user Skills** into your agent client (Claude Code / Cursor / Codex).
Skills are the discovery + workflow layer: they tell your agent *when* to use AuraBoot and
*in what order*, and route everything through the `aura` CLI. They never execute writes
themselves — the platform stays the sole authority on permissions.

```bash
aura skills list                                  # the 6 bundled skills
aura skills install                               # into all clients under cwd
aura skills install --client claude --root .      # just Claude Code, explicit root
aura skills check                                 # installed / stale / not-installed
aura skills remove --client cursor
```

Clients and their skill directories:

| Client | Directory |
|---|---|
| `claude` | `.claude/skills/` |
| `cursor` | `.cursor/skills/` |
| `codex` | `.agents/skills/` |

Bundled skills: `auraboot-data-modeling`, `auraboot-ui-builder`, `auraboot-workflow`,
`auraboot-permissions`, `auraboot-runtime-ops`, `auraboot-dsl-gitops`. Add `--agent-mode`
to `list` / `check` for JSON output. Restart the agent client after install so it
re-scans the skills directory.

### `aura dsl plan / apply / drift` (desired-state reconciler)

Manage a plugin's DSL as version-controlled desired state. `plan` fingerprints
every resource and diffs it against the last-applied baseline
(`<plugin>/.aura/dsl-state.json`), producing a create / update / destroy plan
with a **risk level**:

| Risk | Meaning |
|---|---|
| `L0` | no changes |
| `L1` | create-only (new resources) |
| `L2` | updates to existing resources |
| `L3` | destroys (deleted/dropped resources) |

```bash
aura dsl plan .                 # what would change vs last apply (+ risk level)
aura dsl plan . --agent-mode    # JSON: { pluginId, hasBaseline, create[], update[], destroy[], riskLevel, changed }
aura dsl drift .                # exit 1 if local DSL drifted from last apply
aura dsl apply . --dry-run      # show the plan without publishing
aura dsl apply .                # publish to the instance, then record the new baseline
aura dsl apply . --yes          # required to approve an L3 (destructive) plan
```

`plan` / `drift` / `apply --dry-run` are fully local. `apply` (without `--dry-run`)
publishes to a reachable instance and needs a running backend; L3 plans are
refused unless `--yes` is passed, aligning with the platform approval gate. A live
`pull` (fetch instance → baseline) and live drift-vs-instance are the next step.

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
| `AURA_USER` | Login email (used only when `AURA_TOKEN` is not set) | `admin@auraboot.com` |
| `AURA_PASSWORD` | Login password (used only when `AURA_TOKEN` is not set) | `Test2026x` |
| `AURA_DEBUG` | When non-empty, prints a stderr debug line confirming `AURA_TOKEN` is active | — |
| `AURA_MCP_PROFILE` | MCP tool scope for `aura mcp serve`: `read` / `dsl-authoring` / `full` (overridden by `--profile`) | `read` |

Credential priority: `--token` flag > `AURA_TOKEN` env var > `~/.aura/credentials.json` > interactive login.

Example (CI / `reset-and-init.sh`):

```bash
export AURA_TOKEN=$(curl -s -X POST http://localhost:6443/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@auraboot.com","password":"Test2026x"}' \
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
