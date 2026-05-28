# Session Handover - 2026-05-19 16:56

## Session Summary

本轮在 isolated Docker E2E 栈中完成 BPMN designer strict `designerJson`
校验、历史数据刷新、BPM SQL hard-error 收敛、E2E 环境变量契约固化，以及
Plan A targeted + smoke gate 验证。代码已准备提交到
`codex/page-schema-request-loop-20260518`，但不应直接合并 `main`，因为 full
gate 尚未执行。

## Tasks Completed

- 修复 `/bpmn-designer?pid=01KRXCCGTYBGAV79S7AR0WEB62` 的
  `Cannot read properties of undefined (reading 'x')`，改为严格导入校验并刷新历史
  `designerJson`。
- 固化 Docker E2E env contract：`PLAYWRIGHT_BASE_URL`、`BACKEND_URL`、
  `BE_PORT`、`BFF_PORT`、`PW_SKIP_WEBSERVER` 必须一致。
- 将 Docker E2E 拓扑记录到 `docs/operations/ga-e2e-docker-stack.md`，并把
  isolated frontend memory 默认值调到 `FE_MEM=4g`。
- 收敛 BPM 运行时 hard errors：清理测试制造的 undeploy/delete 500，降低事件持久化
  和 SLA/no-SSE 相关查询成本。
- 修复 E2E helper 问题：auth controlled field flake、saved-view delete helper
  hide failure、`bpm-deep` cleanup active instance。
- 更新 `/Users/ghj/work/auraboot/OSS_E2E_TASKS.md`，记录 Plan A 证据和 full gate
  剩余策略。

## Tasks In Progress

- Full OSS gate 未执行。`PW_PROFILE=full PW_ROLE_PROJECTS=1 --list` 显示
  `1662` tests / `228` files；直接长跑已暴露 auth-state 过期风险。

## Key Decisions

| Decision | Chosen Approach | Rationale | Alternatives Considered |
| --- | --- | --- | --- |
| E2E topology | Host only runs Playwright runner; Vite/BFF/backend/Postgres/Redis all in Docker | 多 worktree 下隔离性更强，避免 host Node/env/proxy/node_modules/port drift | Host Vite/BFF + Docker backend only for short local debugging |
| Remaining SQL warning | Stop chasing warnings for Plan A | Hard errors cleared; remaining warnings are performance backlog, not smoke blocker | Continue optimizing `/tasks/todo`, tenant search, plugin import, command execution before smoke |
| Full gate | Do not claim full completion | Direct long run likely invalid due auth expiry | Split shards with auth refresh, or longer-lived E2E JWT/session |
| Merge | Do not merge to `main` yet | Evidence is targeted + smoke, not full gate | Direct local merge rejected as too risky |

## Files Changed

### Backend

- `platform/src/main/java/com/auraboot/framework/bpm/**` - event dispatch/persistence,
  SLA lookup, deployment cleanup, hook cache, process control, integration service trimming.
- `platform/src/main/java/com/auraboot/framework/meta/**` - model/field binding query
  trimming.
- `platform/src/main/java/com/auraboot/framework/notification/channel/InAppChannel.java` -
  skip unread work when no SSE sessions exist.
- `platform/src/test/java/com/auraboot/framework/bpm/**` - new focused tests for event,
  listener, controller, and service behavior.

### Frontend / E2E

- `web-admin/app/plugins/core-designer/components/bpmn-designer/**` - strict
  `designerJson` validation/import behavior and tests.
- `web-admin/tests/helpers/environments.ts` - Docker noweb env fail-fast contract.
- `web-admin/tests/e2e/auth/auth-complete.spec.ts` - login helper hydration and final
  email verification.
- `web-admin/tests/e2e/saved-view/saved-view-management.spec.ts` - delete helper fail-fast
  and cleanup-only 404 tolerance.
- `web-admin/tests/e2e/bpm/**` and `web-admin/tests/e2e/designer/**` - BPM cleanup and
  strict designer coverage adjustments.

### Configuration / Docs

- `docker-compose.isolated.yml` - frontend memory default `4g`.
- `docs/operations/ga-e2e-docker-stack.md` - Docker E2E topology and env contract.
- `/Users/ghj/work/auraboot/OSS_E2E_TASKS.md` - non-git workspace evidence log.

## Pitfalls & Workarounds

1. **401 after only setting `PLAYWRIGHT_BASE_URL`**
   - **Root Cause**: API helpers still defaulted to host backend port.
   - **Solution**: require `BACKEND_URL`, `BE_PORT`, and `BFF_PORT` for Docker noweb runs.
   - **Prevention**: use the documented env contract for targeted/smoke/full commands.

2. **Smoke run auth-state expiry**
   - **Root Cause**: long run reused aged storageState/JWT.
   - **Solution**: stop invalid run, refresh auth/setup, rerun smoke.
   - **Prevention**: full gate needs shards with auth refresh or longer-lived E2E session.

3. **Test cleanup generated BPM 500 logs**
   - **Root Cause**: tests undeployed/deleted definitions with running instances.
   - **Solution**: terminate tracked instances before destructive cleanup; skip cleanup if
     termination cannot be proven.
   - **Prevention**: E2E cleanup should not manufacture expected backend 500s.

## Verification Evidence

- Gradle targeted BPM/event tests: `23 passed`.
- Env contract Vitest: `5 passed`.
- Auth/setup: `18 passed / 1 skipped`.
- BPM broader slice: `48 passed / 6 skipped`.
- Focused `bpm-deep` smoke cleanup: `2 passed`.
- OSS smoke after helper fixes/auth refresh: `169 passed / 6 skipped`.
- Backend hard-log scan for final smoke window: no `Completed 500`, no `Unexpected system
  exception`, no DB exception, no `Cannot undeploy`, no auth expiry.
- Final health: backend `UP`, frontend `/login` HTTP `200`, BFF `/health` HTTP `200`.
- `git diff --check` passed.

Remaining warning-only logs in final smoke:

- `GET /api/bpm/tasks/todo`: `81` queries.
- `POST /api/tenant/members/search`: `56` queries.
- `POST /api/plugins/import/execute-direct`: `117` queries.
- `POST /api/meta/commands/execute/e2et:submit_order`: `50` queries.

## Current State

### Git

- Worktree: `/Users/ghj/work/auraboot/.worktrees/oss-latest-tag-20260518`
- Branch: `codex/page-schema-request-loop-20260518`
- Canonical OSS and enterprise repos remained on `main`.

### Running Services

- Docker project: `auraboot-oss-latest-tag-20260518`
- Ports: frontend `5226`, BFF `3553`, backend `6496`, Postgres `5485`, Redis `6531`.
- Stop with:

```bash
COMPOSE_PROJECT_NAME=auraboot-oss-latest-tag-20260518 \
PG_PORT=5485 BE_PORT=6496 VITE_PORT=5226 BFF_PORT=3553 REDIS_PORT=6531 FE_MEM=4g \
docker compose -f docker-compose.yml -f docker-compose.isolated.yml \
  --profile isolated --profile cache down
```

## Next Steps

1. Push branch and open PR, but mark full gate as not run.
2. Run full gate via split shards with auth refresh between shards, or implement longer-lived
   E2E JWT/session first.
3. Track SQL warning reduction as separate performance work for BPM tasks, tenant member
   search, plugin import, and command execution.
4. Merge to `main` only after full gate strategy produces valid evidence.
