# AuraBoot Open-Source Documentation & Use Cases — Design Spec

> **Date**: 2026-04-11
> **Status**: Draft
> **Language**: English (primary), Chinese (future)
> **Location**: `auraboot/docs/`

---

## Context

AuraBoot is a source-available, self-hosted low-code platform for business apps. The open-source repository currently has:
- A solid README.md with feature overview and quick start
- CONTRIBUTING.md, SECURITY.md, CODE_OF_CONDUCT.md, DEPLOY.md
- **No `/docs/` directory** — all documentation links in README point to enterprise docs that don't exist in the open-source repo
- The website (auraboot-website) has ~15 doc pages and 11 blog posts, but these are marketing-oriented and not deep enough for developers

**Goal**: Create a comprehensive English documentation suite in `auraboot/docs/` that serves both decision-makers (CTO/tech leads evaluating the platform) and developers (building apps with AuraBoot). Each industry use case gets a full-depth walkthrough with complete DSL configuration examples.

---

## Documentation Structure

```
auraboot/docs/
│
├── README.md                              # Docs index & navigation
│
├── getting-started/
│   ├── introduction.md                    # What is AuraBoot, who it's for
│   ├── quick-start.md                     # Docker Compose → first app in 5 min
│   ├── installation.md                    # Detailed install (source, Docker, cloud)
│   └── first-app.md                       # Build your first CRUD app (30 min tutorial)
│
├── core-concepts/
│   ├── dsl-engine.md                      # DSL philosophy & how it works
│   ├── models-and-fields.md               # 22 field types, relations, formulas
│   ├── commands.md                        # 20+ stage pipeline, CRUD + custom
│   ├── pages-and-layouts.md               # Page kinds, blocks, designers
│   ├── permissions.md                     # RBAC, multi-tenant, data-level
│   └── state-machines.md                  # Status flows, guards, transitions
│
├── guides/
│   ├── page-designer.md                   # Drag-drop page building
│   ├── bpm-workflows.md                   # BPMN 2.0 approval workflows
│   ├── automation-rules.md                # Event-driven automation
│   ├── ai-copilot.md                      # AuraBot, ChatBI, RAG
│   ├── dashboards.md                      # Charts, stat cards, KPI boards
│   ├── data-import-export.md              # Bulk data operations
│   ├── formulas-and-expressions.md        # Computed fields, rollups, aggregates
│   ├── notifications.md                   # Email, in-app, webhooks
│   ├── multi-tenancy.md                   # Tenant setup, isolation, switching
│   └── cli-reference.md                   # aura CLI tool commands
│
├── use-cases/
│   ├── README.md                          # Use case index & capability matrix
│   ├── crm.md                             # Customer Relationship Management
│   ├── sales.md                           # Sales pipeline & quoting
│   ├── project-management.md              # Projects, tasks, resources, Gantt
│   ├── procurement.md                     # Purchase orders, suppliers, S2P
│   ├── manufacturing.md                   # Production planning, BOM, MRP
│   ├── warehouse.md                       # Inventory, WMS, stock management
│   ├── logistics.md                       # Shipping, tracking, delivery
│   ├── finance.md                         # Invoicing, AP/AR, tax compliance
│   ├── quality-management.md              # QA, inspections, NCR
│   ├── compliance.md                      # Regulatory compliance, audits
│   ├── asset-management.md                # Equipment, maintenance, lifecycle
│   ├── hr-leave-management.md             # Leave requests, approvals
│   ├── knowledge-base.md                  # Doc management, RAG search
│   ├── pcba-industry-solution.md          # Full PCBA manufacturing suite
│   └── ai-agent-platform.md              # ACP, AI employees, automation
│
├── plugin-development/
│   ├── overview.md                        # Plugin architecture & lifecycle
│   ├── config-only-plugin.md              # JSON-only plugin (no code)
│   ├── backend-plugin.md                  # PF4J Java extensions
│   ├── frontend-plugin.md                 # Module Federation components
│   ├── full-stack-plugin.md               # Complete plugin walkthrough
│   └── plugin-manifest-reference.md       # plugin.json schema reference
│
├── api-reference/
│   ├── rest-api.md                        # Dynamic CRUD APIs, filters, pagination
│   ├── command-api.md                     # Command execution API
│   ├── datasource-api.md                  # Named queries & data sources
│   └── webhook-api.md                     # Event webhooks
│
├── deployment/
│   ├── docker.md                          # Docker Compose production setup
│   ├── kubernetes.md                      # K8s Helm chart deployment
│   ├── configuration.md                   # Environment vars, application.yml
│   └── upgrading.md                       # Version upgrade guide
│
└── architecture/
    ├── overview.md                        # System architecture diagram
    ├── tech-stack.md                      # Technology choices & rationale
    └── data-model.md                      # Core database schema
```

