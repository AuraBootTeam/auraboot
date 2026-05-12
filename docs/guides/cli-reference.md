# CLI Reference

Complete reference for the Aura CLI -- authenticate, query data, execute commands, manage plugins, and inspect DSL resources from the terminal.

## Goal

Use `aura` to perform common platform operations without opening the browser: health checks, data queries, bulk record creation, plugin management, and DSL inspection.

## Prerequisites

- Node.js 18+ installed
- AuraBoot backend running (default: `http://localhost:6443`)
- Aura CLI installed globally:

```bash
npm install -g @auraboot/cli
```

---

## 1. Installation and Setup

### First-time login

```bash
aura login
```

Interactive mode prompts for:

1. **Server URL** (defaults to `http://localhost:6443`)
2. **Email** and **Password**
3. **Tenant selection** (if multiple tenants are available)

The JWT token is cached in `~/.aura/credentials.json` (mode 0600) and auto-renewed on expiry.

### Non-interactive login

```bash
aura login -u admin@auraboot.com -p Test2026x
```

### Login to a specific tenant

```bash
# By tenant name
aura login --tenant "Acme Corporation"

# Switch to the platform admin space
aura login --tenant System
```

### Credential priority

| Source | Priority |
|--------|----------|
| `--token` flag | Highest |
| `AURA_TOKEN` environment variable | High |
| `~/.aura/credentials.json` | Normal |
| Interactive login prompt | Lowest (fallback) |

---

## 2. Health Check

```bash
aura status
```

Checks backend connectivity, database status, and service health. Returns exit code 0 if healthy, 1 if any check fails.

---

## 3. Command Reference

### Quick reference table

| Command | Description |
|---------|-------------|
| `aura login` | Authenticate and cache token |
| `aura status` | Health check |
| `aura ask "..."` | AI-powered natural language query (SSE streaming) |
| `aura plan "..."` | Generate structured execution plan |
| `aura shell` | Interactive shell with tab completion |
| `aura query` | Query entity data |
| `aura exec` | Execute DSL commands |
| `aura create` | Bulk create records (pipeline) |
| `aura analyze` | AI-powered data analysis (pipeline) |
| `aura plugin validate` | Validate plugin structure |
| `aura plugin publish` | Import/publish a plugin |
| `aura plugin diff` | Compare local plugin with deployed version |
| `aura dsl list` | List DSL resources |
| `aura dsl inspect` | Inspect a specific DSL resource |
| `aura dsl status` | Show DSL resource status |
| `aura dsl deps` | Show resource dependency graph |
| `aura dsl diagnose` | Run diagnostics on DSL resources |
| `aura dsl scaffold` | Generate boilerplate plugin structure |
| `aura dsl sync-i18n` | Synchronize i18n translations |
| `aura crm leads` | List CRM leads |
| `aura crm opps` | List CRM opportunities |
| `aura crm accounts` | List CRM accounts |
| `aura crm dashboard` | Show CRM dashboard KPIs |
| `aura project list` | List projects |
| `aura project tasks` | List tasks (`--mine` for my tasks) |
| `aura project dashboard` | Show project dashboard KPIs |
| `aura ops agents list` | List AI agents |
| `aura ops agents show` | Show agent details |
| `aura ops runs list` | List agent execution history |
| `aura ops runs show` | Show run details |
| `aura ops audit list` | List audit trail |
| `aura ops audit show` | Show audit entry details |
| `aura ops tools list` | List available agent tools |
| `aura ops tools test` | Dry-run an agent tool |
| `aura mcp-server` | Start MCP server (stdio transport) |

---

## 4. Data Queries: `aura query`

Query any model's data using the Dynamic CRUD API.

### Basic usage

```bash
# List first 10 records
aura query crm_lead -n 10

# With filters
aura query crm_lead -f "crm_lead_status=NEW" -f "crm_lead_score>80"

# With sorting
aura query crm_lead -s crm_lead_score:desc

# Combine filters and sorting
aura query crm_lead -f "crm_lead_source=WEBSITE" -s crm_lead_score:desc -n 20
```

### Filter operators

| Operator | Symbol | Example |
|----------|--------|---------|
| Equal | `=` | `-f "status=active"` |
| Not equal | `!=` | `-f "status!=draft"` |
| Greater than | `>` | `-f "amount>1000"` |
| Greater or equal | `>=` | `-f "score>=80"` |
| Less than | `<` | `-f "count<10"` |
| Less or equal | `<=` | `-f "date<=2026-04-11"` |
| Like (contains) | `~` | `-f "name~acme"` |

### NamedQuery execution

```bash
# Execute a named query
aura query --nq crm_dashboard_kpi

# Named query with parameters
aura query --nq pm_project_status_distribution
```

### Output formats

```bash
# Table format (default, human-readable)
aura query crm_lead

# JSON format (machine-readable)
aura query crm_lead --format json

# Compact format (tab-separated)
aura query crm_lead --format compact
```

---

## 5. Command Execution: `aura exec`

Execute DSL commands (create, update, status transitions) from the terminal.

