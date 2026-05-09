# AuraBoot Usage FAQ

> Common usage questions, separate from licensing (see [LICENSE-FAQ.md](LICENSE-FAQ.md))
> and security disclosure (see [SECURITY.md](SECURITY.md)).
>
> This file is filled out as questions arrive. New entries land at the top of the
> relevant section. Last updated: 2026-05-09.

---

## Getting started

### Q. The 5-minute quickstart didn't work for me. What now?

1. Run `scripts/dev/verify-quickstart.sh` — it scripts the whole quickstart and prints what failed.
2. Check the [Troubleshooting](docs/getting-started/troubleshooting.md) page (TODO before launch).
3. Open a `[Question]` issue with the verifier output. We aim for first-response within 24h during beta.

### Q. Do I need Java 21? I'm on Java 17.

Yes — AuraBoot uses Java 21 language features (pattern matching, virtual threads). The Docker image bundles its own runtime, so you only need Java locally if you're building from source.

### Q. I'm behind a corporate proxy. Anything I should know?

- The platform itself doesn't require outbound internet by default (see [TELEMETRY.md](TELEMETRY.md)).
- Plugin marketplace browsing reaches `plugins.auraboot.com`; either whitelist that or run with the marketplace tab disabled.
- LLM-backed features need access to whichever provider you've configured (OpenAI, Anthropic, Zhipu, MiniMax, or local Ollama).
- Docker image pulls go through your registry mirror; configure `docker daemon.json` accordingly.

### Q. Can I run AuraBoot on Apple Silicon (M1/M2/M3)?

Yes — the official Docker images are multi-arch (`linux/amd64` and `linux/arm64`).

### Q. Can I use MySQL instead of PostgreSQL?

Not in the community edition. AuraBoot relies on `pgvector` for the RAG knowledge base and on Postgres-specific features (JSONB GIN indexes, materialized views in Pivot queries). MySQL adapter is on the enterprise roadmap with no committed date.

---

## Data, schema, migrations

### Q. Where is my data stored?

In your PostgreSQL database. The platform metadata (model definitions, page configs, command pipelines) lives in `meta_*` tables; business data lives in tables created when plugins are installed (e.g. `tcrm_lead` from crm-quick-start).

Drop AuraBoot tomorrow and your tables remain queryable — there's no proprietary file format. Schema is documented in [docs/architecture/data-model.md](docs/architecture/data-model.md) (TODO).

### Q. Will my schema change between minor versions?

During the 0.x beta:
- Platform tables (`meta_*`, `sys_*`) may evolve. Migrations run automatically on boot.
- Plugin tables follow the plugin's own version. Each plugin handles its own migrations.
- Breaking schema changes are called out in `### Breaking changes` of [CHANGELOG.md](CHANGELOG.md) with explicit migration instructions.

After 1.0, semver applies strictly: schema breaks only on major version bumps.

### Q. How do I migrate from another low-code platform?

There's no automated importer in the community edition. The general path:
1. Export your data as CSV from the source platform.
2. Define equivalent models in AuraBoot's DSL.
3. Use the bulk-import endpoint (`/api/{model}/import`) per model.

If you have a substantial migration (>10K records, complex relationships), license@auraboot.com — we offer migration consulting.

---

## Plugins & customization

### Q. How do I write a custom plugin?

See [docs/plugin-development/](docs/plugin-development/). The short version: a plugin is a directory with `plugin.json` + a `config/` tree of declarative resources (models, fields, commands, pages). Drop it into `plugins/` and restart.

A plugin scaffolding CLI exists (`pnpm aura plugin init <name>`) for the basic boilerplate.

### Q. Can I distribute / sell my plugin commercially?

Yes — your plugin is your own work, with its own license. AuraBoot's source-available license does not propagate to plugin code (see [LICENSE-FAQ.md Q15](LICENSE-FAQ.md#q15-can-i-build-and-sell-plugins-for-auraboot)).

### Q. The 20-stage pipeline doesn't have an extension point I need.

Open an issue describing the use case. We're conservative about adding stages — each one is a new contract — but real gaps get added. As workaround, the `handler` stage accepts arbitrary Java/TS code; you can do the orchestration there until we add a proper stage.

---

## Performance & scale

### Q. What's the performance baseline?

Documented benchmark suite is on the M2 roadmap. As a rough guide, a single AuraBoot instance on a 4 vCPU / 8 GB host handles **a few hundred QPS for typical CRUD endpoints** with PostgreSQL on the same host. Headroom mostly depends on the plugin pipeline complexity (number of stages + side effects per command).

If you have a specific scale target (e.g., 10K writes/min), open an issue or email — we'll size it together.

### Q. Can I run multiple instances behind a load balancer?

Yes, with caveats:
- All instances must share the same Postgres + Redis.
- The plugin loader caches plugins in-memory per-instance; plugin install/uninstall events trigger a refresh broadcast over Redis pub/sub. Without Redis (or a misconfigured one), instances drift.
- Sticky sessions are not required (JWT-based auth) but recommended for the WebSocket / SSE channels (AuraBot streams).

---

## AI features

### Q. Do I have to use the AI features?

No. They're entirely optional. If you don't configure an LLM provider, the AI panel is hidden. The platform doesn't degrade when AI is off.

### Q. Can I use a local LLM (Ollama, vLLM)?

Yes. Configure `auraboot.ai.provider=openai-compatible` with `base-url=http://your-ollama:11434/v1` and a dummy api-key. AuraBoot speaks the OpenAI-compatible chat-completions API.

### Q. Does the RAG knowledge base ingest my files into a vendor cloud?

No. Files are stored in your Postgres + filesystem (configurable to S3/MinIO). Vector embeddings are computed by your configured provider (which could be local Ollama). Document content never leaves your infrastructure unless you point AI at a cloud provider.

### Q. ChatBI generated wrong SQL. What do I do?

Several knobs:
- Tighten the model description (DSL `description` fields are inputs to the prompt). Vague names = vague queries.
- Switch to a more capable LLM (gpt-4o, claude-sonnet, glm-4-plus typically beat smaller models for SQL).
- Use the "include only these tables" filter to narrow scope.
- Open an issue with the failing prompt + expected SQL so we can improve the prompt template.

---

## Operations

### Q. How do I back up?

Standard Postgres backup applies. `pg_dump` on the AuraBoot database covers everything. Plugin code in `plugins/` is in your file system; back it up if you've added custom plugins.

### Q. How do I monitor the platform?

OpenTelemetry is built in. Configure `MANAGEMENT_TRACING_*` and point at your collector (Tempo, Grafana Cloud, Honeycomb, …). Sentry SDK is also wired — set `SENTRY_DSN`. See [docs/operations/](docs/operations/).

### Q. The audit log is huge. Can I prune it?

Yes — the platform keeps audit forever by default, but `auraboot.audit.retention-days` can cap it. Past that age, rows move to `audit_archive_*` partitions which you can drop on your own schedule. Don't delete from `meta_audit` directly; use the provided pruning command.

---

## Don't see your question?

- GitHub Discussions: https://github.com/AuraBootTeam/auraboot/discussions
- Discord: https://discord.gg/auraboot
- For commercial / partnership questions: license@auraboot.com

We add new questions here whenever they come up more than twice.
