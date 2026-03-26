# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0-beta.1] - 2026-03-15

### Added
- Entitlement system: 5 tables, 4-layer enforcement (API gateway, command stage, frontend context, admin UI), JWT RS256 license validation
- DSL Capability Registry: 27 closed enums, semantic validator, CLI loader, and REST endpoint
- SubTableViewer with command-based inline add/delete on detail pages
- MONEY field type with multi-currency support, currency and exchange rate management
- Integration test suites covering Automation, Webhook, Inbox, SavedView, Organization Team, Auth, and User (100+ new scenarios)
- 13 new E2E spec files covering 8 plugin domains (36+ tests)

### Changed
- Brand rename from AuraMeta to AuraBoot across the entire codebase

### Fixed
- 112 pre-existing backend integration test failures resolved
- 44 failing E2E tests across 8 root causes stabilized
- CommandSideEffectExecutor `${recordId}` resolution bug

## [0.9.0] - 2026-03-13

### Added
- IM (Instant Messaging) module: WebSocket + REST API, sequence-based sync for multi-device, offline, and read receipts
- Plugin Marketplace: browse, install, publish, upgrade tracking, README/screenshots, admin override, and universal review system
- Agent Control Plane L2: seed agents, LLM_NATIVE plan, Capability Graph, cross-agent collaboration protocol, Agent-BPM bridge
- DSL Standardization v1: semantic enrichment, validation gates, quality scorecard, 346 commands backfilled with agent metadata
- Automation engine enhancements: EMAIL channel, ON_INACTIVITY trigger, expanded precondition operators
- Mobile BFF V2: AI insight, GPS check-in, dashboard, sync, settings, activity feed, unified search
- iOS client V2: DSL condition engine, AI streaming, chat attachments, biometric auth, file upload

### Changed
- Agent runtime hardened with trace spans, concurrency control, approval resume, and anomaly detection

## [0.8.0] - 2026-03-10

### Added
- CRM system with 13 models: accounts, contacts, leads, opportunities, quotes, campaigns, activities, complaints
- Quick Actions: 12 context commands with URL default value (`dv.*`) pre-fill mechanism
- AI Lead Scoring: LLM batch evaluation across 5 dimensions (100-point scale)
- CRM Dashboard with 7 blocks (stat cards, pipeline chart, tables) backed by NamedQueries
- Batch operations toolbar: bulk edit, delete, and export with dynamic field selection
- Global search command palette (Cmd+K) with menu, record, and history search
- Plugin layered architecture: L1 generic, L2 industry, platform, and solution layers (27 plugins total)

### Changed
- Plugin split with config completeness checks for dict/field/menu cross-references

## [0.7.0] - 2026-03-07

### Added
- AuraBot AI Copilot panel: context-aware right-side panel with SSE streaming and tool calling
- Dynamic LLM provider configuration supporting 8 providers (OpenAI, Anthropic, MiniMax, etc.)
- Sales forecast dashboard with AI natural language query
- Field history audit trail UI with AI next-best-action suggestions
- Prompt template management with Handlebars-like engine and seed templates

### Fixed
- Project management interaction and stability improvements
- Auth page i18n issues resolved

## [0.6.0] - 2026-03-04

### Added
- Quarry Industry Solution: 43 models across 5 phases (production, transport, sales, equipment, safety)
- Dual Prevention plugin for safety risk management
- Construction Process tracking module
- Contract Cost management with full lifecycle (7 models)

### Fixed
- E2E test suite stabilized to 100% pass rate
- Named thread pool for @Async event listeners to prevent thread exhaustion
- Gradle heap size tuned for large plugin imports

## [0.5.0] - 2026-03-02

### Added
- Plugin Productization: DSL versioning, Manifest v2 schema, 3-layer validation pipeline (schema, semantic, cross-reference)
- Plugin CLI tool for scaffolding, validation, and import automation
- Multi-Channel Authentication: social login with 5 tables, account linking/unlinking, deactivation with scheduler
- Platform Admin DSL: 6 models binding core `ab_*` tables with soft-delete support
- Org Management plugin: department, position, and employee models with platform team structure

### Changed
- Plugin manifest upgraded from v1 to v2 with richer metadata and dependency declarations

## [0.4.0] - 2026-02-28

### Added
- Enterprise Suite PCBA-ERP: ~126 models covering BOM, production, procurement, inventory, quality, and finance
- Project Management (PM-PMO): 10 models with Kanban, Gantt, milestone tracking, and workspace frontend
- JSONB virtual fields (`extension.jsonbColumn` + `jsonbPath`) for first-class DSL field support
- BPMN workflow templates for common approval processes
- Developer verification codes and structured error code system
- BOM tree editor and organization chart components

### Changed
- Test infrastructure expanded with dedicated E2E test fixtures and accounts

## [0.3.0] - 2026-02-20

### Added
- Core DSL engine: Model, Field, Command, and Page schema definitions
- Page Designer with drag-and-drop layout editing and block-level composition
- Command Pipeline: 20-stage execution engine with validation, authorization, state transitions, and side effects
- NamedQuery subsystem for reusable parameterized queries with governance
- AuraEvent system with `publishAfterCommit` for transactional event handling
- i18n 3-layer resolution architecture: model, field, and action label derivation
- Dynamic table architecture for tenant-isolated business data (`mt_*`)
- SavedView system with kanban, gantt, tree, and list view types

## [0.2.0] - 2026-02-10

### Added
- Multi-tenant architecture with automatic `tenant_id` isolation via TenantLineInterceptor
- RBAC permission system with role-based access control and menu management
- JWT authentication with `sv` (security_version) claim for token invalidation
- User management with profile, password, and security settings
- Data permission system with fail-secure defaults

## [0.1.0] - 2026-02-01

### Added
- Initial project scaffold: Spring Boot 3.5 + Java 21 + MyBatis Plus + PostgreSQL
- React + TypeScript + Tailwind CSS + Vite frontend with Express BFF proxy layer
- Gradle multi-module build system
- Development environment with hot reload, API proxy, and reset scripts

[Unreleased]: https://github.com/AuraBoot/AuraBoot/compare/v1.0.0-beta.1...HEAD
[1.0.0-beta.1]: https://github.com/AuraBoot/AuraBoot/compare/v0.9.0...v1.0.0-beta.1
[0.9.0]: https://github.com/AuraBoot/AuraBoot/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/AuraBoot/AuraBoot/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/AuraBoot/AuraBoot/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/AuraBoot/AuraBoot/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/AuraBoot/AuraBoot/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/AuraBoot/AuraBoot/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/AuraBoot/AuraBoot/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/AuraBoot/AuraBoot/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/AuraBoot/AuraBoot/releases/tag/v0.1.0
