# Smoke Skip Audit

Generated from `/tmp/pw-docker-system-smoke-enterprise-r10-json-20260520.json` on 2026-05-20. The skipped set matches the earlier r8 audit extraction exactly.

## Summary

- Collected after exclusion filtering: 638
- Passed: 566
- Skipped: 72
- Unexpected failed: 0
- Known-skipped candidates: 72
- Audit status: all skipped items categorized

## Category Counts

| Category | Count |
|---|---:|
| env-toggle | 4 |
| optional-plugin/profile | 42 |
| permission-gap | 5 |
| seed/config-gap | 21 |

## Gate Proposal

- Smoke CI requires `failed = 0`.
- Smoke CI requires `unexpected skipped = 0`.
- Known skips may exist only when listed below with reason, owner, and action.
- Category priorities: fix `permission-gap` and `seed/config-gap` first; move `optional-plugin/profile` to profile suites; split `mutually-exclusive` into setup-empty-db suite; turn `product-gap` into backlog items.

## Unexpected Failures Closed During Audit

- `e2e/admin/platform-admin-crud.spec.ts:514` PA-006 failed in r8: form field `domain_code` was not visible on the create form. r7 remained the 0-fail full run at that point; r8 was used for skip extraction and this failure was tracked separately for stabilization.
- Stabilization follow-up: PA-006 now waits for the create form's real `domain_code` and `domain_name` fields instead of any page-level input. Targeted evidence: `/tmp/pw-docker-system-targeted-pa006-20260520.log` (`20 passed`) and `/tmp/pw-docker-system-targeted-platform-admin-crud-20260520.log` (`31 passed`).
- Latest full evidence: `/tmp/pw-docker-system-smoke-enterprise-r10-20260520.log` (`566 passed`, `72 skipped`, `0 failed`).

## Skipped Tests

