# Technology Stack

AuraBoot's technology choices prioritize stability, developer productivity, and long-term maintainability. Every component is chosen for a specific reason.

## Overview

| Layer | Technology | Version | Role |
|-------|------------|---------|------|
| **Language** | Java | 21 (LTS) | Backend application code |
| **Language** | TypeScript | 5.x | Frontend application code |
| **Backend Framework** | Spring Boot | 3.5 | HTTP server, DI, security, actuator |
| **ORM** | MyBatis-Plus | 3.5 | SQL mapping, tenant interceptor, pagination |
| **Plugin Framework** | PF4J | 3.x | Plugin loading, isolation, lifecycle |
| **BPM Engine** | SmartEngine | 3.7 | BPMN 2.0 workflow execution |
| **Frontend Framework** | React | 19 | UI rendering, component model |
| **Routing** | React Router | 7 | File-based routing, SSR support |
| **Styling** | Tailwind CSS | 4 | Utility-first CSS |
| **Build Tool** | Vite | 6 | Frontend bundling, HMR, dev server |
| **Database** | PostgreSQL | 16 | Primary data store |
| **Vector Search** | pgvector | -- | AI embedding storage and similarity search |
| **Cache** | Redis | 7 | Distributed locks, event bus, session cache |
| **AI** | Multi-LLM | -- | OpenAI, Anthropic, Zhipu, MiniMax |
| **Testing (Backend)** | JUnit 5 + JaCoCo | -- | Integration tests, coverage reporting |
| **Testing (Frontend)** | Playwright | -- | End-to-end browser tests |
| **Observability** | OpenTelemetry + Sentry | -- | Distributed tracing, error tracking |
| **Deployment** | Docker + Docker Compose | -- | Containerized deployment |

---

## Backend

### Java 21

**Why Java 21:** LTS release with virtual threads, pattern matching, record types, and sealed classes. Virtual threads (Project Loom) enable high-concurrency without reactive complexity. The ecosystem maturity means battle-tested libraries for everything from PDF generation to BPMN execution.

### Spring Boot 3.5

**Why Spring Boot:** The de facto standard for Java web applications. Provides dependency injection, security (Spring Security), actuator (health checks, metrics), and a massive ecosystem. Version 3.5 runs on Jakarta EE 10 and supports GraalVM native images.

Key Spring features used:

- **Spring Security** -- JWT authentication, RBAC, CORS
- **Spring Actuator** -- Health checks, Prometheus metrics, info endpoint
- **Spring Transaction** -- Declarative `@Transactional` with PostgreSQL
- **Spring Validation** -- Request body validation via Jakarta Bean Validation

### MyBatis-Plus

**Why MyBatis-Plus over JPA/Hibernate:** AuraBoot's dynamic model system creates tables at runtime -- the schema is not known at compile time. MyBatis-Plus provides:

- Raw SQL control for dynamic table names (`mt_*`)
- `TenantLineInterceptor` for automatic multi-tenant row filtering
- Pagination plugin with total count
- Soft delete (`deleted_flag`) interceptor
- Lambda query wrappers for type-safe static queries

JPA's entity-centric model does not work well with runtime-generated schemas.

### PF4J

**Why PF4J:** Lightweight plugin framework with classloader isolation. Plugins are loaded as JAR files with defined extension points. AuraBoot uses PF4J primarily for the plugin lifecycle, but most plugins are declarative JSON packages rather than Java code.

### SmartEngine

**Why SmartEngine:** Lightweight BPMN 2.0 engine designed for embedding in Spring Boot applications. Supports human tasks, service tasks, exclusive/parallel gateways, and event-based sub-processes. Lighter than Camunda/Flowable for AuraBoot's approval-centric workflow needs. The BPM engine is abstracted behind an SPI, allowing swap to Camunda or Flowable if needed.

---

## Frontend

### React 19

**Why React 19:** Dominant ecosystem, strong TypeScript support, and the component model maps well to AuraBoot's block-based page rendering. React 19 brings improved server components and concurrent rendering. The large ecosystem means proven solutions for forms, tables, charts, and drag-and-drop.

### React Router 7

**Why React Router 7:** File-based routing with built-in SSR support. Replaces the need for a separate SSR framework. The loader/action pattern cleanly separates data fetching from rendering. The BFF (Backend-For-Frontend) pattern proxies API calls and handles server-side rendering in the same Express process.

### Tailwind CSS 4

**Why Tailwind CSS:** Utility-first CSS eliminates naming debates and dead CSS. Version 4 uses the new Lightning CSS engine for faster builds. Combined with a design token system, it provides consistent spacing, colors, and typography across all components.

### Vite 6

