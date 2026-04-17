# OSS BPM Closure Spec 1 — Implementation Plan (v2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Supersedes**: `2026-04-16-oss-bpm-closure-spec1-plan.md` (v1，部分已完成 commit 需回滚/重构)
>
> **Reference design**: `docs/superpowers/specs/2026-04-17-bpm-module-target-architecture-design.md`
>
> **⚠️ Task 9-16 已被前端 v2.1 取代**：本文件的 Task 9-16 基于错误的前端架构假设（`executionMode` 属性、直接 `/api/` fetch、ActionScheduler 注册），实际 OSS web-admin dispatcher 是 `useActionHandler.handleAction()`（`framework/meta/hooks/useActionHandler.ts:281`），按 `ActionDef.type` discriminated union 分派。**请按 [`2026-04-17-oss-bpm-closure-spec1-plan-v2-frontend.md`](./2026-04-17-oss-bpm-closure-spec1-plan-v2-frontend.md) 执行前端部分**。本文件的 Task 1-8（backend）仍然权威。

**Goal:** 补齐 OSS BPM 审批语义闭环（撤回 / 抄送 / Action `executionMode=bpm` / `bpm-panel` block），同时按目标架构清理 `BpmEngine` 抽象层并把业务策略迁到 BPMN `<smart:properties>` extension。

**Architecture:** 后端：BPM service 直接调 SmartEngine API；策略字段读 BPMN extension 而非 DB column；CC 委托给 SmartEngine `NotificationCommandService`；Action executor 是薄壳触发器。前端：新 `bpm-panel` block 注册到 `core-bpm` 插件，4 个 section 独立渲染；CC inbox 走 `/api/bpm/notifications`。

**Tech Stack:** Java 21 / Spring Boot 3 / MyBatis Plus / SmartEngine (Alibaba) / PostgreSQL 16 / React 18 / TypeScript / Vite / Playwright / AssertJ / JUnit 5

---

## v1 已完成 Commit 回滚 / 重构清单

| Commit | 作用 | v2 处置 |
|---|---|---|
| `69ed69f4` SmartEngine 部署修复 | Task 0 前置 | **保留**（不动） |
| `b5bc1c73` 部署测试加固 | Task 0 前置 | **保留** |
| `792356cf` schema 修正 cc_policy 列注释 | v1 Task 1 | **回滚**（连同列一起删，本 plan Task 8） |
| `9c8e3cd6` cc_record 表对齐 | v1 Task 1 | **回滚**（删表，本 plan Task 8） |
| `b162f170` Policy enum + entity 字段 | v1 Task 2 | **部分保留**（enum 留；entity 字段回滚，本 plan Task 7） |
| `bdfe9a73` WithdrawService 初版 | v1 Task 3 | **重构**（policy 读取改 accessor，本 plan Task 4） |
| `666dcd3b` BpmAuditOperation 重构 | v1 Task 3 后续 | **保留** |
| `c0aae028` CcService + entity + mapper | v1 Task 4-5 | **重写**（删 entity/mapper，rewrite service，本 plan Task 5/6） |
| `73a86b4e` CC i18n + assignee 分支 | v1 Task 5 后续 | **作废**（随 Task 5 重写） |
| `2a07e5b8` BpmActionExecutor 初版 | v1 Task 6 | **重写**（本 plan Task 9） |
| `24b26df5` HANDOVER 文档 | session 交接 | **保留** |
| `65d4f415` 目标架构 spec | 本 plan 依据 | **保留** |
| `05a6e3f8` smart:properties 修订 | 本 plan 依据 | **保留** |

---

## File Structure

### Backend 新增

```
platform/src/main/java/com/auraboot/framework/bpm/extension/
  BpmExtensionAccessor.java                # typed wrapper for getProperties()
  BpmExtensionKeys.java                    # 集中常量定义（aura.withdrawPolicy 等）

platform/src/test/java/com/auraboot/framework/bpm/extension/
  BpmExtensionAccessorTest.java            # 单元测试（不需 Spring）
```

### Backend 重写

```
platform/src/main/java/com/auraboot/framework/bpm/service/
  WithdrawService.java                     # policy 改 accessor，初始化器移除 def.getWithdrawPolicy
  CcService.java                           # 完全重写：薄壳调 SmartEngine NotificationService

platform/src/main/java/com/auraboot/framework/action/executor/
  BpmActionExecutor.java                   # 重写：用 ProcessEngineService + 严格 jsonpath + dedup 真查询

platform/src/test/java/com/auraboot/framework/bpm/
  TestBpmFixture.java                      # 移除 BpmEngine 字段，deployProcess 走真路径
  WithdrawServiceIntegrationTest.java      # 测试 BPMN fixture 改用 <smart:properties>
  CcServiceIntegrationTest.java            # 重写：断言 SmartEngine notification 而非 ccMapper

platform/src/test/java/com/auraboot/framework/action/
  BpmActionExecutorIntegrationTest.java    # 重写：deployProcess 走真路径 + 新增 jsonpath/blank 测试
```

### Backend 删除

```
platform/src/main/java/com/auraboot/framework/bpm/engine/
  BpmEngine.java
  BpmEngineFactory.java
  config/BpmAutoConfiguration.java
  config/BpmProperties.java
  adapter/SmartEngineBpmAdapter.java
  dto/{ProcessInstanceInfo, TaskInfo, HistoryRecord}.java
  exception/BpmEngineException.java

platform/src/main/java/com/auraboot/framework/bpm/entity/
  BpmCcRecord.java

platform/src/main/java/com/auraboot/framework/bpm/mapper/
  BpmCcRecordMapper.java

platform/src/test/java/com/auraboot/framework/bpm/engine/
  BpmEngineAbstractionTest.java
```

### Backend 修改

```
platform/src/main/resources/database/schema.sql                      # 删 ALTER 加列 + DROP cc_record
platform/src/main/java/com/auraboot/framework/plugin/entity/
  BpmProcessDefinition.java                                          # 删 withdrawPolicy/ccPolicy/requiredPermissions 字段
platform/src/main/java/com/auraboot/framework/bpm/service/
  ProcessDeploymentService.java                                      # 删除涉及 withdrawPolicy/ccPolicy/requiredPermissions 的方法/字段
```

### Frontend 新增

```
web-admin/app/plugins/core-bpm/blocks/bpm-panel/
  index.ts                                  # block 注册（kind/blockType/Component）
  BpmPanelBlock.tsx                         # 4 section 容器
  BpmStatusSection.tsx                      # 流程状态卡片
  BpmDiagramSection.tsx                     # BPMN canvas 高亮
  BpmOperationsSection.tsx                  # 操作按钮组
  BpmHistorySection.tsx                     # 审计/历史时间线
  WithdrawDialog.tsx                        # 撤回确认弹窗
  CcDialog.tsx                              # 抄送对象选择 + 留言

web-admin/app/plugins/core-bpm/services/
  BpmPermissionService.ts                   # 操作可见性判定

web-admin/app/plugins/core-bpm/api/
  bpmApi.ts                                 # withdraw / cc / inbox / status 调用封装

web-admin/app/plugins/core-designer/components/studio/registry/blocks/bpm-panel/
  action-schema.ts                          # action.bpm PropertySchema
  bpm-panel-schema.ts                       # block 配置面板 PropertySchema
```

### Frontend 修改

```
web-admin/app/shared/dsl/types.ts                                                     # ActionDef 加 executionMode/bpm
web-admin/app/shared/action/ActionExecutor.ts                                         # 加 executionMode=bpm 分支
web-admin/app/plugins/core-designer/components/studio/registry/blocks/index.ts        # 注册 bpmPanelBlock
```

---

## 任务执行顺序

```
Phase 0 — 基础设施（无依赖，可并行）
  Task 1: BpmExtensionAccessor + 单元测试
  Task 2: 删 BpmEngine 抽象层 + 修 TestBpmFixture
  Task 3: 升级 BPMN fixture 模板加 <smart:properties>

Phase 1 — Service 层重构（Phase 0 完成后）
  Task 4: WithdrawService 改用 accessor
  Task 5: CcService 重写（SmartEngine NotificationService）
  Task 6: BpmActionExecutor 重写（ProcessEngineService + dedup）

Phase 2 — 数据层清理（Phase 1 完成后）
  Task 7: BpmProcessDefinition entity 字段回滚
  Task 8: 删 BpmCcRecord entity/mapper + Schema 回滚

Phase 3 — 前端（Phase 1 完成后，与 Phase 2 并行）
  Task 9: ActionDef + ActionExecutor 分支
  Task 10: bpm-panel block 注册 + skeleton
  Task 11: BpmStatusSection
  Task 12: BpmDiagramSection
  Task 13: BpmOperationsSection + WithdrawDialog + CcDialog
  Task 14: BpmHistorySection
  Task 15: action.bpm PropertySchema

Phase 4 — 收尾
  Task 16: 文档同步 + 冒烟验证
```

---

## Common Setup

每个 Task 开始前确认：

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
pwd                                # 必须显示 .worktrees/bpm-closure-spec1
git branch --show-current          # 必须显示 bpm-closure-spec1
```

测试运行模板（保存日志）：

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
LOG=/tmp/pw-task$(date +%s).log
./gradlew test --tests <FullyQualifiedTestClass> \
  -x :platform-plugin-api:test -x :platform-storage-minio:test \
  -x :platform-storage-s3:test -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test -x :platform-mq-rabbitmq:test 2>&1 | tee "$LOG"
echo "Log: $LOG"
```

`./gradlew test` 末尾 `BUILD FAILED` 但单测全 PASSED 是 jacoco 报告失败，忽略——只看测试 PASS/FAIL 行。

---

## Task 1: BpmExtensionAccessor + Keys 常量 + 单元测试

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/bpm/extension/BpmExtensionKeys.java`
- Create: `platform/src/main/java/com/auraboot/framework/bpm/extension/BpmExtensionAccessor.java`
- Create: `platform/src/test/java/com/auraboot/framework/bpm/extension/BpmExtensionAccessorTest.java`

- [ ] **Step 1: 创建 BpmExtensionKeys 常量类**

```java
package com.auraboot.framework.bpm.extension;

/**
 * Canonical key names used inside <smart:properties> for AuraBoot business config.
 * All keys are prefixed with "aura." to avoid collision with SmartEngine's own
 * properties (e.g., task1InParam1).
 */
public final class BpmExtensionKeys {

    private BpmExtensionKeys() {}

    /** Process-level: WithdrawPolicy code (strict | loose | none). */
    public static final String WITHDRAW_POLICY = "aura.withdrawPolicy";

    /** Process-level: CcPolicy code (initiator | assignee | all). */
    public static final String CC_POLICY = "aura.ccPolicy";

    /** Node-level: form key reference (resolved by form repository). */
    public static final String FORM_KEY = "aura.formKey";

    /** Node-level: required permission codes (JSON array string). */
    public static final String REQUIRED_PERMISSIONS = "aura.requiredPermissions";

    /** Node-level: optional override of the process-level CcPolicy. */
    public static final String CC_POLICY_OVERRIDE = "aura.ccPolicyOverride";
}
```

- [ ] **Step 2: 创建 BpmExtensionAccessor 主类**

```java
package com.auraboot.framework.bpm.extension;

import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.assembly.ActivityDefinition;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Type-safe wrapper over SmartEngine's <smart:properties> extension parser.
 *
 * <p>SmartEngine parses <smart:properties> at deployment time and exposes the
 * result via {@code IdBasedElement.getProperties()} as {@code Map<String, String>}.
 * {@link com.auraboot.smart.framework.engine.service.query.RepositoryQueryService}
 * caches parsed definitions in-memory, so this accessor performs no IO and is
 * safe to call on hot paths.
 *
 * <p>All AuraBoot business config keys are namespaced with the "aura." prefix
 * (see {@link BpmExtensionKeys}).
 */
@Component
@RequiredArgsConstructor
public class BpmExtensionAccessor {

    private final SmartEngine smartEngine;

    /** Get raw process-level property by exact key, or empty when not set. */
    public Optional<String> getProcessProperty(String processKey, String key) {
        ProcessDefinition def = findProcessDefinition(processKey);
        if (def == null) return Optional.empty();
        return readProperty(def.getProperties(), key);
    }

