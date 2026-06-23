---
type: handover
status: active
created: 2026-06-22
relates_to:
  - docs/backlog/2026-06-22-agent-run-path-declared-tool-discovery.md
  - docs/core-concepts/agent-readiness.md
---

# Session Handover - 2026-06-22 16:31 CST

## Session Summary

This session finished the agent dispatch/run-path declared-tool fix and proved the customer-service
approval flow on a host-first DeepSeek live stack. After SmartEngine 4.0.2 was published to Maven
Central, the branch also fixed the clean-CI repository ordering issue so SmartEngine artifacts resolve
from Maven Central before the incomplete Aliyun mirror. After rebasing onto latest `main`, it also
adds Maven Central-first Gradle plugin marker resolution to avoid clean Gradle homes depending only
on Gradle Plugin Portal availability.

## Tasks Completed

- [x] Fixed dispatch/run tool discovery so explicitly declared cross-model tools are always merged
  into the grounded tool set.
- [x] Fixed custom tool discovery metadata so approval/schema/risk are visible before execution.
- [x] Fixed generic `get:` / `list:` DSL read tools to execute through the provider registry.
- [x] Fixed approval pause/resume so the approved tool input is replayed exactly after approval.
- [x] Fixed seeded CS-agent approval policy role drift from `TENANT_ADMIN` to `tenant_admin`.
- [x] Proved live gold: inbound email -> agent -> approval -> `send_customer_reply` -> sent log ->
  `crm:create_activity` -> `mt_crm_activity`.
- [x] Fixed clean CI dependency resolution for SmartEngine 4.0.2 by routing
  `com.auraboot.smart.framework` to Maven Central before Aliyun mirrors.
- [x] Fixed clean Gradle plugin marker resolution by adding `pluginManagement.repositories` with
  Maven Central before Gradle Plugin Portal.
- [x] Destroyed the temporary live runtime `cs-inbound-gold-77`; port `6477` is no longer listening.
- [x] Opened PR #1021: <https://github.com/AuraBootTeam/auraboot/pull/1021>.

## Key Decisions

| Decision | Chosen Approach | Rationale | Alternatives Considered |
|---|---|---|---|
| Declared tool discovery | Shared `DeclaredAgentToolResolver`, merged additively into run-path tools | Keeps run behavior aligned with chat discovery while minimizing blast radius | Rebuild all discovery around agent declarations |
| Approval resume | Replay the exact approved tool input | Preserves the human approval boundary and avoids LLM drift on resume | Re-enter the LLM after approval |
| Custom tool metadata | Include schema, approval, and risk in discovery SQL/mapping | Prevents approved custom tools from executing with `{}` or bypassing approval | Infer metadata later during execution |
| CS-agent integration assertion | Assert run terminal state and log send/action rows as diagnostics | Real LLM routing is nondeterministic; deterministic contract is approval resume and no duplicate effect | Hard assert every LLM-selected side effect in the Spring IT |
| SmartEngine repository order | Resolve `com.auraboot.smart.framework` from Maven Central before Aliyun | Maven Central has 4.0.2 while the mirror can be partially synced; content filters avoid Gradle repository stickiness | Wait for mirror sync or rely on `mavenLocal()` |
| Gradle plugin marker order | Resolve plugin markers from Maven Central before Gradle Plugin Portal | Spring Boot/dependency-management markers are on Maven Central; clean builds should tolerate plugin portal edge failures | Rely on default plugin portal only |

## Files Changed

### Backend

- `platform/src/main/java/com/auraboot/framework/agent/service/DeclaredAgentToolResolver.java` -
  shared declared-tool resolver for chat/run parity.
- `platform/src/main/java/com/auraboot/framework/agent/service/StepLoopService.java` - approval
  pause/resume handling and duplicate future-step completion.
- `platform/src/main/java/com/auraboot/framework/agent/service/AgentApprovalPendingException.java` -
  carries approval pid, tool name, and approved input.
- `platform/src/main/java/com/auraboot/framework/agent/service/ToolLoopService.java` - generic model
  reads route through the provider registry.
- `platform/src/main/java/com/auraboot/framework/agent/provider/CustomToolProvider.java` - discovery
  includes `input_schema`, `requires_approval`, and `risk_level`.
- `platform/src/main/resources/database/schema.sql` - schema support for seeded/runtime contracts.
- `platform/build.gradle` - content-filtered Maven Central repository for SmartEngine fork artifacts
  before Aliyun mirrors.
- `platform/settings.gradle` - Maven Central-first plugin marker repository configuration.

### Tests / Scripts

