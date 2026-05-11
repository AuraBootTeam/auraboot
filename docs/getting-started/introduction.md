# Introduction to AuraBoot

AuraBoot is a source-available, self-hosted platform for building business applications using declarative JSON instead of rewriting the same CRUD, permission, workflow, and audit plumbing for every project. You define your data models, pages, commands, and workflows in JSON DSL -- the platform generates the database schema, REST APIs, and UI automatically. AI-assisted features such as AuraBot, ChatBI, agents, and RAG run on the same application model when they are useful.

## Key Value Propositions

- **Less boilerplate** -- Define a model in JSON, get a database table, CRUD APIs, list/form/detail pages, and permission checks automatically. No code generation step.
- **20+ stage command pipeline** -- Every data operation flows through a configurable pipeline: validation, permission check, state machine, field mapping, handler, side effects, webhooks, audit. You control each stage through DSL.
- **3 visual designers** -- Page Designer (drag-and-drop pages with 20+ block types), BPMN Designer (workflow editor with human tasks and SLA), Automation Designer (event-driven rules with triggers and actions).
- **AI-assisted workflows** -- In-app assistant (AuraBot), agent orchestration (ACP), ChatBI for natural language analytics, and RAG knowledge base. Works with OpenAI, Anthropic, Zhipu GLM, and more.
- **Plugin system** -- PF4J-based architecture. Plugins are declarative JSON packages that add models, fields, commands, pages, and menus. Build your own with the CLI or install from the marketplace.
- **Multi-tenant RBAC** -- Row-level tenant isolation, role-based access control at resource/operation/data levels, menu and API-level permission enforcement.
- **Self-hosted stack** -- Spring Boot 3.5, React 19, PostgreSQL 16 with pgvector, Redis 7, Docker Compose deployment.

## Who Should Use AuraBoot

### Startup CTO / Technical Co-Founder

You need internal tools, a CRM, project management, or an ERP -- fast. AuraBoot lets you define business logic in JSON DSL and ship a working application in days instead of months. When requirements change, you modify the DSL configuration instead of rewriting code.

### Enterprise IT Team

Your organization needs custom business applications but lacks the engineering bandwidth to build everything from scratch. AuraBoot provides the foundation -- authentication, permissions, audit trails, workflows, multi-tenancy -- while you focus on business logic through plugins.

### Solo Developer / Freelancer

You want to build client-facing business applications without wiring up boilerplate CRUD, permissions, and form validation for every project. AuraBoot handles the infrastructure; you configure the business domain.

## What You Can Build

AuraBoot is designed for data-driven business applications. Here are examples of what you can build with the DSL + plugin system:

- **CRM** -- Accounts, contacts, leads, opportunities, sales pipeline (see the included [CRM Starter](../../plugins/crm-starter/) plugin)
- **Project Management** -- Projects, tasks, sprints, kanban boards, time tracking
- **ERP / Inventory** -- Products, warehouses, stock movements, purchase orders, invoices
- **HR Management** -- Employees, leave requests, attendance, performance reviews
- **Procurement** -- Vendors, purchase requisitions, approvals, contracts
- **Help Desk / Ticketing** -- Tickets, SLA tracking, knowledge base, customer portal
- **Asset Management** -- IT assets, maintenance schedules, depreciation tracking
- **Sales Management** -- Quotes, orders, commissions, territory management
- **Compliance / Audit** -- Checklists, inspections, corrective actions, audit trails
- **Approval Workflows** -- Multi-level approval chains with BPM integration
- **Customer Portal** -- Self-service dashboards backed by the same data models
- **Reporting / BI** -- Custom dashboards with stat cards, charts, and ChatBI natural language queries

## How AuraBoot Compares

| Capability | AuraBoot | Odoo | NocoDB | Appsmith | Retool |
|---|---|---|---|---|---|
| **Approach** | DSL-driven platform | Monolithic ERP | Spreadsheet-to-API | UI builder for APIs | UI builder for APIs |
| **Data model definition** | JSON DSL (auto-generates DB + API + UI) | Python ORM models | GUI on existing DB | External DB/API | External DB/API |
| **Command pipeline** | 20+ stage configurable pipeline | ORM hooks | N/A | N/A | N/A |
| **State machine** | Built-in (DSL-configured) | Custom code | N/A | Custom code | Custom code |
| **Page builder** | 3 designers (Page, BPMN, Automation) | Studio (limited) | Grid views | Drag-drop widgets | Drag-drop widgets |
| **AI integration** | Optional app-aware features (copilot, agents, ChatBI, RAG) | Third-party | N/A | Third-party | Third-party |
| **BPM / Workflows** | BPMN 2.0 engine (SmartEngine) | Basic workflows | N/A | N/A | N/A |
| **Plugin system** | PF4J + JSON packages | Python modules | N/A | N/A | N/A |
| **Multi-tenancy** | Row-level isolation, built-in | Per-database | N/A | N/A | N/A |
| **Self-hosted** | Yes (Docker) | Yes | Yes | Yes | Yes (paid) |
| **License** | Source-available (AuraBoot License) | LGPL (Community) | AGPL | Apache 2.0 | Proprietary |
| **Best for** | Custom business apps with complex workflows | Full ERP suite | Quick DB frontend | Internal tool dashboards | Internal tool dashboards |

## License Model

AuraBoot uses a **source-available community edition + commercial enterprise edition** model:

- **Community Edition (Free)** -- DSL engine, page designer, command pipeline, AI copilot, BPM workflows, plugin system, multi-tenant RBAC. Source-available under the [AuraBoot License v1.3](../../LICENSE.txt) (based on Apache 2.0 with supplementary terms).
- **Enterprise Edition (Paid)** -- Agent orchestration (ACP), real-time messaging (IM), CRM/ERP plugin suite, mobile apps (iOS + Android), priority support with SLA.

The community edition is free for internal use. You can use, modify, and deploy it for your own business applications. Attribution is required ("Powered by AuraBoot"). Offering AuraBoot as a hosted platform service requires a commercial license.

## Next Step

Ready to try it? Follow the [Quick Start](quick-start.md) to get AuraBoot running in 5 minutes.
