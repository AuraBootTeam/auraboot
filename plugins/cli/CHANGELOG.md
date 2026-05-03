# Changelog

All notable changes to `@auraboot/plugin-cli`. Format follows [Keep a Changelog](https://keepachangelog.com/) and the project uses [Semantic Versioning](https://semver.org/).

## 2.0.0 — pending (GAP-300 Layer 1)

### Breaking
- **Removed top-level `aura mcp-server` command.** The MCP stdio server is now invoked via the subcommand `aura mcp serve`. There is **no alias and no deprecation warning** — this is a hard rename per the AuraBoot dev-stage breaking-change rule. Any existing Cursor / Claude Code / Codex configuration that points to `aura mcp-server` must be updated to `["mcp", "serve"]`.

### Added
- `aura mcp serve` subcommand starts the AuraBoot MCP stdio server.
- Multi-tenant pin: the server now refuses to start unless the current JWT carries a `tenantId` claim, preventing AI writes against the wrong tenant.
- Local audit log at `~/.aura/mcp-audit.log`: every tool invocation records timestamp, tenant, duration, and success/error.
- New read-only MCP tools for write-tool context: `query_dsl_capabilities`, `query_existing_models`, `query_page_schemas`, `describe_command_pipeline`.
- `ApiClient.put<T>(path, body)` and `ApiClient.delete<T>(path)` helpers (paving the way for write tools in W2).

### Internal
- Refactored MCP server to use a central `ToolRegistry` (`src/mcp/registry.ts`) so each tool is one file under `src/mcp/tools/read/` (and, in W2, `src/mcp/tools/write/`). Behavior of the original 6 read tools is unchanged.