**Total: ~45 documents**

---

## Section Details

### 1. Getting Started (4 docs)

**Audience**: New users, both decision-makers and developers.

#### introduction.md
- What is AuraBoot (one paragraph)
- Key value propositions (5 bullets)
- Who should use it (personas: startup CTO, enterprise IT, solo developer)
- What you can build (screenshot gallery of example apps)
- How it compares to alternatives (brief positioning vs Odoo, NocoDB, Appsmith, Retool)
- License & pricing model

#### quick-start.md
- Prerequisites: Docker, Git
- 3-step setup: clone → docker compose up → open browser
- First login (admin@auraboot.com / default password)
- Explore the demo workspace (pre-loaded CRM data)
- ~5 minutes end-to-end

#### installation.md
- Docker Compose (recommended for production)
- Build from source (Java 21, Node 20, PostgreSQL 16, Redis 7)
- Environment variables reference table
- Database initialization
- Health check verification

#### first-app.md (30-minute tutorial)
- Goal: Build a "Task Tracker" app from scratch
- Step 1: Create a model (`task`) with 8 fields
- Step 2: Define commands (create, update, delete, complete)
- Step 3: Add a state machine (open → in_progress → done → archived)
- Step 4: Design a list page with filters and toolbar
- Step 5: Design a form page with field groups
- Step 6: Create a dashboard with stat cards
- Step 7: Set up permissions
- Step 8: Test the complete flow
- Every step includes JSON config + CLI commands + expected result

### 2. Core Concepts (6 docs)

**Audience**: Developers who want to understand the platform deeply.

Each document follows the pattern:
1. **What it is** — conceptual explanation
2. **How it works** — architecture / flow diagram
3. **Configuration reference** — complete JSON schema with examples
4. **Best practices** — patterns and anti-patterns

#### dsl-engine.md
- Philosophy: "Define, don't code"
- DSL resource types: models, fields, commands, pages, dicts, formulas, bindings
- Resolution order: plugin JSON → database → runtime cache
- Namespace conventions
- Version and lifecycle management

#### models-and-fields.md
- Model definition schema
- All 22 field types with examples:
  - Basic: STRING, TEXT, INTEGER, DECIMAL, BOOLEAN
  - Temporal: DATE, DATETIME
  - Choice: ENUM, TAGS
  - Rich: RICHTEXT, ATTACHMENTS, COLOR, URL, EMAIL, PHONE
  - Numeric display: PROGRESS, RATING
  - Relations: REFERENCE (FK), MANY_TO_MANY
  - Computed: FORMULA, ROLLUP
- Field extensions (renderComponent, validation, defaultValue)
- Indexing and performance considerations

#### commands.md
- 20+ stage pipeline diagram
- Built-in command types: CREATE, UPDATE, DELETE, STATUS_TRANSITION
- Custom command handlers (Java extension point)
- Command bindings: model → commands mapping
- Side effects: webhooks, notifications, automation triggers
- Transaction boundaries and error handling

#### pages-and-layouts.md
- Page kinds: list, form, detail, dashboard
- Block types: table, filters, toolbar, form-section, chart, tabs, sub-table, stat-card, custom
- Layout system: stack vs grid
- Profile system: admin vs report
- Page Designer: drag-drop block composition
- Responsive behavior

#### permissions.md
- Three-layer model: resource → operation → data
- Role definitions and role-permission binding
- Multi-tenant isolation (row-level)
- Menu visibility and route guards
- Permission inheritance and wildcards
- Bootstrap template (default-bootstrap.json)

