# Project Management Template

Customer delivery planning and milestone tracking, built with shared DSL pages and commands.

## What's Included

| Model | Description |
|-------|-------------|
| Project | Initiatives with lifecycle (PLANNING → ACTIVE → COMPLETED) |
| Task | Work items with assignee and due date tracking |
| Milestone | Key deliverables with target dates |
| Customer | Tenant-scoped customer reference used by project plans |

## Key Features

- **Project Lifecycle**: PLANNING → ACTIVE ↔ ON_HOLD → COMPLETED / CANCELLED
- **Task Flow**: TODO → IN_PROGRESS → IN_REVIEW → DONE
- **Delivery workspace**: Inspect project agreements, linked milestones and persisted completion activity
- **Named Query**: Task summary by project for dashboards

## Installation

```bash
aura plugin publish plugins/project-management --target http://localhost:YOUR_BACKEND_PORT --yes
```

## Namespace

All resources use the `tpm` namespace prefix.

## Delivery Experience Blueprint

This template is the business entry point; Field Showcase remains a developer reference.
Sample customer names and projects are fictional and must be labeled as sample data.

| Page / action | User decision | DSL implementation | Runtime evidence |
| --- | --- | --- | --- |
| Project list | Which customer delivery needs attention? | Existing list, lifecycle views, customer, owner, due date | Tenant-scoped project records |
| Project form | What is the promised outcome and delivery date? | Business sections: project information / delivery plan / risks and next actions / optional attachments | Create and update command payloads |
| Project detail | What is complete and what comes next? | Object summary, business tabs, milestone collection, attachments and an audit sidebar | NamedQuery counts and real command audit |
| Complete milestone | Confirm the agreed deliverable is finished | Existing state_transition command and guarded row action | pending -> reached, aggregate and completion facts update immediately and after reload |

Project ownership uses a separate sys_user reference; the existing text owner field remains for compatibility and is excluded from the delivery form. Customer uses `tpm_pj_customer_id`, a real reference to `tpm_customer`; the legacy name field is retained outside the form. Owner-reported delivery progress is an independent 0–100 value, updated through a command. Milestone completion is a separate count and ratio. Attachments use a JSONB collection with real file identifiers; audit differences come from the platform field audit service. No placeholder files or fabricated audit activity are presented.

Form guidance: outcome and customer name use examples; dates use the native date selector; owner uses the member picker; budget explains CNY. Optional long outcome text spans the full row. Internal record identifiers remain hidden.

Primary actions: create project, edit plan, complete a pending milestone with confirmation. Completed milestones have no repeat-completion button. Lifecycle transitions remain server-validated. Loading/empty/error/read-only/permission and persistence are acceptance cases; conflict/stale protection is not invented by the template. Mobile and production deployment are outside this change.


### Local acceptance

Use the public OSS host-first launcher with `--plugin project-management` in an owned runtime. Reset only that runtime database when a new controlled fixture is needed. After import and identity verification, run `scripts/seed-delivery.py` with `DELIVERY_BACKEND_URL`, `DELIVERY_EVIDENCE_ROOT`, `ADMIN_EMAIL` and `ADMIN_PASSWORD`. The fixture tool accepts loopback only and refuses an already populated project table. It provisions four fictional team members and one read-only observer without sending email and creates eight fictional projects through commands.

Run the browser journey with `DELIVERY_WEB_URL` and the same evidence directory:

```bash
pnpm --dir web-admin exec playwright test -c playwright.delivery.config.ts
```

Copy `scripts/acceptance-matrix.json` into the evidence round before running the journey (P01-P23). The completion test expects the first controlled project to begin with two of four milestones reached. It retains the completed milestone and requires a fresh controlled fixture for another full run; it does not silently revert business state.

`detailAsideBlockIds` is an opt-in shared detail layout: the configured blocks move into a responsive aside, other blocks retain their DSL order. Child mutations reload declared page data sources and remount activity blocks so unchanged parent snapshots cannot leave derived progress or activities stale. Completion time is assigned by the command, not supplied by the browser. Historical milestones without a recorded completion timestamp are excluded from completion facts; they are not backfilled into fictitious history.

