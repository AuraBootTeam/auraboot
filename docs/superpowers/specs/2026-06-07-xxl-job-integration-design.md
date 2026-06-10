---
type: run-log
status: closed
created: 2026-06-07
relates_to:
  - docs/superpowers/plans/2026-06-07-xxl-job-integration-plan.md
---
<!-- no-precipitation: session run-log for XXL-JOB integration design; conclusions embedded in code/PRs, no standalone reusable lesson beyond what's in the spec -->


# XXL-JOB Integration Design

## Target

AuraBoot integrates XXL-JOB as a distributed scheduling infrastructure while keeping AuraBoot as the business control plane. AuraBoot remains responsible for tenant context, permissions, task definitions, Command routing, audit, and platform logs. XXL-JOB is responsible for cron dispatch, executor registration, routing, blocking policy, retry, sharding, and scheduler-side logs.

Production uses XXL-JOB Admin. Local development can keep the existing local scheduler through a configuration switch.

## Current AuraBoot Baseline

AuraBoot already has a scheduler abstraction and persistence model:

- `com.auraboot.framework.scheduler.service.SchedulerEngine`
- `com.auraboot.framework.scheduler.service.impl.DatabaseSchedulerEngine`
- `com.auraboot.framework.scheduler.service.impl.ScheduledTaskServiceImpl`
- `com.auraboot.framework.scheduler.service.impl.DefaultTaskExecutor`
- `ab_scheduled_task`
- `ab_scheduled_task_log`

The integration must extend these contracts instead of bypassing them.

## Architecture

```text
AuraBoot Scheduler API / Command / UI
  -> ScheduledTaskService
  -> SchedulerEngine(local | xxl)
  -> XxlJobAdminClient
  -> XXL-JOB Admin

XXL-JOB Admin
  -> AuraBoot Executor @XxlJob("aurabootScheduledTaskJob")
  -> AuraBootScheduledTaskJobHandler
  -> TaskExecutor / CommandExecutor
  -> ab_scheduled_task_log / audit / metrics
```

The only executor handler exposed by AuraBoot in M1/M2 is `aurabootScheduledTaskJob`. The handler accepts a JSON payload that identifies the AuraBoot task, tenant, trigger source, trace id, and optional shard metadata. It then loads the task from AuraBoot storage and delegates to the existing `TaskExecutor`.

## Key Decisions

1. XXL-JOB Admin is required for real cron, routing, retry, sharding, executor registration, and scheduler logs.
2. `ab_scheduled_task` remains the AuraBoot source of truth.
3. Business code calls AuraBoot APIs or Commands, not XXL-JOB executor endpoints.
4. The scheduler engine is selected by `aura.scheduler.engine=local|xxl`.
5. M1/M2 do not migrate all existing `@Scheduled` methods.
6. GLUE, command-line handlers, and generic HTTP handlers are not exposed through AuraBoot's executor.
7. Task execution is not exactly-once; handlers must be idempotent.
8. Tenant context is restored inside AuraBoot before any business execution.

## Programmatic Trigger

Programmatic trigger remains AuraBoot-owned:

```text
POST /api/scheduled-tasks/{pid}/trigger
```

When the active engine is `xxl`, AuraBoot validates permissions and task ownership, then asks XXL-JOB Admin to trigger the mapped external job. The executor receives a payload with `taskPid`, `tenantId`, `traceId`, `triggerType=manual`, and optional runtime parameters.

## Cron Trigger

Cron trigger is owned by XXL-JOB Admin when the active engine is `xxl`. AuraBoot maps `ab_scheduled_task.cron_expression` to the external job configuration and keeps the external job id on the AuraBoot task row.

Per-task timezone remains an AuraBoot field. If the selected XXL-JOB Admin version cannot express per-task timezone, M1/M2 use the platform timezone and document the limitation in the UI/API response.

## Data Contract

M1/M2 introduces Java-side fields before requiring full platform UI support:

- scheduler type: `local` or `xxl`
- external job id
- external sync status
- external sync error
- route strategy
- block strategy
- misfire strategy
- sharding enabled
- next run time for programmatic one-time scheduling

Execution payload:

```json
{
  "taskPid": "task pid",
  "tenantId": 1,
  "traceId": "trace id",
  "triggerType": "scheduled",
  "params": {},
  "shardIndex": 0,
  "shardTotal": 1
}
```

## Implemented Scope

The current implementation covers the backend integration path:

