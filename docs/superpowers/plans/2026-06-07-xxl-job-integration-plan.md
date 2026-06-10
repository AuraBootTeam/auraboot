---
type: run-log
status: closed
created: 2026-06-07
relates_to:
  - docs/superpowers/specs/2026-06-07-xxl-job-integration-design.md
---
<!-- no-precipitation: session run-log for XXL-JOB integration plan execution; conclusions embedded in code/PRs, no standalone reusable lesson beyond what's in the spec -->


# XXL-JOB Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend bridge that lets AuraBoot run scheduler tasks through XXL-JOB while preserving AuraBoot's scheduler abstraction, and prove cron plus programmatic one-time scheduling against a real XXL-JOB Admin + MySQL stack.

**Architecture:** Keep `ScheduledTaskService` and `TaskExecutor` as the AuraBoot control and execution contracts. Add an optional XXL-JOB scheduler engine and a single controlled executor handler that delegates back into AuraBoot by `taskPid`.

**Tech Stack:** Java 21, Spring Boot 3.5, Gradle, JUnit 5, Mockito, XXL-JOB `xxl-job-core`.

---

## Files

- Modify `platform/build.gradle`: add `xxlJobVersion` and `com.xuxueli:xxl-job-core`.
- Modify `platform/src/main/resources/application.yml`: add disabled-by-default `aura.scheduler` and `xxl-job` settings.
- Modify `platform/src/main/java/com/auraboot/framework/scheduler/entity/ScheduledTask.java`: add external scheduler metadata fields.
- Modify `platform/src/main/resources/database/schema.sql`: add scheduler metadata columns.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/XxlJobProperties.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/XxlJobAdminClient.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/XxlJobAdminRequest.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/XxlJobAdminResponse.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/XxlJobSchedulerEngine.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/XxlJobAdminHttpClient.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/UnavailableXxlJobAdminClient.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/AuraBootScheduledTaskJobPayload.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/AuraBootScheduledTaskJobHandler.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/xxl/XxlJobExecutorConfig.java`.
- Create `platform/src/main/java/com/auraboot/framework/scheduler/handler/ScheduledTaskSmokeHandler.java`.
- Create `docker/xxl-job-smoke/docker-compose.yml`.
- Create `docker/xxl-job-smoke/tables_xxl_job.sql`.
- Create `scripts/dev/xxl-job-true-stack-smoke.sh`.
- Create unit tests under `platform/src/test/java/com/auraboot/framework/scheduler/xxl/`.

## Task 1: Documentation And Baseline

- [x] **Step 1: Save design document**

Created `docs/superpowers/specs/2026-06-07-xxl-job-integration-design.md`.

- [x] **Step 2: Save implementation plan**

Created this file.

- [x] **Step 3: Run existing scheduler baseline**

Run:

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/feat-xxl-job-scheduler/platform
./gradlew :test --tests 'com.auraboot.framework.scheduler.service.ScheduledTaskServiceIntegrationTest' --tests 'com.auraboot.framework.scheduler.ScheduledTaskCommandHardeningIntegrationTest'
```

Expected: existing scheduler tests pass before production changes.

## Task 2: Add Payload Validation And Handler Tests

- [x] **Step 1: Write failing tests**

Create `platform/src/test/java/com/auraboot/framework/scheduler/xxl/AuraBootScheduledTaskJobHandlerTest.java` with tests that:

- reject missing `taskPid`.
- reject unknown `taskPid`.
- delegate a valid loaded task to `TaskExecutor`.

- [x] **Step 2: Run red test**

Run:

```bash
./gradlew :test --tests 'com.auraboot.framework.scheduler.xxl.AuraBootScheduledTaskJobHandlerTest'
```

Expected: compilation fails because the handler and payload classes do not exist.

- [x] **Step 3: Implement minimal handler**

Add `AuraBootScheduledTaskJobPayload` and `AuraBootScheduledTaskJobHandler`. Use Jackson for JSON parsing. Use `ScheduledTaskMapper.findByPid` and `TaskExecutor.execute`. Throw `BusinessException` for invalid payload or unknown task.

- [x] **Step 4: Run green test**

Run:

```bash
./gradlew :test --tests 'com.auraboot.framework.scheduler.xxl.AuraBootScheduledTaskJobHandlerTest'
```

Expected: handler tests pass.

## Task 3: Add XXL Scheduler Engine Tests

- [x] **Step 1: Write failing tests**

Create `platform/src/test/java/com/auraboot/framework/scheduler/xxl/XxlJobSchedulerEngineTest.java` with tests that:

- schedule maps enabled cron task to `XxlJobAdminClient.upsert`.
- unschedule maps to `XxlJobAdminClient.disable`.
- reload schedules all enabled tasks from `ScheduledTaskMapper`.

- [x] **Step 2: Run red test**

Run:

```bash
./gradlew :test --tests 'com.auraboot.framework.scheduler.xxl.XxlJobSchedulerEngineTest'
```

Expected: compilation fails because the engine and client contracts do not exist.

- [x] **Step 3: Implement minimal engine and client contract**