`formWidth: "comfortable"` opts a form into a narrower reading width; the default and existing sub-table layouts retain their current width. `activity-timeline.extension.density: "compact"` removes the CRM-specific header and filter controls for an object sidebar while retaining real activity entries and explicit load/permission failures. The delivery roles include datasource read permission and model read grants; the viewer receives no command execution or manage grant.


## Shared presentation contract

These business pages remain list/form/detail DSL. The platform components contain no project-specific page markup.

- `extension.presentation: "workspace"` selects the shared shell and page family. Undeclared pages retain their normal presentation.
- List columns declare `presentation.kind`: `object` (subtitle fields), `member`, `progress`, `deadline` (completion states), or `currency`. `tabs.showCounts` queries actual server totals. A tab filter value of `$currentUser` resolves to the authenticated public user identity.
- Form `extension.formGuidance` declares a title, description, steps and access note. Form sections use `extension.collapsible/defaultCollapsed`. The workspace action area stays fixed at the bottom. New projects start in requirements; lifecycle changes are performed by authorized commands instead of a cosmetic initial-stage picker.
- Detail `extension.objectSummary` declares ordered field presentations; `detailAsideBlockIds` and `detailAsideGroups` declare the side column. Tabs contain ordinary DSL blocks. `detail-section.extension.displayVariant: "object"` renders read-only business facts, including an optional `referenceDisplayField` from a referenced object. The declared reference model must be readable by the user.
- `subTable.presentation` selects `collection` or `focus`, with title, description, date, status and completion fields. Both reuse the existing scoped child query and command dispatcher. Toolbar buttons can opt into `overflow` without losing permissions, visibility rules or confirmation.
- Multi-file values use `dataType: "jsonb"` and the `fileattachment` renderer. The native `file` column is a scalar reference, not an attachment array. `linkFileAttachments: true` plus `attachmentFields` links uploaded public file IDs to the saved record through the standard file-relation API. Saving and relating are separate operations; relation failure is explicitly reported as a saved-record sharing failure.
- Field auditing is a platform configuration. `scripts/field-audit-config.json` is applied explicitly by the local initialization script through `/api/audit/field-config/bulk`; enabling audit is not an automatic application-start side effect. An existing tenant must configure the declared audit fields through the same authorized platform surface. History permissions are separate from project read permission and are not granted implicitly by the template.

The fixture uploads actual small PDF documents, preserves their real sizes and timestamps, and records an actual 60 → 68 progress command before visual review. Every sample project and customer is fictional. The browser journey then reports 72, completes a milestone, creates and edits another project, uploads and downloads a file, and verifies member and observer behavior. A fresh owned fixture is required for a full rerun.

### Optional AI customer intake

The separate **AI整理客户资料 / AI customer intake** menu opens `/p/c/tpm_customer_ai_intake`. Standard customer/project forms keep their existing behavior and do not receive a default AI banner.

| Page | Role and task | Primary action | Fields and guidance | States / evidence |
| --- | --- | --- | --- | --- |
| `tpm_customer_ai_intake` | A project manager turns pasted customer notes into a reviewed customer draft | Extract, review/edit, then Save customer | Customer name (required), contact (optional); business examples and help text; source and save guidance beside the form | Blank input, extracting, supported/conflicting/ambiguous evidence, undo, manual correction, provider error, save and reload; each real state requires a screenshot |

This page is configuration-only: `tpm_customer` → explicit `ai-fill-banner` without an endpoint → the existing `form_draft_fill` conversation skill → review/undo in the current draft → existing `tpm:create_customer` command. Extraction never substitutes for the user's Save action. The page does not grant permissions or add a provider configuration. Its menu requires the existing `tpm.project.manage` permission; backend command and model checks still apply.