#### state-machines.md
- State definition and transitions
- Guard conditions (field-based, role-based)
- Side effects on transition (commands, notifications)
- Status display (colors, badges, filters)
- Complete example: Order lifecycle (draft → confirmed → shipped → delivered → closed)

### 3. Guides (10 docs)

**Audience**: Developers implementing specific features.

Each guide is a focused how-to:
1. **Goal** — what you'll achieve
2. **Prerequisites** — what you need first
3. **Step-by-step** — numbered instructions with JSON/CLI
4. **Complete example** — working config you can copy
5. **Troubleshooting** — common issues and fixes

Topics:
- **page-designer.md**: Visual page builder walkthrough
- **bpm-workflows.md**: BPMN process design, human tasks, SLA
- **automation-rules.md**: Event triggers, conditions, actions
- **ai-copilot.md**: AuraBot setup, ChatBI queries, RAG knowledge base
- **dashboards.md**: Chart types, data sources, stat cards, KPI tracking
- **data-import-export.md**: CSV/Excel import, bulk create, export
- **formulas-and-expressions.md**: Formula syntax, rollup aggregates, cross-model references
- **notifications.md**: Email templates, in-app notifications, webhook dispatch
- **multi-tenancy.md**: Tenant provisioning, data isolation, cross-tenant admin
- **cli-reference.md**: All `aura` CLI commands with examples

### 4. Use Cases (15 docs) — Full Depth

**Audience**: Both decision-makers and developers.

**Every use case follows the same deep template** (described below). Content is derived from the actual enterprise plugin configurations — real DSL, not fabricated examples.

#### Use Case Template (applies to all 15)

```markdown
# {Use Case Name}

> {One-line value proposition}

## Business Overview
- Problem statement (what pain point does this solve?)
- Target users (roles, team size, industry)
- Key capabilities (10-15 bullet points)
- Typical workflow diagram (Mermaid flowchart)

## Data Model

### Entity Relationship Diagram
{Mermaid ER diagram showing all models and their relationships}

### Models Reference
| Model | Purpose | Key Fields | Relations |
|-------|---------|-----------|-----------|
| ... | ... | ... | ... |

### Complete Model Configuration
{Full JSON for every model definition — models.json}

## Fields Deep Dive
- Field configuration for each model
- Enum/dictionary definitions
- Validation rules
- Computed fields and formulas

### Complete Field Configuration
{Full JSON for fields — fields/*.json}

## Commands & Business Logic

### Command Map
| Command | Type | Model | Description |
|---------|------|-------|-------------|
| create_xxx | CREATE | xxx | ... |
| update_xxx | UPDATE | xxx | ... |
| transition_xxx | STATUS_TRANSITION | xxx | ... |

### State Machine
{Mermaid state diagram}

### Transition Table
| From | To | Command | Guard | Side Effects |
|------|----|---------|-------|-------------|

### Complete Command Configuration
{Full JSON — commands/*.json + bindings/*.json}

## Pages & User Interface

### Page Inventory
| Page Key | Kind | Purpose |
|----------|------|---------|
| xxx_list | list | Main listing with filters |
| xxx_form | form | Create/edit form |
| xxx_detail | detail | Record detail with tabs |
| xxx_dashboard | dashboard | KPI overview |

### List Page Configuration
{JSON + explanation of table columns, filters, toolbar actions}

### Form Page Configuration
{JSON + explanation of field groups, conditional visibility}

### Detail Page Configuration
{JSON + explanation of tabs, sub-tables, related data}

### Dashboard Configuration
{JSON + explanation of charts, stat cards, data sources}

## Permissions & Roles

### Role Definitions
| Role | Permissions | Description |
|------|-----------|-------------|
| ... | ... | ... |

### Menu Structure
{JSON — menus.json}

## Internationalization
{i18n key patterns and sample translations}

## Workflows (if applicable)
- BPM process definitions
- Approval routing rules
- Escalation policies

## Automation Rules (if applicable)
- Event triggers
- Automated actions
- Notification templates

## Getting Started
1. Install the plugin: `aura plugin publish plugins/{name} --yes`
2. Verify models: `aura dsl show {model_code}`
3. Verify menus: Navigate sidebar → {menu path}
4. Seed sample data: `aura exec {ns}:create_{model} --set field1="value1"`
5. Explore the UI: List → Create → Edit → Status transitions → Dashboard

## Extension Points
- How to add custom fields
- How to add new commands
- How to customize pages
- How to add automation rules
- How to integrate with external systems

## FAQ
- Common questions and answers specific to this use case
```