### Create a record

```bash
aura exec crm:create_lead \
  --set crm_lead_code="LD-001" \
  --set crm_lead_company="Acme Corp" \
  --set crm_lead_contact_name="Alice Wang" \
  --set crm_lead_source=website \
  --set crm_lead_status=new
```

### Type annotations

Non-string values require type suffixes:

```bash
aura exec inv:create_product \
  --set inv_product_name="Widget" \
  --set inv_product_price:float=29.99 \
  --set inv_product_quantity:int=500 \
  --set inv_product_active:bool=true \
  --set inv_product_tags:json='["electronics","sale"]'
```

| Type | Suffix | Example |
|------|--------|---------|
| String | `:string` (default) | `--set name="Acme"` |
| Integer | `:int` | `--set count:int=42` |
| Float | `:float` | `--set price:float=9.99` |
| Boolean | `:bool` | `--set active:bool=true` |
| JSON | `:json` | `--set config:json='{"k":"v"}'` |
| Null | `:null` | `--set notes:null=` |

### Update a record (status transition)

```bash
# Activate a record by PID
aura exec sc:activate_showcase --target 01ABCDEF

# With additional payload
aura exec pm:complete_task --target 01TASKID --set pm_task_completion_note="Done"
```

### Batch execution from file

```bash
# From a JSON array file
aura exec crm:create_lead --from leads.json

# From stdin
echo '{"crm_lead_code":"LD-X","crm_lead_company":"Test"}' | aura exec crm:create_lead --stdin
```

### Dry run (preview without executing)

```bash
aura exec crm:create_lead --set crm_lead_code=TEST --dry-run
```

Output shows the request body that would be sent:

```json
{
  "payload": {
    "crm_lead_code": "TEST"
  }
}
```

### Options reference

| Option | Description |
|--------|-------------|
| `--set <key=value>` | Set a field value (repeatable, supports `key:type=value`) |
| `--target <pid>` | Target record PID for update/transition commands |
| `--operation <type>` | Operation type override |
| `--from <file>` | Read payload from a JSON file (array = batch) |
| `--stdin` | Read payload from stdin |
| `--dry-run` | Preview the request without executing |

---

## 6. Plugin Management

### Validate plugin structure

```bash
aura plugin validate plugins/crm
```

Checks:
- `plugin.json` manifest schema
- Field definitions
- Command definitions
- Page schema structure
- i18n completeness

### Publish (import) a plugin

```bash
# Interactive mode (shows diff, asks for confirmation)
aura plugin publish plugins/crm

# Skip confirmation
aura plugin publish plugins/crm --yes
```

### Compare local vs deployed

```bash
aura plugin diff plugins/crm
```

Shows which resources would be added, modified, or removed.

### Scaffold a new plugin

```bash
aura dsl scaffold my-plugin
```

Generates the boilerplate directory structure:

```
plugins/my-plugin/
  plugin.json
  models.json
  fields.json
  commands.json
  pages/
  i18n/
  menus.json
  permissions.json
```

---

## 7. DSL Inspection

### List all DSL resources

```bash
aura dsl list
```

### Inspect a specific model

```bash
aura dsl inspect crm_lead
```

Shows the complete DSL definition: model metadata, fields, commands, pages, and permissions.

### Check resource status

```bash
aura dsl status
```

Reports which resources are published, draft, or have pending changes.

### Dependency graph

```bash
aura dsl deps crm_opportunity
```

Shows which models, fields, and pages depend on the specified resource.

### Run diagnostics

```bash
aura dsl diagnose
```

Checks for common issues: orphaned references, missing i18n keys, invalid expressions.

### Sync i18n translations

```bash
aura dsl sync-i18n
```

Scans all DSL resources and generates missing i18n entries.

---

## 8. Business Domain Commands

### CRM

```bash
# List leads
aura crm leads

# List opportunities
aura crm opps

# List accounts
aura crm accounts

# Dashboard KPIs
aura crm dashboard
```

### Project Management

```bash
# List projects
aura project list

# List all tasks
aura project tasks

# List my tasks only
aura project tasks --mine

# Dashboard
aura project dashboard
```

### Operations (AI Agents)

```bash
# List agents and their capabilities
aura ops agents list
aura ops agents show customer-support-agent

# View execution history
aura ops runs list
aura ops runs show 01RUNID

# Audit trail
aura ops audit list
aura ops audit show 01TRACEID

# Test a tool
aura ops tools list
aura ops tools test query-crm-data
```

---

## 9. Pipeline Commands

Chain commands using Unix pipes for data processing workflows.

### Query and filter

```bash
# Extract specific fields
aura query crm_lead -f "crm_lead_source=WEBSITE" --format json | jq '.[].crm_lead_contact_name'

# Dashboard KPI summary
aura query --nq crm_dashboard_kpi --format json | jq '{leads: .total_leads, pipeline: .pipeline_value}'
```

### AI analysis

```bash
# Analyze churn risk
aura query crm_lead --format json | aura analyze churn-risk

# Custom analysis prompt
aura query crm_opportunity --format json | aura analyze "which deals need attention this week"
```