    /** Get raw activity-level property, or empty when activity or property absent. */
    public Optional<String> getActivityProperty(String processKey, String activityId, String key) {
        ProcessDefinition def = findProcessDefinition(processKey);
        if (def == null) return Optional.empty();
        ActivityDefinition act = def.getActivityDefinitionMap() == null
                ? null : def.getActivityDefinitionMap().get(activityId);
        if (act == null) return Optional.empty();
        return readProperty(act.getProperties(), key);
    }

    /** Resolve effective WithdrawPolicy for the process, defaulting to STRICT. */
    public WithdrawPolicy getWithdrawPolicy(String processKey) {
        return getProcessProperty(processKey, BpmExtensionKeys.WITHDRAW_POLICY)
                .map(WithdrawPolicy::fromCode)
                .orElse(WithdrawPolicy.STRICT);
    }

    /**
     * Resolve effective CcPolicy: activity-level override (if any) takes
     * precedence over the process-level value; default is ALL.
     */
    public CcPolicy getCcPolicy(String processKey, String activityId) {
        if (activityId != null) {
            Optional<String> override = getActivityProperty(
                    processKey, activityId, BpmExtensionKeys.CC_POLICY_OVERRIDE);
            if (override.isPresent()) return CcPolicy.fromCode(override.get());
        }
        return getProcessProperty(processKey, BpmExtensionKeys.CC_POLICY)
                .map(CcPolicy::fromCode)
                .orElse(CcPolicy.ALL);
    }

    private ProcessDefinition findProcessDefinition(String processKey) {
        if (processKey == null || processKey.isBlank()) return null;
        return smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(d -> processKey.equals(d.getId()))
                .findFirst()
                .orElse(null);
    }

    private Optional<String> readProperty(Map<String, String> properties, String key) {
        if (properties == null) return Optional.empty();
        String value = properties.get(key);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
    }
}
```

- [ ] **Step 3: 写失败的单元测试**

```java
package com.auraboot.framework.bpm.extension;

import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.assembly.ActivityDefinition;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import com.auraboot.smart.framework.engine.service.query.RepositoryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BpmExtensionAccessor")
class BpmExtensionAccessorTest {

    private SmartEngine smartEngine;
    private RepositoryQueryService repo;
    private BpmExtensionAccessor accessor;
    private ProcessDefinition processDef;
    private ActivityDefinition userTask;

    @BeforeEach
    void setUp() {
        smartEngine = mock(SmartEngine.class);
        repo = mock(RepositoryQueryService.class);
        when(smartEngine.getRepositoryQueryService()).thenReturn(repo);
        accessor = new BpmExtensionAccessor(smartEngine);

        processDef = mock(ProcessDefinition.class);
        when(processDef.getId()).thenReturn("leave_request");

        userTask = mock(ActivityDefinition.class);
        Map<String, ActivityDefinition> activityMap = new HashMap<>();
        activityMap.put("manager_approval", userTask);
        when(processDef.getActivityDefinitionMap()).thenReturn(activityMap);

        when(repo.getAllCachedProcessDefinition()).thenReturn(List.of(processDef));
    }

    @Test
    @DisplayName("getWithdrawPolicy returns parsed value")
    void getWithdrawPolicyParsed() {
        when(processDef.getProperties()).thenReturn(Map.of("aura.withdrawPolicy", "loose"));
        assertThat(accessor.getWithdrawPolicy("leave_request")).isEqualTo(WithdrawPolicy.LOOSE);
    }

    @Test
    @DisplayName("getWithdrawPolicy defaults to STRICT when missing")
    void getWithdrawPolicyDefault() {
        when(processDef.getProperties()).thenReturn(Map.of());
        assertThat(accessor.getWithdrawPolicy("leave_request")).isEqualTo(WithdrawPolicy.STRICT);
    }

    @Test
    @DisplayName("getCcPolicy uses activity override when present")
    void getCcPolicyActivityOverride() {
        when(processDef.getProperties()).thenReturn(Map.of("aura.ccPolicy", "all"));
        when(userTask.getProperties()).thenReturn(Map.of("aura.ccPolicyOverride", "initiator"));
        assertThat(accessor.getCcPolicy("leave_request", "manager_approval"))
                .isEqualTo(CcPolicy.INITIATOR);
    }

    @Test
    @DisplayName("getCcPolicy falls back to process-level when no override")
    void getCcPolicyProcessLevel() {
        when(processDef.getProperties()).thenReturn(Map.of("aura.ccPolicy", "assignee"));
        when(userTask.getProperties()).thenReturn(Map.of());
        assertThat(accessor.getCcPolicy("leave_request", "manager_approval"))
                .isEqualTo(CcPolicy.ASSIGNEE);
    }

    @Test
    @DisplayName("getCcPolicy defaults to ALL when nothing set")
    void getCcPolicyDefault() {
        when(processDef.getProperties()).thenReturn(Map.of());
        when(userTask.getProperties()).thenReturn(Map.of());
        assertThat(accessor.getCcPolicy("leave_request", "manager_approval")).isEqualTo(CcPolicy.ALL);
    }

    @Test
    @DisplayName("unknown processKey returns defaults")
    void unknownProcessKey() {
        assertThat(accessor.getWithdrawPolicy("nonexistent")).isEqualTo(WithdrawPolicy.STRICT);
        assertThat(accessor.getCcPolicy("nonexistent", null)).isEqualTo(CcPolicy.ALL);
    }
}
```

- [ ] **Step 4: 运行单元测试，确认全 PASS**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
./gradlew test --tests com.auraboot.framework.bpm.extension.BpmExtensionAccessorTest 2>&1 | tee /tmp/pw-task1.log
```

Expected: `BpmExtensionAccessorTest > unknown processKey returns defaults PASSED` 等 6 个 PASSED。

- [ ] **Step 5: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/bpm/extension/BpmExtensionKeys.java \
        platform/src/main/java/com/auraboot/framework/bpm/extension/BpmExtensionAccessor.java \
        platform/src/test/java/com/auraboot/framework/bpm/extension/BpmExtensionAccessorTest.java
git commit -m "feat(bpm): typed accessor for <smart:properties> aura.* extensions"
```

---

## Task 2: 删除 BpmEngine 抽象层 + 修复 TestBpmFixture

**Files:**
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/engine/BpmEngine.java`
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/engine/BpmEngineFactory.java`
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/engine/adapter/SmartEngineBpmAdapter.java`
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/engine/config/BpmAutoConfiguration.java`
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/engine/config/BpmProperties.java`
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/engine/dto/ProcessInstanceInfo.java`
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/engine/dto/TaskInfo.java`
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/engine/dto/HistoryRecord.java`
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/engine/exception/BpmEngineException.java`
- Delete: `platform/src/test/java/com/auraboot/framework/bpm/engine/BpmEngineAbstractionTest.java`
- Modify: `platform/src/test/java/com/auraboot/framework/bpm/TestBpmFixture.java`

- [ ] **Step 1: 全仓 grep 确认 production 无其他依赖**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
grep -rn "import com.auraboot.framework.bpm.engine\." src/main/java | grep -v "framework/bpm/engine/" | grep -v "BpmActionExecutor"
```

Expected: 空输出（除了 BpmActionExecutor，它会在 Task 6 重写后不再依赖）。如有其他匹配，停止并 escalate。

- [ ] **Step 2: 全仓 grep 确认 test 无其他依赖**

```bash
grep -rn "import com.auraboot.framework.bpm.engine\." src/test/java | grep -v "engine/BpmEngineAbstractionTest"
```

Expected: 仅 `TestBpmFixture.java:6: import com.auraboot.framework.bpm.engine.BpmEngine;`（Task 6 之前 BpmActionExecutorIntegrationTest 也会被 Task 6 改）。

- [ ] **Step 3: 修改 TestBpmFixture 移除 BpmEngine 依赖**

读 `platform/src/test/java/com/auraboot/framework/bpm/TestBpmFixture.java`，做以下编辑：

(a) 删除 import 行：
```java
import com.auraboot.framework.bpm.engine.BpmEngine;
```

(b) 删除字段：
```java
private final BpmEngine bpmEngine;
```

(c) 重写 `deployProcess(String processKey)` 方法（line 113-117）为走真路径：

```java
/**
 * Deploy a minimal BPMN process under the exact given processKey (no suffix appended).
 * Uses ProcessDeploymentService — same path as production.
 *
 * @param processKey the exact process key to deploy (must be stable across duplicate checks)
 */
public void deployProcess(String processKey) {
    String bpmn = String.format(MINIMAL_BPMN_TEMPLATE, processKey);
    ProcessDeploymentService.CreateProcessRequest req =
            new ProcessDeploymentService.CreateProcessRequest(
                    processKey, "Test Action " + processKey, "Fixture process",
                    "test", bpmn, null, null, null);
    BpmProcessDefinition def = deploymentService.create(req);
    deploymentService.deploy(def.getPid());
    log.debug("TestBpmFixture.deployProcess: deployed key={}", processKey);
}
```

- [ ] **Step 4: 用 git rm 删除整个 engine 包**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
git rm -r platform/src/main/java/com/auraboot/framework/bpm/engine/
git rm platform/src/test/java/com/auraboot/framework/bpm/engine/BpmEngineAbstractionTest.java
git status --short | head -20
```

Expected: `D` 状态显示 9 个文件 + 1 个目录被删除。

- [ ] **Step 5: 编译确认无未解决引用**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
./gradlew compileJava compileTestJava 2>&1 | tee /tmp/pw-task2-compile.log | tail -30
```

Expected: `BUILD SUCCESSFUL`（仅 BpmActionExecutor.java 可能仍 import BpmEngine——它在 Task 6 重写时清理；本步骤暂时容忍它的编译错误，下面 Step 6 用临时 stub 解开）。

如果 BpmActionExecutor 有编译错误，临时把它改成 no-op stub（保留 class + Component，但 execute 直接抛 `UnsupportedOperationException("Refactoring in progress, see Task 6")`）以让编译通过：

```java
package com.auraboot.framework.action.executor;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class BpmActionExecutor {
    public boolean supports(String executionMode) { return "bpm".equalsIgnoreCase(executionMode); }
    public Object execute(Map<String, Object> actionDef, Map<String, Object> record) {
        throw new UnsupportedOperationException("Refactoring in progress, see Task 6");
    }
}
```

- [ ] **Step 6: 重新编译确认通过**

```bash
./gradlew compileJava compileTestJava 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add platform/src/test/java/com/auraboot/framework/bpm/TestBpmFixture.java \
        platform/src/main/java/com/auraboot/framework/action/executor/BpmActionExecutor.java
git commit -m "refactor(bpm): remove BpmEngine abstraction layer, use ProcessEngineService directly

The BpmEngine interface and SmartEngineBpmAdapter were an in-memory stub that
duplicated ProcessEngineService and misled subagents twice. Production code
had zero callers; the only consumers were test fixtures and the abstraction's
own tests. Single-source-of-truth is now ProcessEngineService.

TestBpmFixture.deployProcess now uses ProcessDeploymentService — the same
path used in production — instead of the in-memory stub.

BpmActionExecutor is temporarily stubbed; full implementation lands in Task 6."
```

---

## Task 3: 升级 TestBpmFixture BPMN 模板加 `<smart:properties>`

**Files:**
- Modify: `platform/src/test/java/com/auraboot/framework/bpm/TestBpmFixture.java` (BPMN template constant)

- [ ] **Step 1: 修改 MINIMAL_BPMN_TEMPLATE，加 namespace 和 properties**

替换 `TestBpmFixture.java` 中的 `MINIMAL_BPMN_TEMPLATE` 常量（约 line 42-61）为：

```java
/**
 * Minimal 2-step approval process: start → approval (userTask) → second_approval (userTask) → end.
 * Process and task have <smart:properties> with aura.* keys for policy testing.
 * Format args: %1$s = processKey, %2$s = withdrawPolicy code, %3$s = ccPolicy code.
 */