#### 15 Use Cases Content Source Mapping

Each use case draws directly from the enterprise plugin configurations:

| Use Case | Source Plugin(s) | Key Models |
|----------|-----------------|------------|
| CRM | `crm` | crm_lead, crm_opportunity, crm_account, crm_contact, crm_activity |
| Sales | `sales` | sal_quote, sal_order, sal_template, sal_pipeline |
| Project Management | `project-mgmt` | pm_project, pm_task, pm_milestone, pm_resource, pm_timesheet |
| Procurement | `procurement`, `indirect-procurement`, `source-to-pay` | proc_request, proc_order, proc_supplier, proc_contract |
| Manufacturing | `manufacturing`, `pcba-manufacturing` | mfg_order, mfg_bom, mfg_routing, mfg_workstation |
| Warehouse | `warehouse` | wh_location, wh_inventory, wh_receipt, wh_shipment, wh_count |
| Logistics | `logistics` | log_shipment, log_carrier, log_tracking |
| Finance | `finance`, `tax-compliance` | fin_invoice, fin_payment, fin_account, tax_filing |
| Quality Management | `quality` | qa_inspection, qa_ncr, qa_capa, qa_checklist |
| Compliance | `compliance`, `dual-prevention` | comp_audit, comp_finding, comp_action, dp_hazard, dp_risk |
| Asset Management | `asset-mgmt` | asset_equipment, asset_maintenance, asset_location |
| HR Leave Management | `showcase` (thr_leave_request) | thr_employee, thr_leave_request, thr_leave_type |
| Knowledge Base | `doc-knowledge` | dk_document, dk_category, dk_knowledge_base |
| PCBA Industry Solution | `pcba-*` (10 plugins) | Cross-module: CRM→Sales→Procurement→Manufacturing→QA→Shipping |
| AI Agent Platform | `acp`, `ai-employees` | acp_agent, acp_tool, acp_workflow, ai_employee |

### 5. Plugin Development (6 docs)

**Audience**: Developers extending AuraBoot.

- **overview.md**: PF4J architecture, plugin lifecycle, config-only vs full-stack
- **config-only-plugin.md**: Create a complete plugin with only JSON (no Java/TS). Uses the "Task Tracker" from first-app.md as the running example. Covers plugin.json, models, fields, commands, pages, menus, permissions, i18n, dicts.
- **backend-plugin.md**: Java extension points — custom CommandHandler, custom DataSource, custom API endpoint. Build with Gradle, package as JAR.
- **frontend-plugin.md**: Module Federation — custom React components, custom block renderers, custom widgets. Build with Vite.
- **full-stack-plugin.md**: End-to-end walkthrough combining config + backend + frontend.
- **plugin-manifest-reference.md**: Complete plugin.json schema with every field documented.

### 6. API Reference (4 docs)

**Audience**: Developers integrating with AuraBoot.

- REST API: Authentication, CRUD endpoints, filter syntax, pagination, sorting
- Command API: Execute commands, batch operations, async execution
- DataSource API: Named queries, parameterized data sources
- Webhook API: Event types, payload format, retry policy

Each API doc includes: endpoint URL, method, request/response JSON, curl examples, error codes.

### 7. Deployment (4 docs)

**Audience**: DevOps and platform administrators.

- Docker Compose production setup with SSL, backup, monitoring
- Kubernetes Helm chart with scaling, ingress, secrets
- Configuration reference: all environment variables and application.yml keys
- Upgrade guide: version migration steps, breaking changes

### 8. Architecture (3 docs)

**Audience**: Technical decision-makers and senior developers.

- System architecture overview with layered diagram
- Technology stack choices and rationale
- Core data model: key tables, relationships, multi-tenant design

---

## Writing Standards