### Pipeline composition

```bash
# Query qualified leads, analyze, and preview as tasks
aura query crm_lead -f "crm_lead_status=QUALIFIED" --format json \
  | aura analyze "rank by conversion likelihood" \
  | aura create pm_task --dry-run
```

---

## 10. Interactive Shell

```bash
aura shell
```

Features:
- Tab completion for commands
- Persistent authentication context
- Natural language input routed to AuraBot
- Command history

```
$ aura shell
  Aura Shell
  Connected to http://localhost:6443
  Type "help" for commands, "exit" to quit

aura> crm leads
  12 leads found
  ...

aura> ops agents list
  3 agents configured
  ...

aura> What were our top deals this month?
  [AI response streamed...]

aura> exit
```

---

## 11. MCP Server

Start AuraBoot as an MCP (Model Context Protocol) server for integration with AI coding tools like Claude Code:

```bash
aura mcp-server --token <jwt>
```

### Configuration for Claude Code

Add to `~/.claude/mcp_servers.json`:

```json
{
  "aura": {
    "command": "aura",
    "args": ["mcp-server", "--token", "<your-jwt-token>"]
  }
}
```

### Exposed tools

| Tool | Description | Parameters |
|------|-------------|------------|
| `query_entity` | Query any entity data | `entityCode`, `keyword?`, `filters?`, `limit?` |
| `run_named_query` | Execute a NamedQuery | `queryCode`, `params?`, `limit?` |
| `list_agents` | List AI agents | None |
| `list_tools` | List agent tools | None |
| `dispatch_agent` | Dispatch an agent task | `taskPid` |
| `ask_aurabot` | Ask AuraBot a question | `question` |

---

## 12. Output Formats and Agent Mode

### Output formats

| Format | Flag | Use Case |
|--------|------|----------|
| Table | (default) | Human-readable terminal output |
| JSON | `--format json` | Machine processing, piping |
| Compact | `--format compact` | Tab-separated, minimal |

### Agent mode

Optimized output for AI agents (no colors, spinners, or decorations):

```bash
# Via flag
aura crm leads --agent-mode

# Via environment variable
AURA_AGENT_MODE=1 aura crm leads
```

### Exit codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Failure (API error, network error) |
| 2 | User cancelled |
| 3 | Permission denied (403) |
| 4 | Not found (404) |
| 5 | Authentication required (401, auto-renewal failed) |

---

## 13. Configuration

### Config file: `~/.aura/config.json`

```json
{
  "defaultEnv": "local",
  "environments": {
    "local": {
      "baseUrl": "http://localhost:6443"
    },
    "staging": {
      "baseUrl": "https://staging.example.com"
    },
    "production": {
      "baseUrl": "https://app.example.com"
    }
  },
  "output": "table"
}
```

### Environment variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AURA_TOKEN` | JWT token (overrides credentials file) | None |
| `AURA_BASE_URL` | Server URL | `http://localhost:6443` |
| `AURA_AGENT_MODE` | Enable agent mode (`1` = on) | Off |
| `AURA_ENV` | Environment name from config | `defaultEnv` value |

### Credentials file: `~/.aura/credentials.json`

```json
{
  "local": {
    "jwt": "eyJ...",
    "email": "admin@auraboot.com",
    "expiresAt": "2026-04-12T08:00:00Z"
  }
}
```

File permissions are set to `0600` (owner read/write only).

---

## 14. Core vs Enterprise Commands

Some commands require an Enterprise license:

| Command | Core (Open Source) | Enterprise |
|---------|-------------------|------------|
| `login`, `status`, `query`, `exec` | Yes | Yes |
| `plugin`, `dsl` | Yes | Yes |
| `ops agents/audit` (read-only) | Yes | Yes |
| `ask` (basic AI) | Yes | Yes |
| `run dispatch` (agent execution) | Upgrade prompt | Yes |
| `plan` (structured planning) | Upgrade prompt | Yes |
| `ops tools test` (tool dry-run) | Upgrade prompt | Yes |

When a Core user invokes an Enterprise command, the CLI displays an upgrade prompt instead of an error.

---

## Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| `Error: ECONNREFUSED` | Backend not running | Start the backend: `./gradlew bootRun` |
| Exit code 5 | Token expired and renewal failed | Run `aura login` |
| Exit code 3 on query | Missing DYNAMIC permission | Check `DYNAMIC.{model}.read` permission |
| Empty results from `aura query` | Wrong tenant context | Run `aura login --tenant "correct tenant"` |
| `--from` file not found | Relative path issue | Use absolute path or ensure CWD is correct |
| Plugin publish fails | Validation errors | Run `aura plugin validate` first |
| NQ returns generic structure | Missing `format=records` | CLI handles this automatically; update CLI if stale |

---

## Next Steps

- [Data Import & Export](data-import-export.md) -- bulk data operations with the CLI
- [Formulas and Expressions](formulas-and-expressions.md) -- computed fields and expressions
- [Multi-Tenancy](multi-tenancy.md) -- tenant management
