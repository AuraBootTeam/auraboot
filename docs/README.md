# Repository documentation index

User-facing documentation (guides, tutorials, API reference) lives at
[auraboot.com/docs](https://www.auraboot.com/docs) — bilingual (English / 简体中文).
This directory holds engineering-facing material that ships with the repository.

## Directory map

| Directory | Contents |
|-----------|----------|
| [`system-reference/`](./system-reference/) | Operational and system reference material: deployment evidence, performance measurement reports, runtime behavior notes. The largest set; start from dated files for point-in-time reports. |
| [`e2e/`](./e2e/) | End-to-end test documentation: profile matrix, acceptance matrix, and test-plan notes that accompany the Playwright suites under `web-admin/tests/e2e/`. |
| [`coverage/`](./coverage/) | Coverage campaign working notes and per-module coverage evidence snapshots. |
| [`assets/`](./assets/) | Images used by the documentation (screenshots, diagrams). |
| [`api-fixtures/`](./api-fixtures/) | Shared fixtures referenced by API contract tests. |

## Feature requirements and target contracts

| Topic | Reference | Status |
| --- | --- | --- |
| Announcement management | [Requirements, UX, permissions, BPM composition and implementation status](./system-reference/announcement-management.md) | S1–S8 merged; preview readiness and 60-second BPM graceful stop validated; current Core17/BPM4 regression and 8 reviewed originals, with the premature historical capture excluded; contracts, identities and limits in §18.30 and the [acceptance record](./system-reference/2026-10-25-announcement-s2-s8-testing-gate-acceptance-report.md). Related PRs merged; no production release. [Historical S1 evidence](./system-reference/2026-10-24-announcement-s1-testing-gate-acceptance-report.md) |
Release tooling reference: [Core Web component inputs and explicit reuse](./system-reference/core-web-component-reuse.md).

## Conventions

- Scheduled command delegation: [deployment bindings and execution boundaries](./system-reference/scheduled-command-bindings.md).
- Notification source delivery: [deployment descriptors and configuration boundaries](./system-reference/notification-source-bindings.md).
- Module-owned command validation: [live module registration and import metadata](./system-reference/application-module-command-lifecycle.md).
- Command consistency: [explicit transaction isolation admission](./system-reference/command-transaction-isolation.md).
- Original command files: [target-bound append and combined verification](./system-reference/2026-10-27-maritime-combined-files-testing-gate-acceptance-report.md).
- Query result reuse: [strict opt-out and root component verification](./system-reference/2026-10-27-maritime-query-cache-integration-report.md).
- Target scope: [successful invocation witness and scoped verification](./system-reference/2026-10-27-invocation-target-plan-integration-report.md).

- Architecture overview: start at [ARCHITECTURE.md](../ARCHITECTURE.md) (repo root), then dive into `system-reference/` for operational detail.
- Internal process documents (session handovers, plans, retrospectives) are intentionally **not tracked in this public repository** — `scripts/check-oss-no-internal-docs.sh` enforces this; they live in the team's private documentation repos.
- Documentation changes are gated by `docs.yml` / `docs-quality.yml` (markdown lint, link check, frontmatter governance via `scripts/check-docs-governance.mjs`).

CRUD runtime reference: [Generated CRUD default presentation](./system-reference/crud-default-presentation.md).

Focused browser regression: [Default CRUD regression](./system-reference/crud-regression.md).
