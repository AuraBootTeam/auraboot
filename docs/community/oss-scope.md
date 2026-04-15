# Open-Source Scope

This document defines what is and is not part of AuraBoot's open-source distribution. It complements the source-of-truth manifest at the repo root: [`oss-scope.json`](../../oss-scope.json).

> The manifest is consumed by tooling (test runner, CI filters, release scripts). Always update the manifest when adjusting scope; this doc is the human-readable companion.

## What is in OSS (Community Edition)

### Platform engine (core differentiation)

- DSL engine (model / field / command / page) — auto-generates CRUD
- Command Pipeline (20-stage, SideEffect, state machine, Precondition)
- Plugin framework (L1 / L2 / Platform / Solution layers, Marketplace UI)
- Multi-tenancy (TenantLineInterceptor, tenant isolation)
- RBAC (roles / permissions / menus / data scopes)
- Event System (AuraEventBus, publishAfterCommit)
- NamedQuery (named queries, data source abstraction)
- Roll-Up Summary (declarative parent-child aggregation)
- JSONB virtual fields
- Consistency rules (DSL validation, invariants)
- Auto-numbering (`{PREFIX}-{yyyyMMdd}-{seq}`)

### Three open designers

- Page Designer (5 page types: list / form / detail / home / dashboard)
- BPMN Designer (9 node types)
- Automation Editor (6 triggers + 9 actions, SSE realtime debug)

### AI full stack (all OSS)

- ACP (Agent Control Plane) — Mission Control, 8 LLM providers
- AuraBot — conversational assistant, context-aware
- ChatBI — natural-language data query
- RAG knowledge base — PDF/DOCX/MD/CSV/HTML parsing, pgvector search
- AI Lead Scoring
- Agent Tool Auto-Generator (DSL → MCP tool)
- Intent-Driven Dev

### Workflow & Automation

- BPM (SmartEngine 3.7, process definition / instance / task)
- Automation (trigger → condition → action, template library)
- Webhook (Outbox pattern, HMAC signature, SpEL filter)

### Notification & Inbox foundations

- Notification (in-app, email, SMS) — IM and unified Inbox advanced behaviors are paid

### Infrastructure

- File storage (Local / MinIO / S3 / OSS, CDN integration)
- Observability (Prometheus + Grafana + Jaeger, 8 prebuilt dashboards)
- CLI (25 commands, MCP Server)
- Global search (Cmd+K)
- i18n (3-layer resolution, zh / en / ja / ko)
- Multi-environment config (dev / staging / prod isolation)

### Demo / starter plugins (in `plugins/`)

| Plugin | Namespace | Models | Purpose |
|---|---|---|---|
| `crm-starter` | `crms` | 6 | Account / Contact / Lead / Opportunity / Activity / Campaign |
| `org-management` | `org` | 3 | Department / Position / Employee |
| `platform-admin` | `admin` | 0 | DSL-driven admin console (8 management pages) |
| `page-manager` | `pgm` | 1 | Built-in page-schema management |
| `showcase` | `sc` | 1+ | 20+ field-type demo + Smart Components |
| `agent-control-plane` | `acp` | — | AI Mission Control (8 LLM providers) |
| `acp-showcase` | `acs` | — | ACP demo bundle |

## What is NOT in OSS (commercial editions)

See the strategy doc in the enterprise repo for full pricing tiers. High-level:

- **Dashboard Designer** and **Report Designer** (paid: Standard tier)
- **IM** (real-time chat, WebSocket conversations) and **unified Inbox** advanced flows (paid: Professional)
- **Mobile apps** (iOS SwiftUI + Android Compose) — paid: Enterprise
- **Full business plugins**: Sales / Procurement / Inventory / Finance / Quality / full CRM (18 models) / Project Management / Contract & Cost (paid: Standard / Professional)
- **Industry solution packs**: PCBA-ERP / Quarry / etc. (paid per-pack)
- **Enterprise integrations**: OAuth2 / social login / Entitlement & License system / Payment / Stripe (paid: Professional / Enterprise)

The `admin_entitlements` menu lives in the enterprise-only `platform-admin-ee` plugin; it is intentionally absent from this repo's `platform-admin`.

## How to verify what is OSS

### Run only OSS tests

```bash
./scripts/oss-test.sh
```

This script reads `oss-scope.json` and runs Playwright with a strict per-project `testMatch` derived from `test_paths` (with `test_excludes` for paid features that share a directory with OSS tests). No enterprise spec leaks in.

### Reset the OSS standalone environment

```bash
DATABASE_USERNAME=<your-pg-user> ./scripts/oss-reset-and-init.sh
```

This:
1. Drops + recreates the database
2. Starts the backend
3. Bootstraps admin user + tenant
4. Imports the 8 OSS plugins listed above
5. Starts the frontend

After completion, http://localhost:5173 is a fully functional OSS deployment.

### Test naming-space convention

When writing platform-tier tests (`tests/e2e/platform/**`, `integration/**`, `cross-module/**`), use OSS namespaces only (`crms` / `org` / `admin` / `pgm` / `sc` / `e2eto` / `acp`). Business-domain tests (`tests/e2e/sales/**`, `finance/**`, etc.) follow the open-source status of their domain and should be added to `oss-scope.json` `test_excludes` if paid. See the rule in the enterprise standards doc `docs/standards/testing-e2e-web.md`.

## Adjusting scope

Scope is a configuration, not code. To move a feature between OSS and enterprise:

1. Move the source files (Java + JSON) between repos
2. Add / remove the plugin code in `oss-scope.json` `plugins` and the test paths in `test_paths`
3. If a directory contains a mix of OSS and paid specs, add the specific paid files to `test_excludes`
4. Update `scripts/oss-reset-and-init.sh` `PLUGINS_TO_IMPORT` if a new OSS plugin should be auto-installed
5. Run `./scripts/oss-test.sh` to verify the suite still passes against the new scope

## Migration history

For per-PR migration audit, see commits touching `oss-scope.json` and the strategy doc §8 in the enterprise repo (`docs/strategy/01-开源范围与功能划线.md`).