Add:

- `XxlJobAdminClient`
- `XxlJobAdminRequest`
- `XxlJobAdminResponse`
- `XxlJobSchedulerEngine`

The first implementation defines the contract and maps calls to the client. The final implementation also includes the HTTP Admin adapter after confirming XXL-JOB `3.4.0` endpoint behavior.

- [x] **Step 4: Run green test**

Run:

```bash
./gradlew :test --tests 'com.auraboot.framework.scheduler.xxl.XxlJobSchedulerEngineTest'
```

Expected: engine mapping tests pass.

## Task 4: Add Conditional Configuration

- [x] **Step 1: Write failing context tests**

Create `platform/src/test/java/com/auraboot/framework/scheduler/xxl/XxlJobConfigurationTest.java` with tests that:

- bind `aura.scheduler.engine=xxl`.
- keep local mode as the default.
- expose XXL executor config only when enabled.

- [x] **Step 2: Run red test**

Run:

```bash
./gradlew :test --tests 'com.auraboot.framework.scheduler.xxl.XxlJobConfigurationTest'
```

Expected: compilation or bean assertion failure before properties/config exist.

- [x] **Step 3: Implement properties and config**

Add:

- `XxlJobProperties`
- `XxlJobExecutorConfig`
- application defaults with XXL disabled.

- [x] **Step 4: Run green test**

Run:

```bash
./gradlew :test --tests 'com.auraboot.framework.scheduler.xxl.XxlJobConfigurationTest'
```

Expected: config tests pass without requiring a live XXL-JOB Admin.

## Task 5: Add Schema And Entity Metadata

- [x] **Step 1: Write failing entity/schema test**

Extend an existing scheduler test or add `ScheduledTaskXxlMetadataTest` that checks the Java entity exposes external scheduler metadata fields.

- [x] **Step 2: Run red test**

Run:

```bash
./gradlew :test --tests 'com.auraboot.framework.scheduler.xxl.ScheduledTaskXxlMetadataTest'
```

Expected: compilation fails before fields exist.

- [x] **Step 3: Add entity and schema fields**

Add Java fields and schema columns for scheduler metadata. Keep defaults compatible with local mode.

- [x] **Step 4: Run green test**

Run:

```bash
./gradlew :test --tests 'com.auraboot.framework.scheduler.xxl.ScheduledTaskXxlMetadataTest'
```

Expected: metadata test passes.

## Task 6: Final Verification

- [x] **Step 1: Run targeted tests**

Run:

```bash
./gradlew :test \
  --tests 'com.auraboot.framework.scheduler.xxl.*' \
  --tests 'com.auraboot.framework.scheduler.service.ScheduledTaskServiceIntegrationTest' \
  --tests 'com.auraboot.framework.scheduler.ScheduledTaskCommandHardeningIntegrationTest'
```

Expected: targeted XXL and existing scheduler tests pass.

- [x] **Step 2: Compile production code**

Run:

```bash
./gradlew :compileJava
```

Expected: compile succeeds.

- [x] **Step 3: Add real XXL-JOB Admin adapter**

Add `XxlJobAdminHttpClient` for login, executor group lookup/create, job lookup/create/update, start/stop/delete, and manual trigger.

- [x] **Step 4: Add programmatic one-time scheduling**

Extend AuraBoot scheduled task create/update handling with `nextRunAt`. In XXL mode, map one-time tasks to exact cron expressions such as:

```text
ss mm HH dd MM ? yyyy
```

- [x] **Step 5: Add true-stack smoke script**

Add `scripts/dev/xxl-job-true-stack-smoke.sh` to start:

- XXL-JOB Admin + MySQL
- AuraBoot PostgreSQL + Redis
- AuraBoot backend in `aura.scheduler.engine=xxl`

The smoke script verifies:

- executor registry appears in XXL-JOB Admin.
- AuraBoot API creates a cron task.
- AuraBoot API creates a one-time task with a concrete `nextRunAt`.
- both jobs exist in `xxl_job_info`.
- cron and one-time executions create successful AuraBoot task logs.
- programmatic manual trigger increases the cron task success count.

- [x] **Step 6: Run true-stack smoke**

Run:

```bash
CLEANUP=0 scripts/dev/xxl-job-true-stack-smoke.sh
```

Observed evidence:

```text
[xxl-smoke] cron task succeeded: pid=01KTG931ERYEFJP8CRZRWPEPB7
[xxl-smoke] one-time task succeeded: pid=01KTG931MK18NR82ARQW94EAEE
[xxl-smoke] manual-trigger task success count reached 7: pid=01KTG931ERYEFJP8CRZRWPEPB7
[xxl-smoke] XXL-JOB true-stack smoke passed
```

Database evidence:

```text
01KTG931ERYEFJP8CRZRWPEPB7 | success | 7
01KTG931MK18NR82ARQW94EAEE | success | 1
```

Result: XXL-JOB backend bridge, real Admin adapter, cron scheduling, manual trigger, and programmatic one-time scheduling are implemented and verified against a live XXL-JOB Admin + MySQL + AuraBoot stack.