The live browser scenario is `web-admin/tests/e2e/project-management/customer-ai-intake-live.spec.ts`. It requires `PW_REQUIRE_LIVE_LLM=1`, the exact `PW_EXPECT_LIVE_PROVIDER` and `PW_EXPECT_LIVE_MODEL`, and a genuinely configured live provider. The browser starts with empty storage and signs in through the real login form. Persisted trace metadata must match the expected provider and model. It contains no stub directives, network mocks or skips. Missing/failed providers fail the test; an unexecuted live test must be reported as `did_not_run`. The pre-existing `form-draft-fill.spec.ts` uses an explicit external-provider stub for plumbing and must not be used as live extraction-quality evidence.


## Reproducible Delivery Case

The explicit local seed creates eight fictional customers/projects with complete contacts, 24 assigned tasks, and 32 milestones. Each project has a distinct outcome, delivery risk and owned next action. Project detail shows linked tasks as well as milestones. The seed refuses an already populated project fixture; resume never reruns it.

`examples/customer-delivery-brief.report-template.json` is a portable report design. It queries governed current-space project facts and customers, resolves active owner labels, and renders project progress, budgets, risks and next actions. Import it through Report Designer to edit and export Markdown, HTML or PDF. It contains no stored result rows and does not imply AI analysis.

### Acceptance detail tabs

The acceptance detail uses ordinary DSL tabs: acceptance scope and materials are the default, approval history is separate, and execution records require `system_management`. A `workflow-panel` with `detailPlacement: "header"` stays above the tabs while approval is pending and refreshes the business record after an approval action. `bpmPanel.sections: ["status", "operations"]` and `showTechnicalDetails: false` keep this task card focused on the current work. `approval-history` and `rule-trace` independently opt into their respective evidence; legacy `history` keeps its combined behavior.

The verification tab is permission-gated by the optional verification plugin's `tdv.check.read`. Its read-only `tdv_check` sub-table is scoped by `tdv_ck_acceptance` to the current acceptance. Without that plugin/grant, the tab is absent and the child model is not queried. Child queries retain model permissions and tenant isolation; tab visibility does not grant access.

`extension.showInlineApprovalPanel: false` disables the automatic below-content approval report only for this composed detail. The default for other pages remains enabled. The acceptance runtime tab opts into `audit-report` instead, retaining the governed report and download under a collapsed maintenance disclosure. System field-history tabs are hidden here; the same read-only field-history block is available under execution records. Completed and returned acceptances show their business result without an empty pending-task card.


### Associated acceptance form

The overview references `bpmPanel.formBinding.formRef: "tpm_acceptance_form"` and renders that form's sections through `useDslForm` / `DslFormRenderer` in read-only object-card mode. `excludeFields` suppresses facts already shown in the object header; `asideBlockIds` places delivery materials in the existing right column. These are presentation hints, not field permissions. Drafts and returned records use the current form via `currentWhen`; submitted and completed approvals use the instance snapshot. The approval/task area and existing tabs remain unchanged.

At process start, BPM freezes the published page DTO and node field permissions in the protected `_formBindingsSnapshot`. Subsequent page publication affects new submissions only. This freezes structure and permissions, **not business values**: read-only detail displays the current governed business record. Historical values remain in field audit. Historical instances without a schema snapshot explicitly report an unconfirmed form version; they do not silently load the latest form.

Both review nodes use the acceptance form with all business fields read-only and `variable_only` saving. Corrections require rejection and the normal edit/resubmit path. The decision/comment map to the existing workflow result variables. The backend rejects read-only, hidden and undeclared field writes, mapped-variable bypasses and save-strategy overrides before running a command.

## First-use evaluation setup

On an empty local evaluation instance, run `bash scripts/project-delivery-demo.sh` after starting the stack. It performs the normal quickstart, explicitly imports this template, and creates fictional sample customers, real local members, projects, milestones, files and audit configuration through platform APIs. No SQL or application-start backfill is used.

The default frontend URL is `http://localhost:3000`. Host-first maintainers set `BACKEND_URL` to their owned frontend URL and `PLUGINS_PATH` to the plugin directory visible to the backend. The script accepts only local HTTP evaluation URLs; it never clears projects and refuses to seed an already populated project list. The initialization receipt directory is printed. Existing installations may import the template with `bash scripts/quickstart.sh --project-delivery` without requesting sample data.