### Language & Tone
- **English**: Clear, concise, technical but accessible
- **Active voice**: "AuraBoot creates the table" not "The table is created by AuraBoot"
- **Second person**: "You can configure..." not "Users can configure..."
- **No marketing fluff**: Facts and examples, not superlatives

### Code Examples
- All JSON examples must be **valid, complete, and copy-pasteable**
- Include `aura` CLI commands alongside JSON for every operation
- Use consistent naming: `{namespace}_{entity}` for models, `{ns}:{action}_{entity}` for commands
- Every JSON block has a brief comment explaining non-obvious fields

### Diagrams
- Use **Mermaid** for all diagrams (ER, state, flowchart, sequence)
- Diagrams render in GitHub markdown natively
- Keep diagrams focused — one concept per diagram, not everything-at-once

### Cross-references
- Link to related docs: "See [Commands](../core-concepts/commands.md) for pipeline details"
- Link to source code when referencing extension points
- Link to API reference from use cases

---

## Implementation Order

Given the volume (~45 docs, all use cases at full depth), the recommended writing order is:

### Phase 1: Foundation (8 docs)
1. `docs/README.md` — index
2. `getting-started/introduction.md`
3. `getting-started/quick-start.md`
4. `getting-started/installation.md`
5. `getting-started/first-app.md`
6. `core-concepts/dsl-engine.md`
7. `core-concepts/models-and-fields.md`
8. `core-concepts/commands.md`

### Phase 2: Core Concepts & Guides (12 docs)
9. `core-concepts/pages-and-layouts.md`
10. `core-concepts/permissions.md`
11. `core-concepts/state-machines.md`
12. `guides/page-designer.md`
13. `guides/bpm-workflows.md`
14. `guides/automation-rules.md`
15. `guides/ai-copilot.md`
16. `guides/dashboards.md`
17. `guides/formulas-and-expressions.md`
18. `guides/notifications.md`
19. `guides/multi-tenancy.md`
20. `guides/cli-reference.md`

### Phase 3: Use Cases — Flagship (3 docs)
21. `use-cases/README.md`
22. `use-cases/crm.md`
23. `use-cases/project-management.md`

### Phase 4: Use Cases — Business (6 docs)
24. `use-cases/sales.md`
25. `use-cases/procurement.md`
26. `use-cases/manufacturing.md`
27. `use-cases/warehouse.md`
28. `use-cases/finance.md`
29. `use-cases/hr-leave-management.md`

### Phase 5: Use Cases — Specialized (6 docs)
30. `use-cases/logistics.md`
31. `use-cases/quality-management.md`
32. `use-cases/compliance.md`
33. `use-cases/asset-management.md`
34. `use-cases/knowledge-base.md`
35. `use-cases/pcba-industry-solution.md`
36. `use-cases/ai-agent-platform.md`

### Phase 6: Plugin Development & API (10 docs)
37. `plugin-development/overview.md`
38. `plugin-development/config-only-plugin.md`
39. `plugin-development/backend-plugin.md`
40. `plugin-development/frontend-plugin.md`
41. `plugin-development/full-stack-plugin.md`
42. `plugin-development/plugin-manifest-reference.md`
43. `api-reference/rest-api.md`
44. `api-reference/command-api.md`
45. `api-reference/datasource-api.md`
46. `api-reference/webhook-api.md`

### Phase 7: Deployment & Architecture (7 docs)
47. `deployment/docker.md`
48. `deployment/kubernetes.md`
49. `deployment/configuration.md`
50. `deployment/upgrading.md`
51. `architecture/overview.md`
52. `architecture/tech-stack.md`
53. `architecture/data-model.md`

---

## Verification

After each phase:
1. All markdown files render correctly on GitHub (preview with `grip` or push to branch)
2. All Mermaid diagrams render correctly
3. All JSON examples are valid (parse with `jq`)
4. All internal links resolve (no broken cross-references)
5. All `aura` CLI commands referenced are valid
6. README.md index is updated with new docs

Final verification:
- A developer unfamiliar with AuraBoot can follow `quick-start.md` → `first-app.md` → any `use-case/*.md` and have a working app
- A decision-maker can read `introduction.md` → `use-cases/README.md` → any use case and understand the platform's capabilities