| Spec | Line | Test | Skip Reason | Category | Owner | Action |
|---|---:|---|---|---|---|---|
| e2e/auth/auth-complete.spec.ts | 304 | REG-005: should register successfully and redirect @smoke | Self-registration is disabled in single-tenant mode | env-toggle | QA | keep as known skip unless profile enables channel |
| e2e/auth/auth-complete.spec.ts | 486 | OTP-003: should send verification code and login @smoke | OTP send button click timeout — requires email service | env-toggle | QA | keep as known skip unless profile enables channel |
| e2e/auth/auth-complete.spec.ts | 1165 | TS-001: should display tenant selection page after registration @smoke | Self-registration is disabled in single-tenant mode | env-toggle | QA | keep as known skip unless profile enables channel |
| e2e/auth/auth-complete.spec.ts | 1165 | TS-002: should create tenant successfully after registration @smoke | Self-registration is disabled in single-tenant mode | env-toggle | QA | keep as known skip unless profile enables channel |
| e2e/block-renderer/block-types.spec.ts | 522 | BK-013: filters block should support search expand and collapse @smoke | Filter form not rendered — model has no searchFields configured | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/crm-starter-demo-dashboard.spec.ts | 93 | DASH-001 @smoke — sidebar → dashboard renders both smart-table-chart widgets | crm-starter dashboard is not imported in this profile | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/crm-starter-demo-dashboard.spec.ts | 138 | DASH-002 @smoke — /dashboards resolves default CRM dashboard with seeded rows | crm-starter dashboard is not imported in this profile | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/dashboard/dashboard-management.spec.ts | 525 | DM-E07: row click navigates to designer @smoke | Dashboard rows not found in list after API creation | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/dual-prevention/dp-issue-lifecycle.spec.ts | 955 | DIL-011: visibleWhen — edit/delete buttons hidden for pending issues @smoke | Main test issue row not visible in pending tab | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/p1demo/wd-leave-request-ai-lifecycle.spec.ts | 45 | AI-001 @smoke — banner visible above form, button opens dialog | workflow-demo plugin not imported in current environment | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/pcba-solution/pcba-production-dashboard.spec.ts | 158 | PCBA-DASH-02: KPI NQ returns data with production counts | KPI NQ returned 500 — prerequisite table may not exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-drilldown.spec.ts | 31 | DD-001: Dashboard renders all KPI blocks with drill-down | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-drilldown.spec.ts | 31 | DD-002: Click contract total KPI navigates to cc-contract with filter | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-drilldown.spec.ts | 31 | DD-003: Click contract count KPI navigates to cc-contract with filter | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-drilldown.spec.ts | 31 | DD-004: Click safety issues KPI navigates to dp-issue with filter | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-drilldown.spec.ts | 31 | DD-005: Click quality checks KPI navigates to qm-checkpoint with filter | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-drilldown.spec.ts | 31 | DD-006: Target page dp-issue loads with URL filter applied | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-drilldown.spec.ts | 31 | DD-007: End-to-end drill-down from dashboard to dp-issue with filter | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-drilldown.spec.ts | 31 | DD-008: Original QO KPI cards also have drill-down navigation | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-kpi-enhanced.spec.ts | 32 | KPI-E-001: Dashboard renders all 3 new KPI blocks visible | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-kpi-enhanced.spec.ts | 32 | KPI-E-002: Dashboard renders safety distribution pie chart | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-kpi-enhanced.spec.ts | 32 | KPI-E-003: Dashboard renders monthly KPI bar chart | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-kpi-enhanced.spec.ts | 32 | KPI-E-004: Click profit rate KPI navigates to cc-profit-analysis | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-kpi-enhanced.spec.ts | 32 | KPI-E-005: Click cost overrun KPI navigates to cc-cost-budget | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-kpi-enhanced.spec.ts | 32 | KPI-E-006: Click schedule variance KPI navigates to pm-schedule-deviation | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/qo-dashboard-kpi-enhanced.spec.ts | 32 | KPI-E-007: Total dashboard blocks count >= 13 | Dashboard page qo_dashboard_data (kind=dashboard) not configured — only list/form/detail exist | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/quarry/quarry-coverage-gap.spec.ts | 86 | CG-003: CC contract lifecycle tabs and row actions are all operable | No explicit reason in JSON annotation | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/report-template/report-generate-button.spec.ts | 67 | RGN-03: Published templates API returns valid response | Report templates published API not available | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/report-template/report-template-crud.spec.ts | 66 | RPT-02: Can create a new report template via API | Report templates API not available | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/report-template/report-template-crud.spec.ts | 76 | RPT-03: Created template appears in list API | Report template API returns non-200 — feature may not be deployed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/report-template/report-template-crud.spec.ts | 89 | RPT-04: Can read template detail via API | Skipped because RPT-02 did not create a template | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/report-template/report-template-crud.spec.ts | 102 | RPT-05: Can update template via API | Skipped because RPT-02 did not create a template | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/report-template/report-template-crud.spec.ts | 122 | RPT-06: Publish requires template content | Skipped because RPT-02 did not create a template | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/report-template/report-template-crud.spec.ts | 133 | RPT-07: Published templates API is accessible | Report templates API not available | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/report-template/report-template-crud.spec.ts | 142 | RPT-08: Code uniqueness check API works | Skipped because RPT-02 did not create a template | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/report-template/report-template-crud.spec.ts | 151 | RPT-09: Categories API returns our category | Skipped because RPT-02 did not create a template | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/report-template/report-template-crud.spec.ts | 160 | RPT-10: Editor page loads for existing template | Skipped because RPT-02 did not create a template | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/saved-view/saved-view-calendar.spec.ts | 122 | SV-020: CALENDAR — renders events by date field @smoke | No explicit reason in JSON annotation | seed/config-gap | QA/platform | add deterministic seed/config or move to optional suite |
| e2e/scheduler/scheduler-crud.spec.ts | 473 | SC-009: should reload scheduler @smoke | Scheduler page may require specific permissions | permission-gap | backend/platform | fix role/permission or move to permission suite |
| e2e/templates/templates-smoke.spec.ts | 200 | TMP-CRM-001 @smoke — 线索列表页可访问 | CRM Quick Start template not accessible: 403 (model may not be installed or user lacks permission) | permission-gap | backend/platform | fix role/permission or move to permission suite |
| e2e/templates/templates-smoke.spec.ts | 200 | TMP-CRM-002 @smoke — 客户列表页可访问 | CRM Quick Start template not accessible: 403 (model may not be installed or user lacks permission) | permission-gap | backend/platform | fix role/permission or move to permission suite |
| e2e/templates/templates-smoke.spec.ts | 200 | TMP-CRM-003 @smoke — 联系人列表页可访问 | CRM Quick Start template not accessible: 403 (model may not be installed or user lacks permission) | permission-gap | backend/platform | fix role/permission or move to permission suite |
| e2e/templates/templates-smoke.spec.ts | 200 | TMP-CRM-004 @smoke — 商机列表页可访问 | CRM Quick Start template not accessible: 403 (model may not be installed or user lacks permission) | permission-gap | backend/platform | fix role/permission or move to permission suite |
| e2e/templates/templates-smoke.spec.ts | 254 | TMP-PM-001 @smoke — 项目列表页可访问 | Project Management template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 254 | TMP-PM-002 @smoke — 任务列表页可访问 | Project Management template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 254 | TMP-PM-003 @smoke — 里程碑列表页可访问 | Project Management template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 302 | TMP-ASSET-001 @smoke — 资产列表页可访问 | Asset Management template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 302 | TMP-ASSET-002 @smoke — 分类列表页可访问 | Asset Management template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 302 | TMP-ASSET-003 @smoke — 维护记录列表页可访问 | Asset Management template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 341 | TMP-INV-001 @smoke — 产品列表页可访问 | Simple Inventory template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 341 | TMP-INV-002 @smoke — 仓库列表页可访问 | Simple Inventory template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 341 | TMP-INV-003 @smoke — 入库列表页可访问 | Simple Inventory template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 341 | TMP-INV-004 @smoke — 出库列表页可访问 | Simple Inventory template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 385 | TMP-HR-001 @smoke — 员工列表页可访问 | HR Essentials template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 385 | TMP-HR-002 @smoke — 考勤记录列表页可访问 | HR Essentials template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 385 | TMP-HR-003 @smoke — 请假申请列表页可访问 | HR Essentials template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 448 | TMP-GP-001 @smoke — 任务列表页可访问 | Golden Path template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 448 | TMP-GP-002 @smoke — 评论列表页可访问 | Golden Path template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 448 | TMP-GP-003 @smoke — 审批列表页可访问 | Golden Path template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 496 | TMP-EHR-001 @smoke — 员工列表页可访问 | Enterprise HR template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 496 | TMP-EHR-002 @smoke — 部门列表页可访问 | Enterprise HR template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 496 | TMP-EHR-003 @smoke — 职位列表页可访问 | Enterprise HR template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 496 | TMP-EHR-004 @smoke — 薪资列表页可访问 | Enterprise HR template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 549 | TMP-ECM-001 @smoke — 政策列表页可访问 | Enterprise Compliance template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 549 | TMP-ECM-002 @smoke — 审计发现列表页可访问 | Enterprise Compliance template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 549 | TMP-ECM-003 @smoke — 纠正措施列表页可访问 | Enterprise Compliance template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 549 | TMP-ECM-004 @smoke — 风险评估列表页可访问 | Enterprise Compliance template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 603 | TMP-EAM-001 @smoke — 资产列表页可访问 | Enterprise Asset template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 603 | TMP-EAM-002 @smoke — 分类列表页可访问 | Enterprise Asset template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 603 | TMP-EAM-003 @smoke — 维护计划列表页可访问 | Enterprise Asset template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/templates-smoke.spec.ts | 603 | TMP-EAM-004 @smoke — 工单列表页可访问 | Enterprise Asset template not installed | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
| e2e/templates/thr-leave-request-lifecycle.spec.ts | 209 | LV-001 @smoke — Navigate via sidebar menu → list page loads with table | HR Essentials template is not imported in current environment | optional-plugin/profile | frontend/platform | move to plugin/profile suite or import profile fixture |