private static final String MINIMAL_BPMN_TEMPLATE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:smart="http://smartengine.org/schema/process"
                     targetNamespace="http://auraboot.com/bpm">
            <process id="%1$s" isExecutable="true">
                <extensionElements>
                    <smart:properties>
                        <smart:property name="aura.withdrawPolicy" value="%2$s"/>
                        <smart:property name="aura.ccPolicy" value="%3$s"/>
                    </smart:properties>
                </extensionElements>
                <startEvent id="start"/>
                <userTask id="approval" name="First Approval"
                          smart:assigneeType="user"
                          smart:assigneeId="system"/>
                <userTask id="second_approval" name="Second Approval"
                          smart:assigneeType="user"
                          smart:assigneeId="system"/>
                <endEvent id="end"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="approval"/>
                <sequenceFlow id="f2" sourceRef="approval" targetRef="second_approval"/>
                <sequenceFlow id="f3" sourceRef="second_approval" targetRef="end"/>
            </process>
        </definitions>
        """;
```

- [ ] **Step 2: 修改 `deployProcess(String processKey)` 注入默认 policy（strict + all）**

```java
public void deployProcess(String processKey) {
    String bpmn = String.format(MINIMAL_BPMN_TEMPLATE,
            processKey, WithdrawPolicy.STRICT.code(), CcPolicy.ALL.code());
    ProcessDeploymentService.CreateProcessRequest req =
            new ProcessDeploymentService.CreateProcessRequest(
                    processKey, "Test Action " + processKey, "Fixture process",
                    "test", bpmn, null, null, null);
    BpmProcessDefinition def = deploymentService.create(req);
    deploymentService.deploy(def.getPid());
    log.debug("TestBpmFixture.deployProcess: deployed key={}", processKey);
}
```

- [ ] **Step 3: 修改 `startProcessWithInitiator` 让 BPMN 嵌入 policy**

将 `startProcessWithInitiator` 中 `String bpmn = String.format(MINIMAL_BPMN_TEMPLATE, processKey);`（约 line 128）改为：

```java
String bpmn = String.format(MINIMAL_BPMN_TEMPLATE,
        processKey, withdrawPolicy.code(), ccPolicy.code());
```

并删除以下三行（Task 4 会让 WithdrawService 不再读 entity column；Task 8 会删 column）：

```java
// 删除 line 136-138：
def.setWithdrawPolicy(withdrawPolicy.code());
def.setCcPolicy(ccPolicy.code());
processDefinitionMapper.updateById(def);
```

- [ ] **Step 4: 编译确认通过**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
./gradlew compileTestJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add platform/src/test/java/com/auraboot/framework/bpm/TestBpmFixture.java
git commit -m "test(bpm): embed aura.* policies in fixture BPMN <smart:properties>

TestBpmFixture's MINIMAL_BPMN_TEMPLATE now declares withdrawPolicy/ccPolicy
under <process><extensionElements><smart:properties>. The startProcess and
deployProcess helpers inject the requested policy values into the template
at format time, eliminating the post-deploy entity column update."
```

---

## Task 4: WithdrawService 改用 BpmExtensionAccessor

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/bpm/service/WithdrawService.java`
- Modify: `platform/src/test/java/com/auraboot/framework/bpm/WithdrawServiceIntegrationTest.java` (compatibility check)

- [ ] **Step 1: 重写 WithdrawService**

完整覆盖 `WithdrawService.java`：

```java
package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.extension.BpmExtensionAccessor;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Handles process-instance withdrawal according to the process-level WithdrawPolicy
 * declared in BPMN <smart:properties> under aura.withdrawPolicy.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code strict} — initiator only, before ANY approve.</li>
 *   <li>{@code loose}  — initiator only, anytime while the instance is still running.</li>
 *   <li>{@code none}   — disabled; always rejected.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private final SmartEngine smartEngine;
    private final BpmExtensionAccessor extensionAccessor;
    private final BpmAuditService auditService;

    /**
     * Withdraw a running process instance identified by a current task.
     */
    @Transactional
    public void withdraw(String taskId, String reason) {
        String currentUserId = BpmSecurityUtil.getCurrentUserId();
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        // 1. Resolve task → process instance
        TaskInstance task = smartEngine.getTaskQueryService().findOne(taskId, tenantId);
        if (task == null) {
            throw new BusinessException("Task not found: " + taskId);
        }
        String processInstanceId = task.getProcessInstanceId();

        ProcessInstance processInstance =
                smartEngine.getProcessQueryService().findById(processInstanceId, tenantId);
        if (processInstance == null) {
            throw new BusinessException("Process instance not found: " + processInstanceId);
        }
        String processKey = processInstance.getProcessDefinitionId();

        // 2. Policy gate (read from BPMN extension; defaults to STRICT when absent)
        WithdrawPolicy policy = extensionAccessor.getWithdrawPolicy(processKey);
        if (policy == WithdrawPolicy.NONE) {
            throw new BusinessException("Withdraw is disabled for process: " + processKey);
        }

        // 3. Initiator check
        String initiatorId = processInstance.getStartUserId();
        if (initiatorId == null) {
            initiatorId = auditService.findByProcessInstance(processInstanceId)
                    .stream()
                    .filter(r -> BpmAuditOperation.PROCESS_START.matches(r.getOperation()))
                    .map(r -> r.getDetails() != null
                            ? (String) r.getDetails().get("startUserId") : null)
                    .filter(uid -> uid != null && !uid.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        if (initiatorId == null || !currentUserId.equals(initiatorId)) {
            throw new BusinessException("Only the initiator can withdraw this process");
        }

        // 4. STRICT: reject if any task previously approved
        if (policy == WithdrawPolicy.STRICT) {
            boolean anyApproved = auditService.findByProcessInstance(processInstanceId)
                    .stream()
                    .anyMatch(r -> BpmAuditOperation.TASK_APPROVE.matches(r.getOperation()));
            if (anyApproved) {
                throw new BusinessException(
                        "Process has already approved tasks; withdraw not allowed under strict policy");
            }
        }

        // 5. Abort via SmartEngine (terminate process)
        smartEngine.getProcessCommandService().abort(processInstanceId, "WITHDRAWN", tenantId);

        // 6. Audit
        auditService.auditProcessOperation(
                BpmAuditOperation.WITHDRAW.code(),
                processInstanceId,
                taskId,
                Map.of(
                        "reason", reason != null ? reason : "",
                        "policy", policy.code(),
                        "userId", currentUserId
                )
        );

        log.info("Process withdrawn: instanceId={}, processKey={}, by user={}, reason={}",
                processInstanceId, processKey, currentUserId, reason);
    }
}
```

- [ ] **Step 2: 重置数据库（schema 还有 v1 列，先重置确保从干净状态开始测试）**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
yes y | ../scripts/reset-db.sh 2>&1 | tail -5
```

Expected: `Database aura_boot recreated successfully` 或类似。

- [ ] **Step 3: 运行 WithdrawServiceIntegrationTest 确认全部通过**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
./gradlew test --tests com.auraboot.framework.bpm.WithdrawServiceIntegrationTest \
  -x :platform-plugin-api:test -x :platform-storage-minio:test \
  -x :platform-storage-s3:test -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test -x :platform-mq-rabbitmq:test 2>&1 | tee /tmp/pw-task4.log
grep -E "PASSED|FAILED" /tmp/pw-task4.log | head -10
```

Expected: 5 个 `WithdrawServiceIntegrationTest > ... PASSED`，无 FAILED。

- [ ] **Step 4: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/bpm/service/WithdrawService.java
git commit -m "refactor(bpm): WithdrawService reads policy from BPMN <smart:properties>

Replaces BpmProcessDefinitionMapper.findByProcessKey + def.getWithdrawPolicy()
with BpmExtensionAccessor.getWithdrawPolicy(processKey) — which reads
aura.withdrawPolicy from the BPMN extensionElements parsed by SmartEngine.

The WithdrawPolicy enum and BpmAuditOperation enum are unchanged; only the
policy resolution path moves from DB column to BPMN extension."
```

---

## Task 5: CcService 重写为 SmartEngine NotificationService 薄壳

**Files:**
- Rewrite: `platform/src/main/java/com/auraboot/framework/bpm/service/CcService.java`
- Rewrite: `platform/src/test/java/com/auraboot/framework/bpm/CcServiceIntegrationTest.java`
- Modify: `platform/src/main/java/com/auraboot/framework/bpm/controller/TaskController.java`（cc endpoint 返回类型）

- [ ] **Step 1: 重写 CcService**

完整覆盖 `CcService.java`：

```java
package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.extension.BpmExtensionAccessor;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.NotificationConstant;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Sends CC notifications for a BPM task.
 *
 * <p>Authorization is governed by the process-level CcPolicy declared in BPMN
 * <smart:properties> under aura.ccPolicy (initiator | assignee | all), with
 * an optional per-activity override under aura.ccPolicyOverride.
 *
 * <p>Storage and per-receiver fan-out is delegated to SmartEngine
 * NotificationCommandService. AuraBoot only writes a business-semantic audit
 * record ("I executed cc to N receivers") to ab_bpm_audit_record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcService {

    private final SmartEngine smartEngine;
    private final BpmExtensionAccessor extensionAccessor;
    private final BpmAuditService auditService;

    /**
     * Send a CC for the given task to the specified receiver user IDs.
     *
     * @param taskId          the active task ID
     * @param receiverUserIds receiver user IDs (numeric, must be non-empty)
     * @param comment         optional message body sent as notification content
     * @throws IllegalArgumentException if receiverUserIds is empty
     * @throws BusinessException        if the current user does not satisfy the CC policy
     */
    @Transactional
    public void cc(String taskId, List<Long> receiverUserIds, String comment) {
        if (receiverUserIds == null || receiverUserIds.isEmpty()) {
            throw new IllegalArgumentException("receiverUserIds must not be empty");
        }

        String currentUserId = BpmSecurityUtil.getCurrentUserId();
        Long currentUserIdLong = MetaContext.getCurrentUserId();
        String tenantIdStr = MetaContext.getCurrentTenantIdAsString();

        // 1. Resolve task → process instance + activity id
        TaskInstance task = smartEngine.getTaskQueryService().findOne(taskId, tenantIdStr);
        if (task == null) {
            throw new BusinessException("Task not found: " + taskId);
        }
        String processInstanceId = task.getProcessInstanceId();
        String activityId = task.getProcessDefinitionActivityId();

        ProcessInstance processInstance =
                smartEngine.getProcessQueryService().findById(processInstanceId, tenantIdStr);
        if (processInstance == null) {
            throw new BusinessException("Process instance not found: " + processInstanceId);
        }
        String processKey = processInstance.getProcessDefinitionId();

        // 2. Resolve CcPolicy (activity override > process default > ALL)
        CcPolicy policy = extensionAccessor.getCcPolicy(processKey, activityId);

        // 3. Identity gate
        String initiatorId = processInstance.getStartUserId();
        if (initiatorId == null) {
            initiatorId = auditService.findByProcessInstance(processInstanceId)
                    .stream()
                    .filter(r -> BpmAuditOperation.PROCESS_START.matches(r.getOperation()))
                    .map(r -> r.getDetails() != null
                            ? (String) r.getDetails().get("startUserId") : null)
                    .filter(uid -> uid != null && !uid.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        boolean isInitiator = currentUserId.equals(initiatorId);

        Long assigneeIdLong = parseLongSafely(task.getClaimUserId());
        boolean isAssignee = assigneeIdLong != null && assigneeIdLong.equals(currentUserIdLong);

        boolean allowed = switch (policy) {
            case INITIATOR -> isInitiator;
            case ASSIGNEE  -> isAssignee;
            case ALL       -> isInitiator || isAssignee;
        };
        if (!allowed) {
            throw new BusinessException(
                    "Current user does not satisfy cc policy: " + policy.code());
        }

        // 4. Delegate fan-out + storage + read tracking to SmartEngine.
        //    Use sendSingleNotification per receiver so we can set notification_type=cc
        //    (the bulk sendNotification overload does not accept a type parameter).
        for (Long receiverId : receiverUserIds) {
            smartEngine.getNotificationCommandService().sendSingleNotification(
                    processInstanceId,
                    taskId,
                    String.valueOf(currentUserIdLong),
                    String.valueOf(receiverId),
                    "$i18n:bpm.cc.inbox.title",
                    comment != null ? comment : "",
                    NotificationConstant.NotificationType.CC,
                    tenantIdStr);
        }

        // 5. Audit (AuraBoot business semantic)
        auditService.auditProcessOperation(
                BpmAuditOperation.CC.code(),
                processInstanceId,
                taskId,
                Map.of(
                        "receiverIds", receiverUserIds,
                        "comment", comment == null ? "" : comment,
                        "policy", policy.code()
                )
        );

        log.info("CC sent: instance={}, sender={}, receivers={}",
                processInstanceId, currentUserId, receiverUserIds);
    }

    private Long parseLongSafely(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }
}
```

- [ ] **Step 2: 重写 CcServiceIntegrationTest**

完整覆盖 `CcServiceIntegrationTest.java`：

```java
package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.NotificationConstant;
import com.auraboot.smart.framework.engine.model.instance.NotificationInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CcService (SmartEngine notification backend)")
class CcServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private CcService ccService;
    @Autowired private TestBpmFixture fixture;
    @Autowired private SmartEngine smartEngine;

    @Test
    @DisplayName("Policy=all, initiator sends cc: SmartEngine stores 2 notifications with type=cc")
    void allPolicyInitiatorCc() {
        var setup = fixture.startProcess("cc-all-initiator", CcPolicy.ALL);

        ccService.cc(setup.taskId(), List.of(501L, 502L), "please be aware");

        List<NotificationInstance> r501 = smartEngine.createNotificationQuery()
                .receiverUserId("501")
                .notificationType(NotificationConstant.NotificationType.CC)
                .listPage(0, 10);
        List<NotificationInstance> r502 = smartEngine.createNotificationQuery()
                .receiverUserId("502")
                .notificationType(NotificationConstant.NotificationType.CC)
                .listPage(0, 10);

        assertThat(r501).hasSize(1);
        assertThat(r501.get(0).getProcessInstanceId()).isEqualTo(setup.instanceId());
        assertThat(r501.get(0).getContent()).isEqualTo("please be aware");
        assertThat(r501.get(0).getReadStatus()).isEqualTo(NotificationConstant.ReadStatus.UNREAD);
        assertThat(r502).hasSize(1);
    }

    @Test
    @DisplayName("Policy=all, assignee sends cc: accepted")
    void allPolicyAssigneeCc() {
        var setup = fixture.startProcess("cc-all-assignee-pos", CcPolicy.ALL);

        // Claim the task as user 888 so task.claimUserId == "888"
        smartEngine.getTaskCommandService().claim(
                setup.taskId(), "888", MetaContext.getCurrentTenantIdAsString());

        // Switch current user to the task assignee
        fixture.switchCurrentUserTo(setup.assigneeId());

        ccService.cc(setup.taskId(), List.of(777L), "assignee-sends-cc");

        List<NotificationInstance> r777 = smartEngine.createNotificationQuery()
                .receiverUserId("777")
                .notificationType(NotificationConstant.NotificationType.CC)
                .listPage(0, 10);
        assertThat(r777).hasSize(1);
        // Sender id is the assignee (888L)
        assertThat(r777.get(0).getSenderUserId()).isEqualTo(String.valueOf(setup.assigneeId()));
        assertThat(r777.get(0).getTitle()).isEqualTo("$i18n:bpm.cc.inbox.title");
    }

    @Test
    @DisplayName("Policy=initiator, assignee attempts cc: rejected")
    void initiatorPolicyRejectsAssignee() {
        var setup = fixture.startProcess("cc-initiator-only", CcPolicy.INITIATOR);
        fixture.switchCurrentUserTo(setup.assigneeId());

        assertThatThrownBy(() -> ccService.cc(setup.taskId(), List.of(501L), "assignee cc attempt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("policy");
    }

    @Test
    @DisplayName("Policy=assignee, initiator attempts cc: rejected")
    void assigneePolicyRejectsInitiator() {
        var setup = fixture.startProcess("cc-assignee-only", CcPolicy.ASSIGNEE);
        // current user is initiator by default in fixture
        assertThatThrownBy(() -> ccService.cc(setup.taskId(), List.of(501L), "initiator cc attempt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("policy");
    }

    @Test
    @DisplayName("Empty receivers rejected with IllegalArgumentException")
    void emptyReceiversRejected() {
        var setup = fixture.startProcess("cc-empty", CcPolicy.ALL);
        assertThatThrownBy(() -> ccService.cc(setup.taskId(), List.of(), "nobody"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: 修改 TaskController.ccTask 适配新签名**

`CcService.cc` 现在 void 返回（旧版返回 BpmCcRecord）。修改 `TaskController.java` line 184-193 cc endpoint：

```java
@PostMapping("/{taskId}/cc")
@RequirePermission("BPM_TASK_CC")
@Operation(summary = "CC process",
           description = "Send a CC notification for the process to specified users; subject to ccPolicy")
public ApiResponse<Void> ccTask(
        @PathVariable String taskId,
        @RequestBody CcRequest request) {
    log.info("CC process: taskId={}, receivers={}", taskId, request.receiverUserIds());
    ccService.cc(taskId, request.receiverUserIds(), request.comment());
    return ApiResponse.success();
}
```

如果 `ApiResponse<Long>` 是单独的 import，删除（无其他用途）。

- [ ] **Step 4: 编译确认通过（CcService 已不依赖 BpmCcRecord/Mapper，但它们的文件仍存在 — Task 6 删除）**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
./gradlew compileJava compileTestJava 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: 运行 CcServiceIntegrationTest 全部通过**

```bash
./gradlew test --tests com.auraboot.framework.bpm.CcServiceIntegrationTest \
  -x :platform-plugin-api:test -x :platform-storage-minio:test \
  -x :platform-storage-s3:test -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test -x :platform-mq-rabbitmq:test 2>&1 | tee /tmp/pw-task5.log
grep -E "PASSED|FAILED" /tmp/pw-task5.log | head -10
```

Expected: 5 个 `CcServiceIntegrationTest > ... PASSED`，无 FAILED。

- [ ] **Step 6: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/bpm/service/CcService.java \
        platform/src/test/java/com/auraboot/framework/bpm/CcServiceIntegrationTest.java \
        platform/src/main/java/com/auraboot/framework/bpm/controller/TaskController.java
git commit -m "refactor(bpm): rewrite CcService over SmartEngine NotificationService

- Drop BpmCcRecord persistence; SmartEngine se_notification_instance is the
  single source of truth for CC fan-out, storage, and read tracking
- Per-receiver sendSingleNotification with type=cc replaces ab_bpm_cc_record
  + InboxItem dual write
- CcPolicy reads from BPMN extension (activity override > process default)
  via BpmExtensionAccessor
- Audit still writes one ab_bpm_audit_record per CC action with receiverIds
  and policy as the AuraBoot business semantic
- TaskController.ccTask returns Void (notification IDs are queryable via
  SmartEngine NotificationQuery)"
```

---

## Task 6: BpmActionExecutor 重写

**Files:**
- Rewrite: `platform/src/main/java/com/auraboot/framework/action/executor/BpmActionExecutor.java`
- Rewrite: `platform/src/test/java/com/auraboot/framework/action/BpmActionExecutorIntegrationTest.java`

- [ ] **Step 1: 重写 BpmActionExecutor**

完整覆盖 `BpmActionExecutor.java`：

```java
package com.auraboot.framework.action.executor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.InstanceStatus;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes action definitions whose {@code executionMode == "bpm"} by starting
 * a BPM process instance via {@link ProcessEngineService}. This is a thin trigger
 * shell — variable extraction + duplicate-business-key check + delegation. All
 * tenant injection, initiator wiring, audit, and form-binding-snapshot logic
 * lives in ProcessEngineService.
 *
 * <p>The accepted action.bpm config shape:
 * <pre>{@code
 * {
 *   "executionMode": "bpm",
 *   "bpm": {
 *     "processKey": "<required string>",
 *     "businessKeyField": "<required string — record field name>",
 *     "variables": { "varName": "$.recordField", ... }   // optional; jsonpath only
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmActionExecutor {

    private static final String EXECUTION_MODE_BPM = "bpm";

    private final ProcessEngineService processEngineService;
    private final SmartEngine smartEngine;

    @Transactional
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> actionDef, Map<String, Object> record) {
        Map<String, Object> bpmConfig = (Map<String, Object>) actionDef.get("bpm");
        if (bpmConfig == null) {
            throw new IllegalArgumentException("action.bpm config is required for executionMode=bpm");
        }

        String processKey = requireString(bpmConfig, "processKey");
        String businessKeyField = requireString(bpmConfig, "businessKeyField");

        Object businessKeyVal = record.get(businessKeyField);
        if (businessKeyVal == null) {
            throw new IllegalArgumentException(
                    "Record missing businessKeyField: " + businessKeyField);
        }
        String businessKey = String.valueOf(businessKeyVal);
        if (businessKey.isBlank()) {
            throw new IllegalArgumentException(
                    "businessKey resolved to blank for field: " + businessKeyField);
        }

        // Dedup: reject if an instance already exists for (processKey, businessKey) and is running.
        if (hasRunningInstance(processKey, businessKey)) {
            throw new BusinessException(
                    "A process instance already exists for businessKey=" + businessKey);
        }

        // Extract variables via simple JSONPath traversal. Only "$.field[.subfield]" is supported.
        Map<String, Object> variables = new HashMap<>();
        Object varsConfig = bpmConfig.get("variables");
        if (varsConfig instanceof Map<?, ?> varMap) {
            varMap.forEach((k, v) -> {
                Object extracted = extractVariable(record, String.valueOf(v));
                if (extracted != null) {
                    variables.put(String.valueOf(k), extracted);
                }
            });
        }

        ProcessInstance instance = processEngineService.startProcess(processKey, businessKey, variables);
        log.info("Started process via action executor: processKey={}, businessKey={}, instanceId={}",
                processKey, businessKey, instance.getInstanceId());

        return Map.of(
                "processInstanceId", instance.getInstanceId(),
                "processKey", processKey,
                "businessKey", businessKey);
    }

    public boolean supports(String executionMode) {
        return EXECUTION_MODE_BPM.equalsIgnoreCase(executionMode);
    }

    /** Return true when a non-completed process instance exists for the (processKey, businessKey). */
    private boolean hasRunningInstance(String processKey, String businessKey) {
        ProcessInstanceQueryParam param = new ProcessInstanceQueryParam();
        param.setTenantId(MetaContext.getCurrentTenantIdAsString());
        param.setBizUniqueId(businessKey);
        List<ProcessInstance> instances = smartEngine.getProcessQueryService().findList(param);
        if (instances == null) return false;
        return instances.stream()
                .filter(i -> processKey.equals(i.getProcessDefinitionId()))
                .anyMatch(i -> InstanceStatus.running == i.getStatus() && !i.isSuspend());
    }

    private String requireString(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("action.bpm." + key + " is required");
        }
        return String.valueOf(v);
    }

    /**
     * Extract a value from the record using a strict path expression.
     *
     * <p>Supported:
     * <ul>
     *   <li>{@code $.field}</li>
     *   <li>{@code $.parent.child}</li>
     *   <li>Any non-{@code $}-prefixed value — treated as literal</li>
     * </ul>
     *
     * <p>Rejected (no silent fallback per project red line):
     * <ul>
     *   <li>Bracket syntax: {@code $.list[0]}, {@code $..filter[*]}</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Object extractVariable(Map<String, Object> record, String path) {
        if (path == null) return null;
        if (!path.startsWith("$")) {
            // Literal value
            return path;
        }
        if (path.indexOf('[') >= 0) {
            throw new IllegalArgumentException(
                    "JSONPath bracket syntax not supported: " + path
                    + " — use simple dot paths only ($.field or $.parent.child)");
        }
        String stripped = path.startsWith("$.") ? path.substring(2) : path.substring(1);
        if (stripped.isBlank()) {
            return null;
        }
        Object current = record;
        for (String part : stripped.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    /** Return Optional wrapping execute() result for callers preferring optional access. */
    public Optional<Object> tryExecute(Map<String, Object> actionDef, Map<String, Object> record) {
        return Optional.ofNullable(execute(actionDef, record));
    }
}
```

注：`tryExecute` 是为可能的 dispatcher 收口预留的可选 API。如果 review 觉得 YAGNI 可以删掉。

- [ ] **Step 2: 重写 BpmActionExecutorIntegrationTest**

完整覆盖 `platform/src/test/java/com/auraboot/framework/action/BpmActionExecutorIntegrationTest.java`：

```java
package com.auraboot.framework.action;

import com.auraboot.framework.action.executor.BpmActionExecutor;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.TestBpmFixture;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BpmActionExecutor (real SmartEngine path)")
class BpmActionExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired private BpmActionExecutor executor;
    @Autowired private TestBpmFixture fixture;
    @Autowired private SmartEngine smartEngine;

    @Test
    @DisplayName("executionMode=bpm starts process via real SmartEngine and returns instance id")
    void executionModeBpmStartsProcess() {
        fixture.deployProcess("executor-demo");
        Map<String, Object> actionDef = Map.of(
                "code", "submit_demo",
                "executionMode", "bpm",
                "bpm", Map.of(
                        "processKey", "executor-demo",
                        "businessKeyField", "id",
                        "variables", Map.of("amount", "$.amount")));
        Map<String, Object> record = Map.of("id", "rec-001", "amount", 100);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) executor.execute(actionDef, record);

        String instanceId = (String) result.get("processInstanceId");
        assertThat(instanceId).isNotBlank();

        // Verify the instance is observable via real SmartEngine
        ProcessInstanceQueryParam param = new ProcessInstanceQueryParam();
        param.setTenantId(MetaContext.getCurrentTenantIdAsString());
        param.setBizUniqueId("rec-001");
        List<ProcessInstance> live = smartEngine.getProcessQueryService().findList(param);
        assertThat(live).isNotEmpty();
        assertThat(live.get(0).getInstanceId()).isEqualTo(instanceId);
    }

    @Test
    @DisplayName("rejects duplicate businessKey (running instance check via SmartEngine)")
    void rejectsDuplicateBusinessKey() {
        fixture.deployProcess("executor-dedup");
        Map<String, Object> actionDef = Map.of(
                "code", "submit",
                "executionMode", "bpm",
                "bpm", Map.of("processKey", "executor-dedup", "businessKeyField", "id"));
        Map<String, Object> record = Map.of("id", "rec-dup-1");

        executor.execute(actionDef, record);
        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("supports() returns true for executionMode=bpm")
    void supportsDetectsBpmMode() {
        assertThat(executor.supports("bpm")).isTrue();
        assertThat(executor.supports("BPM")).isTrue();
        assertThat(executor.supports("command")).isFalse();
    }

    @Test
    @DisplayName("rejects bracket-style JSONPath (no silent fallback)")
    void rejectsComplexJsonPath() {
        fixture.deployProcess("executor-jsonpath");
        Map<String, Object> actionDef = Map.of(
                "executionMode", "bpm",
                "bpm", Map.of(
                        "processKey", "executor-jsonpath",
                        "businessKeyField", "id",
                        "variables", Map.of("first", "$.items[0]")));
        Map<String, Object> record = Map.of("id", "rec-jp-1", "items", List.of("a", "b"));

        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bracket syntax");
    }

    @Test
    @DisplayName("rejects blank businessKey value")
    void rejectsBlankBusinessKey() {
        fixture.deployProcess("executor-blank-key");
        Map<String, Object> actionDef = Map.of(
                "executionMode", "bpm",
                "bpm", Map.of("processKey", "executor-blank-key", "businessKeyField", "id"));
        Map<String, Object> record = Map.of("id", "   ");

        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("missing action.bpm rejected")
    void missingBpmConfigRejected() {
        Map<String, Object> actionDef = Map.of("executionMode", "bpm");
        Map<String, Object> record = Map.of("id", "rec-x");
        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action.bpm");
    }
}
```

- [ ] **Step 3: 编译 + 运行测试**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
./gradlew test --tests com.auraboot.framework.action.BpmActionExecutorIntegrationTest \
  -x :platform-plugin-api:test -x :platform-storage-minio:test \
  -x :platform-storage-s3:test -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test -x :platform-mq-rabbitmq:test 2>&1 | tee /tmp/pw-task6.log
grep -E "PASSED|FAILED" /tmp/pw-task6.log | head -10
```

Expected: 6 个 `BpmActionExecutorIntegrationTest > ... PASSED`，无 FAILED。

- [ ] **Step 4: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/action/executor/BpmActionExecutor.java \
        platform/src/test/java/com/auraboot/framework/action/BpmActionExecutorIntegrationTest.java
git commit -m "feat(action): BpmActionExecutor uses ProcessEngineService + real SmartEngine dedup

- Drops BpmEngine abstraction; calls ProcessEngineService.startProcess
  (canonical path with tenant/initiator/businessKey/audit/form-binding wiring)
- Duplicate businessKey check queries real SmartEngine via
  smartEngine.getProcessQueryService().findList(bizUniqueId)
- Strict JSONPath: rejects bracket syntax instead of silently returning null
- Blank businessKey value guarded with IllegalArgumentException
- Tests verify the started instance is observable via real SmartEngine,
  not just the executor's return value"
```

---

## Task 7: 回滚 BpmProcessDefinition entity 业务字段

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/plugin/entity/BpmProcessDefinition.java`
- Modify: `platform/src/main/java/com/auraboot/framework/bpm/service/ProcessDeploymentService.java`（删除涉及这 3 字段的方法/赋值）
- Modify: `platform/src/main/java/com/auraboot/framework/bpm/controller/ProcessDefinitionController.java`（删除请求 record 中的 3 字段）
- Modify: `platform/src/main/java/com/auraboot/framework/plugin/dto/imports/ProcessDefinitionDTO.java`（删除 3 字段）
- Modify: `platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java`（删除 3 字段赋值）
- Modify: `platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginImportServiceImpl.java`（删除 requiredPermissions 引用）

- [ ] **Step 1: 全仓 grep 找出所有 getter/setter 引用**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
grep -rn "getWithdrawPolicy\|setWithdrawPolicy\|getCcPolicy\|setCcPolicy\|getRequiredPermissions\|setRequiredPermissions\|withdrawPolicy\|ccPolicy\|requiredPermissions" \
  src/main/java --include="*.java" | grep -v "ApprovalChainExecutor\|RequirePermission" | head -40
```

记录所有需要修改的文件位置。

- [ ] **Step 2: 修改 BpmProcessDefinition 实体类**

打开 `platform/src/main/java/com/auraboot/framework/plugin/entity/BpmProcessDefinition.java`，删除以下字段（连同对应的 `@TableField` 注解）：

```java
@TableField("withdraw_policy")
private String withdrawPolicy;

@TableField("cc_policy")
private String ccPolicy;

@TableField(value = "required_permissions", typeHandler = ...)
private Map<String, Object> requiredPermissions;
```

- [ ] **Step 3: 修改 ProcessDefinitionDTO 删除 3 字段**

打开 `platform/src/main/java/com/auraboot/framework/plugin/dto/imports/ProcessDefinitionDTO.java`，删除以下字段：

```java
private String withdrawPolicy;
private String ccPolicy;
private Map<String, Object> requiredPermissions;
```

- [ ] **Step 4: 修改 PluginResourceImporterImpl 删除赋值**

`PluginResourceImporterImpl.java` 中 `BpmProcessDefinition.builder()` 链中删除：

```java
.withdrawPolicy(...)
.ccPolicy(...)
.requiredPermissions(...)
```

- [ ] **Step 5: 修改 PluginImportServiceImpl 删除 requiredPermissions 引用**

`PluginImportServiceImpl.java:1152` 那一行（`.requiredPermissions(extended.getRequiredPermissions())`）整行删除。

- [ ] **Step 6: 修改 ProcessDeploymentService 删除涉及 3 字段的逻辑**

打开 `ProcessDeploymentService.java`，找出所有 `withdrawPolicy` / `ccPolicy` / `requiredPermissions` 出现位置（`.builder()` 调用、`if (request.xxx() != null)` 块、`existing.setXxx(...)` 等），删除整段。

- [ ] **Step 7: 修改 ProcessDefinitionController 请求/响应 record**

`ProcessDefinitionController.java` 中 CreateProcessRequest / UpdateProcessRequest record（如有 withdrawPolicy/ccPolicy/requiredPermissions 参数），删除这些参数；调整调用方。

- [ ] **Step 8: 编译确认通过**

```bash
./gradlew compileJava compileTestJava 2>&1 | tee /tmp/pw-task7-compile.log | tail -30
```

Expected: `BUILD SUCCESSFUL`. 如果有未解决的引用，根据错误位置补 grep 删除。

- [ ] **Step 9: 跑相关测试套件确认无 regression**

```bash
./gradlew test --tests "com.auraboot.framework.bpm.*" \
  --tests "com.auraboot.framework.action.BpmActionExecutorIntegrationTest" \
  --tests "com.auraboot.framework.plugin.PluginProcessImportDeploymentTest" \
  -x :platform-plugin-api:test -x :platform-storage-minio:test \
  -x :platform-storage-s3:test -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test -x :platform-mq-rabbitmq:test 2>&1 | tee /tmp/pw-task7.log
grep -cE "PASSED|FAILED" /tmp/pw-task7.log
grep "FAILED" /tmp/pw-task7.log | head -10
```

Expected: 全部 PASSED 无 FAILED。如有 FAILED，定位并修。

- [ ] **Step 10: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/plugin/entity/BpmProcessDefinition.java \
        platform/src/main/java/com/auraboot/framework/plugin/dto/imports/ProcessDefinitionDTO.java \
        platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java \
        platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginImportServiceImpl.java \
        platform/src/main/java/com/auraboot/framework/bpm/service/ProcessDeploymentService.java \
        platform/src/main/java/com/auraboot/framework/bpm/controller/ProcessDefinitionController.java
git commit -m "refactor(bpm): drop withdrawPolicy/ccPolicy/requiredPermissions from entity

Business policies now live in BPMN <smart:properties> under aura.* keys and
are read at runtime via BpmExtensionAccessor. The entity columns were a
short-lived v1 design that doubled as DB cache; eliminating them removes
the dual-source-of-truth risk."
```

---

## Task 8: 删除 BpmCcRecord 实体/Mapper + Schema 回滚

**Files:**
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/entity/BpmCcRecord.java`
- Delete: `platform/src/main/java/com/auraboot/framework/bpm/mapper/BpmCcRecordMapper.java`
- Modify: `platform/src/main/resources/database/schema.sql`

- [ ] **Step 1: 全仓 grep 确认 entity/mapper 无消费者**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
grep -rn "BpmCcRecord\|BpmCcRecordMapper" src/main/java src/test/java --include="*.java" | grep -v "framework/bpm/entity/BpmCcRecord\|framework/bpm/mapper/BpmCcRecordMapper"
```

Expected: 空输出（CcService 已在 Task 5 重写，不再引用）。

- [ ] **Step 2: git rm 删除 entity 和 mapper**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
git rm platform/src/main/java/com/auraboot/framework/bpm/entity/BpmCcRecord.java
git rm platform/src/main/java/com/auraboot/framework/bpm/mapper/BpmCcRecordMapper.java
```

- [ ] **Step 3: 编辑 schema.sql 删除 ALTER 加 3 列 + DROP cc_record 表**

打开 `platform/src/main/resources/database/schema.sql`，找到 line ~2528：

```sql
-- Approval withdrawal and cc policies
ALTER TABLE ab_bpm_process_definition
    ADD COLUMN IF NOT EXISTS withdraw_policy VARCHAR(20) NOT NULL DEFAULT 'strict',
    ADD COLUMN IF NOT EXISTS cc_policy VARCHAR(20) NOT NULL DEFAULT 'all',
    ADD COLUMN IF NOT EXISTS required_permissions JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN ab_bpm_process_definition.withdraw_policy IS '...';
COMMENT ON COLUMN ab_bpm_process_definition.cc_policy IS '...';
COMMENT ON COLUMN ab_bpm_process_definition.required_permissions IS '...';
```

**整段删除**。

然后找到 line ~3767：

```sql
-- ================================================================
-- BPM CC Record — 抄送记录表
-- 每条记录代表一次"抄送行为"，承载 sender/receivers/comment/read_state
-- ================================================================
CREATE TABLE IF NOT EXISTS ab_bpm_cc_record (
    ...
);
DROP INDEX IF EXISTS idx_cc_process_instance;
... (3 indexes)
CREATE INDEX IF NOT EXISTS idx_bpm_cc_process_instance ...;
... (3 partial indexes)
```

**整段删除**（从注释开始到最后一个 CREATE INDEX 结束，约 25 行）。

- [ ] **Step 4: 重置数据库验证 schema 干净**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
yes y | ../scripts/reset-db.sh 2>&1 | tail -5
```

Expected: `Database aura_boot recreated successfully`.

确认 3 列和表都不在了：

```bash
psql -h localhost -U ghj -d aura_boot -P pager=off -c "\d ab_bpm_process_definition" | grep -E "withdraw|cc_policy|required_permissions" || echo "OK: columns removed"
psql -h localhost -U ghj -d aura_boot -P pager=off -c "\d ab_bpm_cc_record" 2>&1 | grep -E "Did not find" || echo "TABLE STILL EXISTS"
```

Expected: `OK: columns removed` + `Did not find any relation named "ab_bpm_cc_record"`.

- [ ] **Step 5: 全套 BPM 测试回归**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/platform
./gradlew test --tests "com.auraboot.framework.bpm.*" \
  --tests "com.auraboot.framework.action.BpmActionExecutorIntegrationTest" \
  --tests "com.auraboot.framework.plugin.PluginProcessImportDeploymentTest" \
  -x :platform-plugin-api:test -x :platform-storage-minio:test \
  -x :platform-storage-s3:test -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test -x :platform-mq-rabbitmq:test 2>&1 | tee /tmp/pw-task8.log
grep "FAILED" /tmp/pw-task8.log | head -10 || echo "ALL PASSED"
```

Expected: `ALL PASSED`.

- [ ] **Step 6: Commit**

```bash
git add platform/src/main/resources/database/schema.sql
git commit -m "refactor(bpm): rollback v1 cc-record table and policy ALTER columns

Schema returns to pre-spec-1 shape for ab_bpm_process_definition (no
withdraw_policy/cc_policy/required_permissions columns) and ab_bpm_cc_record
is removed entirely. Business policies live in BPMN <smart:properties>;
CC fan-out lives in se_notification_instance via SmartEngine
NotificationCommandService."
```

---

## Task 9: 前端 ActionDef 类型扩展 + ActionExecutor 分支

**Files:**
- Modify: `web-admin/app/shared/dsl/types.ts`
- Modify: `web-admin/app/shared/action/ActionExecutor.ts`（或等价 dispatch 入口）
- Create: `web-admin/app/shared/dsl/__tests__/action-bpm.test.ts`

- [ ] **Step 1: 修改 ActionDef 类型**

打开 `web-admin/app/shared/dsl/types.ts`，在 `ActionDef` interface 中加：

```typescript
export type ActionExecutionMode = 'command' | 'bpm';

export interface ActionBpmConfig {
  /** Process definition key (matches BPMN <process id="...">) */
  processKey: string;
  /** Field name on the source record providing the businessKey */
  businessKeyField: string;
  /** Variable name → JSONPath mapping; only "$.field[.sub]" supported */
  variables?: Record<string, string>;
}

export interface ActionDef {
  // ... existing fields ...
  executionMode?: ActionExecutionMode;
  /** Required when executionMode === 'bpm' */
  bpm?: ActionBpmConfig;
}
```

- [ ] **Step 2: 找到 dispatcher 实现**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/web-admin
grep -rn "executeAction\|dispatchAction\|ActionExecutor" app/shared/action/ app/shared/dsl/ 2>&1 | head -10
```

记录实际的 dispatch 函数路径（设其为 `app/shared/action/ActionExecutor.ts` 中的 `dispatchAction`，下文以此为准）。

- [ ] **Step 3: 在 dispatcher 中加 executionMode=bpm 分支**

在 `dispatchAction(action, record)` 函数顶部（在原 command 分支之前）加：

```typescript
import { ActionDef } from '../dsl/types';

export async function dispatchAction(
  action: ActionDef,
  record: Record<string, unknown>
): Promise<{ processInstanceId?: string; result?: unknown }> {
  if (action.executionMode === 'bpm') {
    if (!action.bpm) {
      throw new Error(
        `action.bpm config required when executionMode=bpm: ${action.code}`);
    }
    const { processKey, businessKeyField, variables } = action.bpm;
    const businessKey = record[businessKeyField];
    if (businessKey === undefined || businessKey === null
        || String(businessKey).trim() === '') {
      throw new Error(
        `Record missing or blank businessKeyField: ${businessKeyField}`);
    }
    // Resolve variables via JSONPath ($.field) — frontend mirrors backend semantic
    const resolved: Record<string, unknown> = {};
    if (variables) {
      for (const [k, expr] of Object.entries(variables)) {
        if (typeof expr !== 'string') continue;
        if (expr.startsWith('$.')) {
          if (expr.includes('[')) {
            throw new Error(`JSONPath bracket syntax not supported: ${expr}`);
          }
          let cursor: unknown = record;
          for (const part of expr.slice(2).split('.')) {
            if (cursor && typeof cursor === 'object' && part in cursor) {
              cursor = (cursor as Record<string, unknown>)[part];
            } else { cursor = undefined; break; }
          }
          if (cursor !== undefined) resolved[k] = cursor;
        } else {
          resolved[k] = expr;  // literal
        }
      }
    }
    const response = await fetch('/api/bpm/process-instances', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ processKey, businessKey: String(businessKey), variables: resolved }),
    });
    const json = await response.json();
    if (!response.ok || json.code !== 0) {
      throw new Error(json.message ?? `BPM dispatch failed: ${response.status}`);
    }
    return { processInstanceId: json.data?.instanceId ?? json.data?.processInstanceId };
  }

  // ... existing command branch unchanged ...
}
```

- [ ] **Step 4: 新增前端单元测试**

创建 `web-admin/app/shared/dsl/__tests__/action-bpm.test.ts`：

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { dispatchAction } from '../../action/ActionExecutor';
import { ActionDef } from '../types';

describe('action.executionMode=bpm', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('POSTs /api/bpm/process-instances with extracted variables', async () => {
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ code: 0, data: { processInstanceId: 'pi-1' } }),
                   { status: 200 }));

    const action: ActionDef = {
      code: 'submit', label: 'submit',
      executionMode: 'bpm',
      bpm: {
        processKey: 'leave_request',
        businessKeyField: 'id',
        variables: { amount: '$.amount' },
      },
    };
    const result = await dispatchAction(action, { id: 'rec-1', amount: 100 });

    expect(fetchSpy).toHaveBeenCalledWith('/api/bpm/process-instances',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          processKey: 'leave_request',
          businessKey: 'rec-1',
          variables: { amount: 100 },
        }),
      }));
    expect(result.processInstanceId).toBe('pi-1');
  });

  it('throws when action.bpm is missing', async () => {
    await expect(dispatchAction(
      { code: 'x', label: 'x', executionMode: 'bpm' } as ActionDef,
      {})).rejects.toThrow(/bpm config/);
  });

  it('throws when businessKeyField value blank', async () => {
    await expect(dispatchAction(
      { code: 'x', label: 'x', executionMode: 'bpm',
        bpm: { processKey: 'p', businessKeyField: 'id' } } as ActionDef,
      { id: '   ' })).rejects.toThrow(/blank/);
  });

  it('throws on bracket JSONPath', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(new Response('{}', { status: 200 }));
    await expect(dispatchAction(
      { code: 'x', label: 'x', executionMode: 'bpm',
        bpm: { processKey: 'p', businessKeyField: 'id',
               variables: { first: '$.items[0]' } } } as ActionDef,
      { id: 'rec-1', items: ['a', 'b'] })).rejects.toThrow(/bracket/);
  });
});
```

- [ ] **Step 5: 运行 vitest**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/web-admin
pnpm vitest run app/shared/dsl/__tests__/action-bpm.test.ts 2>&1 | tee /tmp/pw-task9.log | tail -30
```

Expected: 4 个 test PASSED.

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/shared/dsl/types.ts \
        web-admin/app/shared/action/ActionExecutor.ts \
        web-admin/app/shared/dsl/__tests__/action-bpm.test.ts
git commit -m "feat(web): ActionDef supports executionMode=bpm via /api/bpm/process-instances

- Add ActionExecutionMode union and ActionBpmConfig interface
- Dispatcher routes executionMode=bpm to POST /api/bpm/process-instances
  with extracted variables (mirror backend JSONPath semantic)
- Reject blank businessKey and bracket JSONPath; no silent fallback"
```

---

## Task 10: bpm-panel block 注册 + Skeleton

**Files:**
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/index.ts`
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx`
- Create: `web-admin/app/plugins/core-bpm/api/bpmApi.ts`
- Modify: `web-admin/app/plugins/core-designer/components/studio/registry/blocks/index.ts`

- [ ] **Step 1: 创建 BPM API 封装**

```typescript
// web-admin/app/plugins/core-bpm/api/bpmApi.ts
const NO_PROXY_BASE = ''; // dev/BFF prefix is configured upstream

export interface BpmStatusResponse {
  processInstanceId: string;
  status: 'running' | 'completed' | 'terminated' | 'suspended' | 'unknown';
  currentNodes: Array<{ nodeId: string; nodeName?: string; assignee?: string }>;
  completedNodes: Array<{ nodeId: string; nodeName?: string; completedAt?: string }>;
  variables: Record<string, unknown>;
}

export async function getProcessStatus(
  processKey: string, businessKey: string,
): Promise<BpmStatusResponse | null> {
  const r = await fetch(
    `${NO_PROXY_BASE}/api/bpm/process-instances/by-business-key`
    + `?processKey=${encodeURIComponent(processKey)}&businessKey=${encodeURIComponent(businessKey)}`);
  const j = await r.json();
  if (j.code !== 0) return null;
  return j.data ?? null;
}

export async function withdrawProcess(taskId: string, reason: string): Promise<void> {
  const r = await fetch(`${NO_PROXY_BASE}/api/bpm/tasks/${taskId}/withdraw`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  });
  const j = await r.json();
  if (j.code !== 0) throw new Error(j.message ?? 'withdraw failed');
}

export async function ccProcess(
  taskId: string, receiverUserIds: number[], comment: string,
): Promise<void> {
  const r = await fetch(`${NO_PROXY_BASE}/api/bpm/tasks/${taskId}/cc`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ receiverUserIds, comment }),
  });
  const j = await r.json();
  if (j.code !== 0) throw new Error(j.message ?? 'cc failed');
}

export interface CcInboxItem {
  id: string;
  processInstanceId: string;
  senderUserId: string;
  title: string;
  content: string;
  readStatus: 'unread' | 'read';
  createdAt: string;
}

export async function listMyCcInbox(
  unreadOnly: boolean, page: number, size: number,
): Promise<CcInboxItem[]> {
  const params = new URLSearchParams({
    unreadOnly: String(unreadOnly), page: String(page), size: String(size),
  });
  const r = await fetch(`${NO_PROXY_BASE}/api/bpm/notifications/inbox?${params}`);
  const j = await r.json();
  if (j.code !== 0) return [];
  return j.data ?? [];
}

export async function markCcAsRead(notificationId: string): Promise<void> {
  await fetch(`${NO_PROXY_BASE}/api/bpm/notifications/${notificationId}/read`,
    { method: 'POST' });
}
```

- [ ] **Step 2: 创建 BpmPanelBlock skeleton**

```tsx
// web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx
import React from 'react';

export interface BpmPanelBlockProps {
  config: {
    processKey: string;
    businessKeyField: string;
    /** Which sections to show; empty = all */
    sections?: Array<'status' | 'diagram' | 'operations' | 'history'>;
  };
  record: Record<string, unknown>;
}

export const BpmPanelBlock: React.FC<BpmPanelBlockProps> = ({ config, record }) => {
  const businessKey = record[config.businessKeyField];
  if (businessKey === undefined || businessKey === null) {
    return <div className="bpm-panel-empty">No business key in record</div>;
  }
  const sections = config.sections && config.sections.length
    ? config.sections : ['status', 'diagram', 'operations', 'history'];

  return (
    <div className="bpm-panel" data-testid="bpm-panel">
      {sections.includes('status') && <div data-testid="bpm-status-placeholder" />}
      {sections.includes('diagram') && <div data-testid="bpm-diagram-placeholder" />}
      {sections.includes('operations') && <div data-testid="bpm-operations-placeholder" />}
      {sections.includes('history') && <div data-testid="bpm-history-placeholder" />}
    </div>
  );
};
```

- [ ] **Step 3: 创建 block 注册入口**

```typescript
// web-admin/app/plugins/core-bpm/blocks/bpm-panel/index.ts
import { BpmPanelBlock } from './BpmPanelBlock';

export const bpmPanelBlock = {
  blockType: 'bpm-panel',
  Component: BpmPanelBlock,
  /** Allowed page kinds: detail-only (full panel makes no sense on list/form) */
  allowedKinds: ['detail'] as const,
};
```

- [ ] **Step 4: 注册到 BlockRegistry**

打开 `web-admin/app/plugins/core-designer/components/studio/registry/blocks/index.ts`，加：

```typescript
import { bpmPanelBlock } from '@/plugins/core-bpm/blocks/bpm-panel';

// ... existing imports ...

export const builtinBlocks = [
  // ... existing entries ...
  bpmPanelBlock,
];
```

- [ ] **Step 5: 类型检查 + 提交**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/web-admin
npx tsc --noEmit 2>&1 | tail -10
```

Expected: 无新增 error.

```bash
git add web-admin/app/plugins/core-bpm/api/bpmApi.ts \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/ \
        web-admin/app/plugins/core-designer/components/studio/registry/blocks/index.ts
git commit -m "feat(web): bpm-panel block skeleton + bpmApi service

Block registered for kind=detail with 4 section placeholders. bpmApi wraps
withdraw/cc/inbox/status endpoints and routes CC inbox via SmartEngine
NotificationQuery (/api/bpm/notifications/inbox)."
```

---

## Task 11: BpmStatusSection

**Files:**
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmStatusSection.tsx`
- Modify: `BpmPanelBlock.tsx` to render BpmStatusSection in place of placeholder

- [ ] **Step 1: 创建 BpmStatusSection**

```tsx
// web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmStatusSection.tsx
import React, { useEffect, useState } from 'react';
import { getProcessStatus, BpmStatusResponse } from '../../api/bpmApi';

interface Props {
  processKey: string;
  businessKey: string;
}

const STATUS_LABELS: Record<string, string> = {
  running: '进行中',
  completed: '已完成',
  terminated: '已撤回',
  suspended: '已暂停',
  unknown: '未知',
};

export const BpmStatusSection: React.FC<Props> = ({ processKey, businessKey }) => {
  const [status, setStatus] = useState<BpmStatusResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    getProcessStatus(processKey, businessKey).then((r) => {
      if (!cancelled) { setStatus(r); setLoading(false); }
    });
    return () => { cancelled = true; };
  }, [processKey, businessKey]);

  if (loading) return <div data-testid="bpm-status-loading">加载中…</div>;
  if (!status) return <div data-testid="bpm-status-empty">暂无流程实例</div>;

  return (
    <section className="bpm-status-section" data-testid="bpm-status">
      <header>
        <h3>流程状态</h3>
        <span data-testid="bpm-status-badge" className={`status-${status.status}`}>
          {STATUS_LABELS[status.status] ?? status.status}
        </span>
      </header>
      <div data-testid="bpm-status-current-nodes">
        {status.currentNodes.length === 0
          ? <span>无活动节点</span>
          : status.currentNodes.map((n) => (
              <div key={n.nodeId} className="current-node">
                <span>{n.nodeName ?? n.nodeId}</span>
                {n.assignee && <span className="assignee"> @{n.assignee}</span>}
              </div>))}
      </div>
    </section>
  );
};
```

- [ ] **Step 2: 在 BpmPanelBlock 中接入**

```tsx
// 替换 <div data-testid="bpm-status-placeholder" /> 为：
{sections.includes('status') && (
  <BpmStatusSection
    processKey={config.processKey}
    businessKey={String(businessKey)}
  />
)}
// 文件顶部加 import：
import { BpmStatusSection } from './BpmStatusSection';
```

- [ ] **Step 3: 类型检查 + 提交**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/web-admin && npx tsc --noEmit 2>&1 | tail -5
git add web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmStatusSection.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx
git commit -m "feat(web/bpm-panel): BpmStatusSection renders process status + current nodes"
```

---

## Task 12: BpmDiagramSection

**Files:**
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmDiagramSection.tsx`
- Modify: `BpmPanelBlock.tsx`

- [ ] **Step 1: 创建 BpmDiagramSection（先用 SVG 简单显示，后续可换 bpmn-js）**

```tsx
// web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmDiagramSection.tsx
import React, { useEffect, useState } from 'react';
import { getProcessStatus, BpmStatusResponse } from '../../api/bpmApi';

interface Props {
  processKey: string;
  businessKey: string;
}

export const BpmDiagramSection: React.FC<Props> = ({ processKey, businessKey }) => {
  const [status, setStatus] = useState<BpmStatusResponse | null>(null);

  useEffect(() => {
    let cancelled = false;
    getProcessStatus(processKey, businessKey).then((r) => {
      if (!cancelled) setStatus(r);
    });
    return () => { cancelled = true; };
  }, [processKey, businessKey]);

  if (!status) return <div data-testid="bpm-diagram-empty">无流程数据</div>;

  const allNodes = [
    ...status.currentNodes.map((n) => ({ ...n, state: 'active' })),
    ...status.completedNodes.map((n) => ({ ...n, state: 'completed' })),
  ];
  return (
    <section className="bpm-diagram-section" data-testid="bpm-diagram">
      <header><h3>流程进度</h3></header>
      <ol className="bpm-node-list">
        {allNodes.map((n) => (
          <li key={n.nodeId} data-testid={`bpm-node-${n.nodeId}`} className={`node-${n.state}`}>
            <span className="node-state">{n.state === 'active' ? '◉' : '✓'}</span>
            <span>{n.nodeName ?? n.nodeId}</span>
          </li>
        ))}
      </ol>
    </section>
  );
};
```

- [ ] **Step 2: BpmPanelBlock 接入**

```tsx
{sections.includes('diagram') && (
  <BpmDiagramSection
    processKey={config.processKey}
    businessKey={String(businessKey)}
  />
)}
// import { BpmDiagramSection } from './BpmDiagramSection';
```

- [ ] **Step 3: Commit**

```bash
git add web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmDiagramSection.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx
git commit -m "feat(web/bpm-panel): BpmDiagramSection renders sequential node progress"
```

---

## Task 13: BpmOperationsSection + WithdrawDialog + CcDialog + BpmPermissionService

**Files:**
- Create: `web-admin/app/plugins/core-bpm/services/BpmPermissionService.ts`
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/WithdrawDialog.tsx`
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/CcDialog.tsx`
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmOperationsSection.tsx`
- Modify: `BpmPanelBlock.tsx`

- [ ] **Step 1: BpmPermissionService**

```typescript
// web-admin/app/plugins/core-bpm/services/BpmPermissionService.ts
export interface CurrentUser {
  userId: number;
  username: string;
}

/**
 * Operation visibility on bpm-panel.
 * Three layers: action permission → identity heuristic → IAM override (TBD).
 */
export class BpmPermissionService {
  static canWithdraw(opts: { isInitiator: boolean; processStatus: string }): boolean {
    return opts.isInitiator && opts.processStatus === 'running';
  }
  static canCc(opts: { isInitiator: boolean; isAssignee: boolean }): boolean {
    return opts.isInitiator || opts.isAssignee;
  }
}
```

- [ ] **Step 2: WithdrawDialog**

```tsx
// web-admin/app/plugins/core-bpm/blocks/bpm-panel/WithdrawDialog.tsx
import React, { useState } from 'react';
import { withdrawProcess } from '../../api/bpmApi';

interface Props {
  open: boolean;
  taskId: string;
  onClose: () => void;
  onSuccess: () => void;
}

export const WithdrawDialog: React.FC<Props> = ({ open, taskId, onClose, onSuccess }) => {
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const submit = async () => {
    setSubmitting(true); setError(null);
    try { await withdrawProcess(taskId, reason); onSuccess(); onClose(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setSubmitting(false); }
  };

  return (
    <div className="dialog-overlay" data-testid="withdraw-dialog">
      <div className="dialog">
        <h3>撤回流程</h3>
        <label>撤回原因
          <textarea value={reason} onChange={(e) => setReason(e.target.value)}
                    data-testid="withdraw-reason" />
        </label>
        {error && <div className="dialog-error" data-testid="withdraw-error">{error}</div>}
        <footer>
          <button onClick={onClose} disabled={submitting}>取消</button>
          <button onClick={submit} disabled={submitting} data-testid="withdraw-confirm">
            {submitting ? '提交中…' : '确认撤回'}
          </button>
        </footer>
      </div>
    </div>
  );
};
```

- [ ] **Step 3: CcDialog**

```tsx
// web-admin/app/plugins/core-bpm/blocks/bpm-panel/CcDialog.tsx
import React, { useState } from 'react';
import { ccProcess } from '../../api/bpmApi';

interface Props {
  open: boolean;
  taskId: string;
  onClose: () => void;
  onSuccess: () => void;
}

export const CcDialog: React.FC<Props> = ({ open, taskId, onClose, onSuccess }) => {
  const [receiverIds, setReceiverIds] = useState('');
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const submit = async () => {
    const ids = receiverIds.split(/[,\s]+/).map((s) => Number(s.trim())).filter(Boolean);
    if (ids.length === 0) { setError('请填写至少一个接收人 user id'); return; }
    setSubmitting(true); setError(null);
    try { await ccProcess(taskId, ids, comment); onSuccess(); onClose(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setSubmitting(false); }
  };

  return (
    <div className="dialog-overlay" data-testid="cc-dialog">
      <div className="dialog">
        <h3>抄送</h3>
        <label>接收人 user id（逗号或空格分隔）
          <input value={receiverIds} onChange={(e) => setReceiverIds(e.target.value)}
                 data-testid="cc-receivers" />
        </label>
        <label>留言
          <textarea value={comment} onChange={(e) => setComment(e.target.value)}
                    data-testid="cc-comment" />
        </label>
        {error && <div className="dialog-error" data-testid="cc-error">{error}</div>}
        <footer>
          <button onClick={onClose} disabled={submitting}>取消</button>
          <button onClick={submit} disabled={submitting} data-testid="cc-confirm">
            {submitting ? '提交中…' : '发送抄送'}
          </button>
        </footer>
      </div>
    </div>
  );
};
```

- [ ] **Step 4: BpmOperationsSection**

```tsx
// web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmOperationsSection.tsx
import React, { useEffect, useState } from 'react';
import { getProcessStatus, BpmStatusResponse } from '../../api/bpmApi';
import { BpmPermissionService } from '../../services/BpmPermissionService';
import { WithdrawDialog } from './WithdrawDialog';
import { CcDialog } from './CcDialog';

interface Props {
  processKey: string;
  businessKey: string;
  currentUserId: number;
  currentUsername: string;
}

export const BpmOperationsSection: React.FC<Props> = (props) => {
  const [status, setStatus] = useState<BpmStatusResponse | null>(null);
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  const [ccOpen, setCcOpen] = useState(false);

  const reload = () => {
    getProcessStatus(props.processKey, props.businessKey).then(setStatus);
  };
  useEffect(reload, [props.processKey, props.businessKey]);

  if (!status) return <section data-testid="bpm-ops-empty" />;

  // Identity inference (replace with real /api/me when available)
  const initiatorVar = String(status.variables?.['initiatorUserId'] ?? '');
  const isInitiator = initiatorVar === String(props.currentUserId);
  const currentNode = status.currentNodes[0];
  const isAssignee = currentNode?.assignee === props.currentUsername;
  const taskId = currentNode ? currentNode.nodeId : '';

  const showWithdraw = BpmPermissionService.canWithdraw(
    { isInitiator, processStatus: status.status });
  const showCc = BpmPermissionService.canCc({ isInitiator, isAssignee });

  return (
    <section className="bpm-operations-section" data-testid="bpm-operations">
      <header><h3>操作</h3></header>
      <div className="bpm-op-buttons">
        {showWithdraw && (
          <button onClick={() => setWithdrawOpen(true)} data-testid="bpm-withdraw-btn">
            撤回流程
          </button>
        )}
        {showCc && (
          <button onClick={() => setCcOpen(true)} data-testid="bpm-cc-btn">
            抄送
          </button>
        )}
      </div>
      <WithdrawDialog open={withdrawOpen} taskId={taskId}
                      onClose={() => setWithdrawOpen(false)}
                      onSuccess={reload} />
      <CcDialog open={ccOpen} taskId={taskId}
                onClose={() => setCcOpen(false)} onSuccess={reload} />
    </section>
  );
};
```

注：`taskId` 从 currentNode.nodeId 推断仅作 skeleton；生产应从 `/api/bpm/tasks?processInstanceId=...` 拿真实 taskId。本 plan 不展开（后续 spec）。

- [ ] **Step 5: BpmPanelBlock 接入 + 注入 currentUser**

```tsx
{sections.includes('operations') && (
  <BpmOperationsSection
    processKey={config.processKey}
    businessKey={String(businessKey)}
    currentUserId={Number(record['__currentUserId'] ?? 0)}
    currentUsername={String(record['__currentUsername'] ?? '')}
  />
)}
// import { BpmOperationsSection } from './BpmOperationsSection';
```

约定调用方在 record 注入 `__currentUserId` / `__currentUsername`（来自 `useCurrentUser` hook）。

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/plugins/core-bpm/services/BpmPermissionService.ts \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/WithdrawDialog.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/CcDialog.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmOperationsSection.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx
git commit -m "feat(web/bpm-panel): operations section with withdraw + cc dialogs"
```

---

## Task 14: BpmHistorySection

**Files:**
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmHistorySection.tsx`
- Modify: `BpmPanelBlock.tsx`
- Modify: `web-admin/app/plugins/core-bpm/api/bpmApi.ts` (加 listAuditEvents)

- [ ] **Step 1: 加 listAuditEvents API**

在 `bpmApi.ts` 加：

```typescript
export interface AuditEvent {
  id: string;
  operation: string;
  userId: string;
  createdAt: string;
  details?: Record<string, unknown>;
}

export async function listAuditEvents(processInstanceId: string): Promise<AuditEvent[]> {
  const r = await fetch(
    `/api/bpm/audit-records?processInstanceId=${encodeURIComponent(processInstanceId)}`);
  const j = await r.json();
  return j.code === 0 ? (j.data ?? []) : [];
}
```

- [ ] **Step 2: BpmHistorySection 组件**

```tsx
// web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmHistorySection.tsx
import React, { useEffect, useState } from 'react';
import { getProcessStatus, listAuditEvents, AuditEvent } from '../../api/bpmApi';

interface Props {
  processKey: string;
  businessKey: string;
}

const OPERATION_LABELS: Record<string, string> = {
  process_start: '流程发起',
  task_approve: '审批通过',
  task_reject: '审批拒绝',
  withdraw: '撤回',
  cc: '抄送',
};

export const BpmHistorySection: React.FC<Props> = ({ processKey, businessKey }) => {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const status = await getProcessStatus(processKey, businessKey);
      if (cancelled || !status) { setLoading(false); return; }
      const list = await listAuditEvents(status.processInstanceId);
      if (!cancelled) { setEvents(list); setLoading(false); }
    })();
    return () => { cancelled = true; };
  }, [processKey, businessKey]);

  if (loading) return <div data-testid="bpm-history-loading">加载中…</div>;
  if (events.length === 0) return <div data-testid="bpm-history-empty">无审计事件</div>;

  return (
    <section className="bpm-history-section" data-testid="bpm-history">
      <header><h3>历史记录</h3></header>
      <ol className="bpm-timeline">
        {events.map((e) => (
          <li key={e.id} data-testid={`bpm-history-event-${e.id}`}>
            <time>{e.createdAt}</time>
            <span className="op">{OPERATION_LABELS[e.operation] ?? e.operation}</span>
            <span className="user">@{e.userId}</span>
          </li>
        ))}
      </ol>
    </section>
  );
};
```

- [ ] **Step 3: BpmPanelBlock 接入**

```tsx
{sections.includes('history') && (
  <BpmHistorySection
    processKey={config.processKey}
    businessKey={String(businessKey)}
  />
)}
// import { BpmHistorySection } from './BpmHistorySection';
```

- [ ] **Step 4: Commit**

```bash
git add web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmHistorySection.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx \
        web-admin/app/plugins/core-bpm/api/bpmApi.ts
git commit -m "feat(web/bpm-panel): history section + audit event timeline"
```

---

## Task 15: action.bpm PropertySchema for Studio designer

**Files:**
- Create: `web-admin/app/plugins/core-designer/components/studio/registry/blocks/bpm-panel/action-schema.ts`
- Create: `web-admin/app/plugins/core-designer/components/studio/registry/blocks/bpm-panel/bpm-panel-schema.ts`

- [ ] **Step 1: action.bpm PropertySchema**

```typescript
// .../bpm-panel/action-schema.ts
import { PropertySchema } from '@/plugins/core-designer/components/studio/types';

export const actionBpmSchema: PropertySchema[] = [
  { key: 'executionMode', label: 'Execution Mode', type: 'select',
    options: [
      { value: 'command', label: 'Command' },
      { value: 'bpm', label: 'BPM' },
    ],
    defaultValue: 'command',
  },
  { key: 'bpm.processKey', label: 'Process Key', type: 'select',
    required: true, group: 'BPM',
    dataSource: { type: 'remote', url: '/api/bpm/process-definitions' },
    dependsOn: { field: 'executionMode', value: 'bpm' } },
  { key: 'bpm.businessKeyField', label: 'Business Key Field', type: 'field-selector',
    required: true, group: 'BPM',
    dependsOn: { field: 'executionMode', value: 'bpm' } },
  { key: 'bpm.variables', label: 'Variables (key → JSONPath)', type: 'key-value-editor',
    group: 'BPM',
    dependsOn: { field: 'executionMode', value: 'bpm' } },
];
```

- [ ] **Step 2: bpm-panel block PropertySchema**

```typescript
// .../bpm-panel/bpm-panel-schema.ts
import { PropertySchema } from '@/plugins/core-designer/components/studio/types';

export const bpmPanelSchema: PropertySchema[] = [
  { key: 'processKey', label: 'Process Key', type: 'select', required: true,
    dataSource: { type: 'remote', url: '/api/bpm/process-definitions' } },
  { key: 'businessKeyField', label: 'Business Key Field', type: 'field-selector',
    required: true },
  { key: 'sections', label: 'Sections', type: 'multi-select',
    options: [
      { value: 'status', label: '状态' },
      { value: 'diagram', label: '进度图' },
      { value: 'operations', label: '操作' },
      { value: 'history', label: '历史' },
    ],
    defaultValue: ['status', 'diagram', 'operations', 'history'] },
];
```

- [ ] **Step 3: 在 Studio designer registry 注册**

修改 `web-admin/app/plugins/core-designer/components/studio/registry/blocks/index.ts`，扩展 `bpmPanelBlock` 的 schema 字段或增加 schema 注册条目（按现有 registry 形式）。

- [ ] **Step 4: tsc 检查 + commit**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/web-admin && npx tsc --noEmit 2>&1 | tail -5
git add web-admin/app/plugins/core-designer/components/studio/registry/blocks/bpm-panel/
git commit -m "feat(web/designer): action.bpm + bpm-panel PropertySchema"
```

---

## Task 16: 文档同步 + 冒烟验证

**Files:**
- Modify: `docs/system-reference/subsystems/` BPM 子系统文档
- Modify: `docs/standards/architecture.md`（加 RL-BPM-1..5 红线）

- [ ] **Step 1: 加 5 条红线到 standards/architecture.md**

在 `docs/standards/architecture.md` 末尾追加 RL-BPM-1 ~ RL-BPM-5（直接 copy 自 design spec 第 8 节）。

- [ ] **Step 2: 更新 BPM 子系统文档**

找到 `docs/system-reference/subsystems/` 下的 BPM 文档，更新：
- 删除关于 BpmEngine 抽象层的描述
- 更新 ab_bpm_process_definition 字段列表（去掉 withdrawPolicy/ccPolicy/requiredPermissions）
- 删除 ab_bpm_cc_record 表说明
- 加 BPMN extension `<smart:properties>` aura.* 约定
- 更新 CC 实现说明（指向 SmartEngine NotificationService）

- [ ] **Step 3: 写 HANDOVER 候选回退指引**

在 `docs/handover/HANDOVER.md` 顶部加一段说明 v2 plan 的位置，指向新设计 spec + 新 plan。（保留原 HANDOVER 内容用作历史 trail）

- [ ] **Step 4: 全套后端测试冒烟**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
yes y | ../scripts/reset-db.sh 2>&1 | tail -3
cd platform
./gradlew test --tests "com.auraboot.framework.bpm.*" \
  --tests "com.auraboot.framework.action.BpmActionExecutorIntegrationTest" \
  --tests "com.auraboot.framework.plugin.PluginProcessImportDeploymentTest" \
  -x :platform-plugin-api:test -x :platform-storage-minio:test \
  -x :platform-storage-s3:test -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test -x :platform-mq-rabbitmq:test 2>&1 | tee /tmp/pw-task16-backend.log
grep "FAILED" /tmp/pw-task16-backend.log | head -5 || echo "BACKEND ALL PASSED"
```

Expected: `BACKEND ALL PASSED`.

- [ ] **Step 5: 前端单元测试冒烟**

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1/web-admin
pnpm test:unit 2>&1 | tee /tmp/pw-task16-fe.log | tail -20
```

Expected: 全部 PASSED 或仅 pre-existing 失败（非本 plan 引入）。

- [ ] **Step 6: 提交文档变更**

```bash
git add docs/standards/architecture.md \
        docs/system-reference/subsystems/ \
        docs/handover/HANDOVER.md
git commit -m "docs(bpm): sync system-reference + standards red lines for v2 architecture"
```

---

## Self-Review

完成所有 Task 后，本 plan 的 spec 覆盖性自检：

| Spec 决策 | Task 覆盖 |
|---|---|
| D1 删 BpmEngine | Task 2 |
| D2 厚 service 不抽象 | 隐含在所有 service 重写 |
| D3 CC 用 NotificationService | Task 5 + Task 14（前端 inbox） |
| D4 ab_bpm_process_definition 退化 | **本 plan 不全做**（瘦身完整版在 Spec 1.5）；Task 7 仅删 3 列 |
| D5 节点配置进 BPMN | Task 3 fixture 模板示例 + Task 5/4 读取 |
| D6 smart:properties + aura.* | Task 1 (常量) + Task 3 (fixture) |
| D7 formKey 引用 | 留 Spec 1.5 |
| D8 SmartEngine 能力盘点 | 文档（Task 16）|
| D9 Audit 边界 | Task 5 调整 + 文档；查询聚合留 Spec 1.5 |
| D10 依赖红线 | Task 16 红线文档 |
| D11 触发器薄壳 | Task 6 |
| D12 测试 fixture 真路径 | Task 2 + Task 3 |

**已知留待 Spec 1.5**：
- ab_bpm_process_definition 完整瘦身（删 form_bindings, business_data_bindings, bpmn_content 等 14 个剩余字段）
- BpmAuditQueryService 聚合多源
- jump UNSAFE 收紧
- timeout 字段 sunset

---

## 完成标志

- 所有 16 个 Task 全部 commit
- `./gradlew test --tests "com.auraboot.framework.bpm.*"` 全 PASSED
- `pnpm vitest run app/shared/dsl/__tests__/action-bpm.test.ts` 全 PASSED
- `git log` 显示约 16 个新 commit + 之前 7 个保留的 v1 commit + 2 个设计文档 commit
- `BpmEngine` / `BpmCcRecord` / `withdrawPolicy column` 全仓 grep 0 匹配
- 数据库 schema 中 `ab_bpm_cc_record` 表不存在，`ab_bpm_process_definition` 不含 3 个新加列
