# Project Management Template

Simple project and task tracking for teams. One-click install to get project, task, and milestone management.

## What's Included

| Model | Description |
|-------|-------------|
| Project | Initiatives with lifecycle (PLANNING → ACTIVE → COMPLETED) |
| Task | Work items with assignee and due date tracking |
| Milestone | Key deliverables with target dates |

## Key Features

- **Project Lifecycle**: PLANNING → ACTIVE ↔ ON_HOLD → COMPLETED / CANCELLED
- **Task Flow**: TODO → IN_PROGRESS → IN_REVIEW → DONE
- **Project 360**: View tasks and milestones linked to each project
- **Named Query**: Task summary by project for dashboards

## Installation

```bash
aura plugin import plugins/project-management
```

## Namespace

All resources use the `tpm` namespace prefix.

## Delivery Experience Blueprint

This template is the business entry point; Field Showcase remains a developer reference.
Sample customer names and projects are fictional and must be labeled as sample data.

| Page / action | User decision | DSL implementation | Runtime evidence |
| --- | --- | --- | --- |
| Project list | Which customer delivery needs attention? | Existing list, lifecycle views, customer, owner, due date | Tenant-scoped project records |
| Project form | What is the promised outcome and delivery date? | Two form sections: customer outcome / delivery plan | Create and update command payloads |
| Project detail | What is complete and what comes next? | Header identity, milestone aggregate metric-strip, milestone sub-table, activity sidebar | NamedQuery counts and real command audit |
| Complete milestone | Confirm the agreed deliverable is finished | Existing state_transition command and guarded row action | pending -> reached, aggregate and completion facts update immediately and after reload |

Project ownership uses a separate sys_user reference; the existing text owner field remains for compatibility and is excluded from the delivery form. Customer is a business name, not a CRM relationship. Completion progress is the ratio of reached milestones to all milestones, computed by the backend query; it is never an editable percentage. No attachment placeholder or fabricated audit activity is presented.

Form guidance: outcome and customer name use examples; dates use the native date selector; owner uses the member picker; budget explains CNY. Optional long outcome text spans the full row. Internal record identifiers remain hidden.

Primary actions: create project, edit plan, complete a pending milestone with confirmation. Completed milestones have no repeat-completion button. Lifecycle transitions remain server-validated. Loading/empty/error/read-only/permission and persistence are acceptance cases; conflict/stale protection is not invented by the template. Mobile and production deployment are outside this change.


### Local acceptance

Use the public OSS host-first launcher with `--plugin project-management` in an owned runtime. Reset only that runtime database when a new controlled fixture is needed. After import and identity verification, run `scripts/seed-delivery.py` with `DELIVERY_BACKEND_URL`, `DELIVERY_EVIDENCE_ROOT`, `ADMIN_EMAIL` and `ADMIN_PASSWORD`. The fixture tool accepts loopback only and refuses an already populated project table. It provisions three fictional team members without sending email and creates eight fictional projects through commands.

Run the browser journey with `DELIVERY_WEB_URL` and the same evidence directory:

```bash
pnpm --dir web-admin exec playwright test -c playwright.delivery.config.ts
```

Create the screenshot matrix in the evidence round before running the journey (P01-P09). The completion test expects the first controlled project to begin with two of four milestones reached. It retains the completed milestone and requires a fresh controlled fixture for another full run; it does not silently revert business state.

`detailAsideBlockIds` is an opt-in shared detail layout: the configured blocks move into a responsive aside, other blocks retain their DSL order. Child mutations reload declared page data sources and remount activity blocks so unchanged parent snapshots cannot leave derived progress or activities stale. Completion time is assigned by the command, not supplied by the browser. Historical milestones without a recorded completion timestamp are excluded from completion facts; they are not backfilled into fictitious history.