- `aura.scheduler.engine=local|xxl` selects the scheduler engine.
- Local mode remains the default and does not require a live XXL-JOB Admin.
- XXL mode creates a `XxlJobSpringExecutor` and registers the controlled `aurabootScheduledTaskJob` handler.
- The handler validates the XXL payload, loads the AuraBoot task by `taskPid`, and delegates to AuraBoot's existing `TaskExecutor`.
- AuraBoot maps create, update, enable, disable, delete, and manual trigger operations to XXL-JOB Admin.
- AuraBoot supports programmatic one-time scheduling by accepting `nextRunAt` and converting it to an exact XXL cron expression.
- AuraBoot stores external scheduler metadata on `ab_scheduled_task`.

## XXL-JOB Admin Bridge

AuraBoot uses XXL-JOB Admin's web endpoints for job and executor group management:

- login: `POST /auth/doLogin`
- executor group lookup/create: `/jobgroup/pageList`, `/jobgroup/insert`
- job lookup/create/update: `/jobinfo/pageList`, `/jobinfo/insert`, `/jobinfo/update`
- lifecycle and manual trigger: `/jobinfo/start`, `/jobinfo/stop`, `/jobinfo/delete`, `/jobinfo/trigger`

This is intentionally wrapped behind `XxlJobAdminClient` so the platform can replace the adapter if XXL-JOB exposes a stable official OpenAPI in a later version.

## Operational Requirements

- XXL-JOB Admin uses its own MySQL database.
- AuraBoot continues to use PostgreSQL.
- `accessToken` is required for executor and Admin communication.
- Admin default account credentials must be changed.
- Executor ports must not be publicly exposed.
- Admin reachability failures must surface as explicit scheduler sync errors, not silent local fallback.
- The production database migration path must add the new scheduler metadata columns before enabling XXL mode.

## Limitations

- Without XXL-JOB Admin, AuraBoot can only use the existing local scheduler.
- `aura.scheduler.engine=xxl` requires a reachable Admin address; if no Admin address is configured the bridge fails explicitly through `UnavailableXxlJobAdminClient`.
- AuraBoot and XXL-JOB use separate databases, so task sync is not a single transaction.
- XXL-JOB does not provide AuraBoot multi-tenancy; AuraBoot must enforce tenant context during execution.
- XXL-JOB retries and manual triggers can cause duplicate execution; jobs must be idempotent.
- Plugin lifecycle integration is required before plugin-provided handlers are managed by XXL-JOB.
- High-frequency internal pollers are not automatically migrated.
- Per-task timezone is normalized into the generated XXL cron expression; production deployments should run Admin and AuraBoot with a consistent zone unless the UI/API exposes the effective conversion clearly.
- The Admin adapter targets XXL-JOB `3.4.0` endpoint behavior and should be regression-tested when upgrading XXL-JOB.

## Milestones

### M1: Executor Integration

- Add XXL-JOB dependency and properties.
- Configure `XxlJobSpringExecutor` only when XXL-JOB is enabled.
- Add `AuraBootScheduledTaskJobHandler`.
- Parse and validate JSON payload.
- Delegate to existing `TaskExecutor`.

### M2: Scheduler Engine Bridge

- Add `XxlJobSchedulerEngine`.
- Add `XxlJobAdminClient` abstraction.
- Map create, update, delete, enable, disable, and manual trigger to the external scheduler.
- Keep `DatabaseSchedulerEngine` as local mode.

### M3: Sync And Observability

- Add sync status and reconciliation.
- Add external log id and executor metadata to task logs.
- Add metrics and alertable failure states.

### M4: UI Enhancement

- Show scheduler engine, external job id, sync status, and sync error.
- Preserve existing Scheduler page entry and permission model.

### M5: Selective Migration

- Audit existing `@Scheduled` methods.
- Migrate only tasks that benefit from distributed scheduling.
- Keep short interval in-process maintenance tasks local unless there is a real distributed coordination need.

## Validation Evidence

Completed checks:

- local scheduler tests still pass.
- XXL payload validation rejects missing `taskPid`.
- XXL handler loads the AuraBoot task and delegates to `TaskExecutor`.
- `XxlJobSchedulerEngine` maps schedule/unschedule/manual trigger to `XxlJobAdminClient`.
- disabled local mode does not require XXL-JOB classes to start.
- a true-stack run starts XXL-JOB Admin + MySQL + AuraBoot and observes cron, one-time, and manual-trigger success rows in `ab_scheduled_task_log`.

True-stack smoke command:

```bash
CLEANUP=0 scripts/dev/xxl-job-true-stack-smoke.sh
```

Smoke evidence from the completed run:

- XXL-JOB Admin + MySQL started on the smoke stack.
- AuraBoot started with `aura.scheduler.engine=xxl`.
- Executor registry became visible in XXL-JOB Admin.
- AuraBoot API created a cron task and a one-time task.
- XXL-JOB stored both external jobs.
- AuraBoot task logs recorded `7` successful cron/manual executions and `1` successful one-time execution.
