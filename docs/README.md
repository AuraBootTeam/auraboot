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
| Announcement management | [Requirements, UX, permissions, BPM composition and implementation status](./system-reference/announcement-management.md) | S1 verified; S2–S8 partial real-stack evidence and remaining work tracked in §18 and the [current acceptance record](./system-reference/2026-10-25-announcement-s2-s8-testing-gate-acceptance-report.md). Latest BPM composition and durable reminders remain unfinished; [historical S1 evidence](./system-reference/2026-10-24-announcement-s1-testing-gate-acceptance-report.md) |

## Conventions

- Architecture overview: start at [ARCHITECTURE.md](../ARCHITECTURE.md) (repo root), then dive into `system-reference/` for operational detail.
- Internal process documents (session handovers, plans, retrospectives) are intentionally **not tracked in this public repository** — `scripts/check-oss-no-internal-docs.sh` enforces this; they live in the team's private documentation repos.
- Documentation changes are gated by `docs.yml` / `docs-quality.yml` (markdown lint, link check, frontmatter governance via `scripts/check-docs-governance.mjs`).