- `platform/src/test/java/com/auraboot/framework/agent/service/DeclaredAgentToolResolverTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/service/AgentRunServiceSyncTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/service/StepLoopServiceLlmResponseGuardTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/service/ToolLoopServiceSafetyTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/provider/CustomToolProviderTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/provider/CustomToolProviderExecutionTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/CustomerServiceAgentIntegrationTest.java`
- `scripts/reset-init-contracts.test.mjs`
- `scripts/seed-cs-agent.sql`
- `scripts/dev/plugin-import-profiles.json`

### Documentation

- `docs/backlog/2026-06-22-agent-run-path-declared-tool-discovery.md` - updated from active bug
  note to resolved backlog with gold evidence.
- `docs/core-concepts/agent-readiness.md` - documented declared-tool materialization and approval
  resume semantics.
- `docs/handover/HANDOVER-2026-06-22-agent-run-declared-tools.md` - this handover.

## Pitfalls & Workarounds

1. **Custom tool discovery initially returned too little metadata**
   - **Root Cause**: `CustomToolProvider.discover()` did not select/map approval and schema columns.
   - **Solution**: Discover and map `input_schema`, `requires_approval`, and `risk_level`.
   - **Prevention**: Provider discovery tests now assert metadata, not just tool names.

2. **Approval resume re-entered the LLM and could drift**
   - **Root Cause**: pending approval state stored the approval id but not the exact tool call.
   - **Solution**: Store `approvalToolName` and `approvalInput` in the step output and replay them.
   - **Prevention**: Step-loop tests cover approval-required pause and approved-input resume.

3. **Seeded approver role drifted**
   - **Root Cause**: `scripts/seed-cs-agent.sql` used stale `TENANT_ADMIN` instead of `tenant_admin`.
   - **Solution**: Update seed and add reset-init contract coverage.
   - **Prevention**: `bash scripts/check-reset-init-contracts.sh` now covers this contract.

4. **SmartEngine 4.0.2 resolved from a partially synced mirror**
   - **Root Cause**: after 4.0.2 publication, Aliyun still lacked part of the artifact set and was
     listed before Maven Central.
   - **Solution**: add a content-filtered Maven Central repository for `com.auraboot.smart.framework`
     before Aliyun, and exclude that group from Aliyun repositories.
   - **Prevention**: `scripts/reset-init-contracts.test.mjs` now asserts this repository contract.

5. **Clean Gradle homes depended only on Gradle Plugin Portal for plugin markers**
   - **Root Cause**: `settings.gradle` had no `pluginManagement.repositories`, so a clean Gradle
     home used the default plugin portal only before project repositories were available.
   - **Solution**: add Maven Central before Gradle Plugin Portal in `pluginManagement`.
   - **Prevention**: `scripts/reset-init-contracts.test.mjs` now asserts this repository contract.

## Reflection & Codify

### 本会话弯路 / 返工 / 翻车

1. **先修了声明工具发现,但 live gold 又暴露 custom tool metadata 缺失** - 代价:多一轮
   live/单测定位 - 本可如何更早避免:provider discovery 测试一开始就断言 schema/approval/risk - 根因:
   `[A 门禁质量, D 验证纪律]`
2. **审批 resume 初版没有固定 approved input** - 代价:多一轮 StepLoop 重构和 RED/GREEN - 本可如何更早避免:
   把 approval 当作 durable checkpoint 设计,不是 transient exception - 根因:`[D 验证纪律]`
3. **SmartEngine 发布后仍让 Gradle 先碰残缺镜像** - 代价:CI rerun 后又红一次 - 本可如何更早避免:
   用干净 `GRADLE_USER_HOME` + 干净 `maven.repo.local` 复现,并检查各仓库 HTTP 状态 - 根因:
   `[A 门禁质量, D 验证纪律]`
4. **rebase 后干净 Gradle 又暴露 plugin marker 单点** - 代价:多一轮 settings.gradle 合约修复 -
   本可如何更早避免:干净 Gradle home 验证要覆盖 plugin resolution,不只看 project repositories - 根因:
   `[A 门禁质量, D 验证纪律]`

### 为什么会发生

本会话主要卡在 provider metadata 和 approval checkpoint 的测试粒度不够细。单纯发现 tool name
不等于运行时可安全执行,审批 pending 也不等于审批后能以同一语义继续。CI 侧还暴露了
仓库镜像部分同步时的 Gradle repository stickiness,不能只以本机缓存或单仓库成功解析判断清洁环境可用。

### 应该有哪些改进

- Provider discovery 相关改动必须断言完整工具描述符: name、schema、approval、risk。
- 审批/人工确认路径的测试必须覆盖 resume 后使用的精确输入,不能只测 pending 状态。
- Live gold 证据要同时查 action log 和业务表,避免只用 run status 当完成判据。
- Maven 依赖发布后,对 CI 阻塞要用干净 Gradle home / Maven local 做验证,并在镜像前后顺序敏感时加
  repository contract 测试。
- Gradle plugin marker resolution 属于 settings 层,不能靠 project `repositories` 修复;清洁环境验证要覆盖
  settings/pluginManagement。