**Why Vite:** Sub-second HMR (Hot Module Replacement) during development. ESBuild-powered dev server starts instantly regardless of project size. Rollup-based production builds with code splitting and tree shaking.

---

## Database

### PostgreSQL 16

**Why PostgreSQL:** The most capable open-source relational database. Features critical to AuraBoot:

- **JSONB** -- Stores DSL page schemas, extension data, and command payloads as queryable JSON
- **pgvector** -- Vector similarity search for AI embeddings (RAG knowledge base)
- **pg_trgm** -- Trigram indexes for fast fuzzy text search
- **pgcrypto** -- Cryptographic functions for token generation
- **Row-level security** -- Foundation for multi-tenant isolation
- **Partial indexes** -- `WHERE deleted_flag = FALSE` for soft-delete performance
- **LISTEN/NOTIFY** -- Real-time event propagation (used by event bus in local mode)

### Why Not MySQL

PostgreSQL's JSONB support, pgvector extension, partial indexes, and transactional DDL are all critical for AuraBoot's dynamic schema management. MySQL lacks native equivalents for several of these features.

---

## Cache / Infrastructure

### Redis 7

**Why Redis:** Industry standard for distributed caching and pub/sub. AuraBoot uses Redis for:

- **Distributed locks** -- Prevents concurrent command execution on the same record across instances
- **Event bus transport** -- Cross-instance event propagation via pub/sub
- **Session cache** -- JWT blacklist for revoked tokens
- **Message queue** -- Background job coordination (webhook delivery, async commands)
- **Real-time sync** -- SSE (Server-Sent Events) broadcast across instances

Redis is **optional** for single-instance deployments. Without Redis, AuraBoot uses JVM-local equivalents.

---

## AI

### Multi-LLM Abstraction

AuraBoot's AI core uses a provider abstraction layer that normalizes different LLM APIs into a unified interface. Supported providers:

| Provider | Models | Use Case |
|----------|--------|----------|
| Anthropic | Claude 3.5/4 | Primary reasoning, AuraBot, ACP agents |
| OpenAI | GPT-4o, GPT-4 | Alternative reasoning, embeddings |
| Zhipu | GLM-4 | Chinese language support |
| MiniMax | abab | Additional provider option |

The provider is selected per-request based on configuration, allowing different models for different features (e.g., Claude for complex reasoning, OpenAI for embeddings).

### pgvector

Vector embeddings for the RAG knowledge base are stored in PostgreSQL using pgvector. This avoids the operational complexity of a separate vector database while providing good performance for most use cases (up to millions of embeddings with IVFFlat indexes).

---

## Testing

### JUnit 5 + JaCoCo

Backend integration tests run against real PostgreSQL and Redis (no H2, no mocks for infrastructure). JaCoCo enforces 80% line coverage threshold.

### Playwright

End-to-end tests run in real browsers against the full stack. Tests cover menu navigation, CRUD operations, state transitions, and data correctness. The E2E test suite replaces manual QA for all DSL-driven pages.

---

## Observability

### OpenTelemetry

Distributed tracing with W3C trace context propagation. Traces span from the BFF through Spring Boot to PostgreSQL. Configurable sampling rate (`1.0` in development, `0.1` in production).

### Sentry

Client-side and server-side error tracking. JavaScript errors in the browser, unhandled exceptions in Spring Boot, and crash reports from mobile apps all flow to Sentry.

### Structured Logging

JSON-format logs with consistent fields: `timestamp`, `level`, `logger`, `message`, `trace_id`, `tenant_id`. Integrates with ELK, Loki, or any log aggregation system.

### Prometheus + Grafana

Backend exposes metrics at `/actuator/prometheus`. Pre-built Grafana dashboards cover:

- HTTP request rate and latency
- Database connection pool usage
- JVM memory and GC
- Command pipeline execution times
- Slow query counts

---

## Why These Choices

| Decision | Alternative Considered | Rationale |
|----------|----------------------|-----------|
| Java over Go/Rust | Performance vs ecosystem | Java's ecosystem (Spring, MyBatis, PF4J, SmartEngine) provides more business-domain libraries than Go/Rust. Virtual threads close the performance gap. |
| MyBatis over JPA | Type-safety vs flexibility | Dynamic table names and runtime schemas make JPA's entity model impractical |
| PostgreSQL over MySQL | Feature set | JSONB, pgvector, partial indexes, transactional DDL |
| React over Vue/Svelte | Ecosystem size | Larger component ecosystem, better TypeScript support, more hiring pool |
| Tailwind over CSS Modules | Productivity | Faster iteration, no naming debates, consistent design tokens |
| Monolith over microservices | Simplicity | A modular monolith with plugin isolation provides the benefits of separation without distributed system complexity |