### 已固化 / 待固化

- [x] 已写入 `docs/core-concepts/agent-readiness.md`: declared tools are materialized in both
  chat and dispatch/run paths, and approval resume replays approved input.
- [x] 已写入 `docs/backlog/2026-06-22-agent-run-path-declared-tool-discovery.md`: root cause,
  resolution, verification, and gold evidence.
- [x] 已写入 `docs/backlog/2026-06-22-ci-smartengine-402-mavenlocal.md`: clean CI cannot resolve
  SmartEngine 4.0.2 artifacts without routing the SmartEngine group to Maven Central before mirrors.
- [x] Fixed `markdownlint` MD025 config with `MD025: { front_matter_title: "" }`, so
  frontmatter title no longer counts as a duplicate H1 while the single-H1 rule remains active.

## 2026-06-23 Follow-up

- [x] Removed the temporary SmartEngine Maven-local install fallback after SmartEngine 4.0.2 was
  published to the remote Maven repository. Backend/codeql CI and `platform/Dockerfile` no longer
  call `install-smartengine-maven-local.sh`, and both fallback scripts were deleted.
- [x] Closed the declared-tool cap follow-up: declared `custom:` tools now load directly by exact
  `ab_agent_tool.tool_code`, and provider-prefixed declared tools fall back to provider-specific
  discovery instead of the globally capped aggregate catalog.
- [x] Hardened run-path planning prompts with a structured tool catalog and an explicit
  `platform.execute_sql` fallback-only rule so DeepSeek sees declared DSL/custom tools as the
  preferred path. This is covered by `PlanServiceTest`; no new live-model gold is claimed here.

## Operational State

### 分支 / Worktree / PR

- **当前分支**: `fix/agent-run-declared-tools`
- **相对 main**: rebased onto `origin/main`; use `git rev-list --left-right --count origin/main...HEAD`
  for the current ahead count after this handover update
- **Worktree**: `/Users/ghj/work/auraboot/auraboot-declfix`
- **PR**: #1021 OPEN, ready, base `main`, head branch `fix/agent-run-declared-tools`
- **Stash**: existing unrelated stashes remain untouched:
  `billing-meta-permission-constants`, `oss pre-existing changes`, `wip workbench backlog`
- **CI status**: local gates pass; clean-cache dependency resolution is fixed locally by preferring
  Maven Central for SmartEngine 4.0.2 and Gradle plugin markers. GitHub checks still need to run on
  the force-pushed rebased commit.

### Runtime / 端口

- **Live runtime used**: `cs-inbound-gold-77`, slot `77`, backend `6477`, DB `auraboot_77`
- **Current state**: runtime destroyed; `6477` has no listener
- **Host-first**: no Docker stack was used for this verification

### Verification Already Run

- `bash scripts/check-reset-init-contracts.sh`
- `node scripts/check-agent-eval-boundary.mjs`
- `bash scripts/check-oss-boundary.sh`
- `git diff --check`
- `cd platform && ./gradlew :test --tests com.auraboot.framework.agent.provider.CustomToolProviderTest --tests com.auraboot.framework.agent.service.StepLoopServiceLlmResponseGuardTest --tests com.auraboot.framework.agent.service.ToolLoopServiceSafetyTest --tests com.auraboot.framework.agent.service.DeclaredAgentToolResolverTest --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest --no-daemon`
- `cd platform && ./gradlew :test --tests com.auraboot.framework.agent.CustomerServiceAgentIntegrationTest --no-daemon`
- `cd platform && ./gradlew bootJar -x test --no-daemon`
- `cd platform && GRADLE_USER_HOME=/tmp/auraboot-smartengine-402-gradle-fix ./gradlew --no-daemon --refresh-dependencies clean compileJava -Dmaven.repo.local=/tmp/auraboot-smartengine-402-m2-fix`
- `cd platform && GRADLE_USER_HOME=/tmp/auraboot-smartengine-402-gradle-fix ./gradlew --no-daemon compileTestJava -Dmaven.repo.local=/tmp/auraboot-smartengine-402-m2-fix`
- `cd platform && ./gradlew --no-daemon compileJava compileTestJava compileIntegrationTestJava checkstyleMain pmdMain jar`
- `node --test scripts/reset-init-contracts.test.mjs` (24/24)

### Verification Limits

- `scripts/check-schema-sql.sh` could not run locally because this machine has no Docker binary.
- A post-rebase fresh `GRADLE_USER_HOME` / fresh `maven.repo.local` run got past plugin marker and
  SmartEngine resolution, then failed on transient TLS handshakes to Maven Central while fetching
  unrelated Spring Boot/AWS artifacts.

## Next Steps

1. Push the SmartEngine repository-order fix and watch #1021 checks on the new commit.
2. Merge #1021 after required checks/review are satisfied.
