# BPM 审批语义补齐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 OSS BPM 审批语义闭环：修复 SmartEngine 部署链路；新增撤回/抄送 endpoint；新增 `executionMode:"bpm"` action 路径 + `bpm-panel` blockType；落实 schema 扩展和权限模型。

**Architecture:** 后端：BPM 业务进 `framework/bpm/`，action executor 进 `framework/action/executor/`，action→bpm 单向依赖。前端：新 `bpm-panel` block 注册进 BlockRegistry，4 个 section 组件独立渲染。数据库直改 `schema.sql`，不做迁移脚本。

**Tech Stack:** Java 21 / Spring Boot / MyBatis Plus / SmartEngine (Alibaba) / Drools / PostgreSQL / React / TypeScript / Vite / React Router / Playwright / AssertJ / JUnit 5

**Spec:** `docs/superpowers/specs/2026-04-16-oss-bpm-closure-spec1-design.md`

---

## 前置约定

- 所有后端测试继承 `BaseIntegrationTest`（真实 PG + 真实 Redis，禁止 H2/Mock DB）
- 禁止魔术字符串：所有 policy 枚举在 `WithdrawPolicy` / `CcPolicy` enum 常量
- 所有 DB 字符串值小写（`strict` / `loose` / `none` / `initiator` / `assignee` / `all`）
- 每个 Task 完成后立即 commit，commit message 用英文
- **E2E 测试整体延后到示例包合并后做**，本 Plan 内只补"单元/集成测试"与"冒烟级 Playwright"
- 本 Plan 在主仓 `/Users/ghj/work/auraboot/auraboot` 执行，不在 worktree

---

## 文件结构

**新增**（后端）
- `platform/src/main/java/com/auraboot/framework/bpm/model/WithdrawPolicy.java` — enum
- `platform/src/main/java/com/auraboot/framework/bpm/model/CcPolicy.java` — enum
- `platform/src/main/java/com/auraboot/framework/bpm/service/WithdrawService.java`
- `platform/src/main/java/com/auraboot/framework/bpm/service/CcService.java`
- `platform/src/main/java/com/auraboot/framework/bpm/entity/BpmCcRecord.java`
- `platform/src/main/java/com/auraboot/framework/bpm/mapper/BpmCcRecordMapper.java`
- `platform/src/main/java/com/auraboot/framework/action/executor/BpmActionExecutor.java`
- `platform/src/test/java/com/auraboot/framework/bpm/WithdrawServiceIntegrationTest.java`
- `platform/src/test/java/com/auraboot/framework/bpm/CcServiceIntegrationTest.java`
- `platform/src/test/java/com/auraboot/framework/action/BpmActionExecutorIntegrationTest.java`

**新增**（前端）
- `web-admin/app/plugins/core-bpm/blocks/bpm-panel/index.ts`
- `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx`
- `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmStatusSection.tsx`
- `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmDiagramSection.tsx`
- `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmOperationsSection.tsx`
- `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmHistorySection.tsx`
- `web-admin/app/plugins/core-bpm/blocks/bpm-panel/dialogs/WithdrawDialog.tsx`
- `web-admin/app/plugins/core-bpm/blocks/bpm-panel/dialogs/CcDialog.tsx`
- `web-admin/app/plugins/core-bpm/blocks/bpm-panel/schema.ts`

**修改**（后端）
- `platform/src/main/java/com/auraboot/framework/plugin/entity/BpmProcessDefinition.java` — 加 3 字段
- `platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java:1271` — 改调用
- `platform/src/main/java/com/auraboot/framework/bpm/controller/TaskController.java` — 加 2 endpoint
- `platform/src/main/java/com/auraboot/framework/plugin/mapper/BpmProcessDefinitionMapper.java` — mapping 更新
- `platform/src/main/resources/database/schema.sql` — ALTER + CREATE TABLE

**修改**（前端）
- `web-admin/app/plugins/core-designer/components/studio/registry/blocks/index.ts` — register bpmPanelBlock
- `web-admin/app/shared/dsl/types.ts` — ActionDef 加 `executionMode: 'bpm'` + `bpm` 子对象类型
- `web-admin/app/shared/action/ActionExecutor.ts`（或等价入口）— 分支 bpm
- `web-admin/app/plugins/core-designer/components/studio/registry/blocks/*/action-schema.ts` — 新 schema for action.bpm

---

## Task 0: 修复 SmartEngine 部署链路（阻塞性）

**Files:**
- Read: `platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java:1247-1278`
- Read: `platform/src/main/java/com/auraboot/framework/bpm/service/ProcessDeploymentService.java:328-394`
- Modify: `platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java:1271`
- Test: `platform/src/test/java/com/auraboot/framework/plugin/PluginProcessImportDeploymentTest.java`

**诊断假说**（根据探查结果）：
- `ProcessDeploymentService.deploy()` 调的是 `smartEngine.getRepositoryCommandService().deploy(bpmnStream, tenantId)`（带 tenant）
- `PluginResourceImporterImpl.deployProcessToSmartEngine()` 调的是 `smartEngine.getRepositoryCommandService().deployWithUTF8Content(bpmnXml)`（无 tenant）
- 插件导入走后者，跨租户部署时 `se_deployment_instance` 写入的 tenant 与后续 `startProcess` 查询的 tenant 不一致，导致流程起不来

- [ ] **Step 1: 写失败的集成测试**

创建 `platform/src/test/java/com/auraboot/framework/plugin/PluginProcessImportDeploymentTest.java`：

```java
package com.auraboot.framework.plugin;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Plugin-imported processes deploy to SmartEngine with current tenant")
class PluginProcessImportDeploymentTest extends BaseIntegrationTest {

    @Autowired private BpmProcessDefinitionMapper processDefinitionMapper;
    @Autowired private SmartEngine smartEngine;

    @Test
    @DisplayName("Imported process can be started under the same tenant")
    void importedProcessStartsUnderSameTenant() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String processKey = "test-plugin-deploy-" + System.currentTimeMillis();

        // 1. Simulate plugin import path — writes BpmProcessDefinition row AND deploys to SmartEngine
        importProcessViaPluginPath(processKey, tenantId);

        // 2. DB row exists for current tenant
        BpmProcessDefinition def = processDefinitionMapper.selectByProcessKey(tenantId, processKey);
        assertThat(def).isNotNull();
        assertThat(def.getStatus()).isEqualTo("deployed");

        // 3. SmartEngine can start an instance — proves deployment registered under this tenant
        String instanceId = smartEngine.getProcessCommandService()
                .startProcessInstance(processKey, Map.of("amount", 100)).getInstanceId();
        assertThat(instanceId).isNotBlank();

        // 4. Query by same tenant returns the running instance
        ProcessInstanceQueryParam q = new ProcessInstanceQueryParam();
        q.setTenantId(MetaContext.getCurrentTenantIdAsString());
        q.setStatus("running");
        assertThat(smartEngine.getProcessQueryService().findList(q))
                .anyMatch(pi -> processKey.equals(pi.getProcessDefinitionId()));
    }

    /** Helper: reproduce the actual plugin-import code path into one call. */
    private void importProcessViaPluginPath(String processKey, Long tenantId) {
        // Build minimal designerJson: start node → end node
        Map<String, Object> designerJson = Map.of(
                "nodes", List.of(
                        Map.of("id", "start", "type", "startEvent", "data", Map.of("label", "Start")),
                        Map.of("id", "end", "type", "endEvent", "data", Map.of("label", "End"))
                ),
                "edges", List.of(
                        Map.of("id", "e1", "source", "start", "target", "end")
                )
        );

        ProcessDefinitionDTO dto = new ProcessDefinitionDTO();
        dto.setKey(processKey);
        dto.setName(Map.of("zh_CN", "测试流程", "en_US", "Test Process"));
        dto.setDesignerJson(designerJson);
        dto.setAutoDeploy(true);

        // Call the internal importer — if the signature differs, read
        // PluginResourceImporterImpl for actual method name and args.
        pluginResourceImporter.importProcess(dto, "test-plugin-pid", "test-import-id",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE, true);
    }
}
```

**Note:** If `ProcessDefinitionDTO` has required fields not set above (e.g., `formBindings`, `businessDataBindings`), add defaults. Read the DTO file first. Auto-injected bean: `@Autowired private PluginResourceImporter pluginResourceImporter;`

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew test --tests PluginProcessImportDeploymentTest 2>&1 | tee /tmp/pw-task0-step2.log
```

Expected: FAIL — either `def.getStatus()` is `draft` (no deploy), or `startProcessInstance` throws `ProcessDefinitionNotFoundException`, or SmartEngine returns empty.

- [ ] **Step 3: 修复 `deployProcessToSmartEngine` 统一带 tenant**

Edit `platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java` around line 1270:

OLD:
```java
try {
    String bpmnXml = jsonToBpmnConverter.convertFromMap(designerJson);
    smartEngine.getRepositoryCommandService().deployWithUTF8Content(bpmnXml);
    log.info("Deployed BPMN process to SmartEngine: tenantId={}, processKey={}, version={}",
            tenantId, dto.getKey(), version);
}
```

NEW:
```java
try {
    String bpmnXml = jsonToBpmnConverter.convertFromMap(designerJson);
    // Ensure BPMN has version attribute (SmartEngine requires)
    String versionStr = String.valueOf(version);
    if (!bpmnXml.contains("version=\"")) {
        bpmnXml = bpmnXml.replaceFirst(
                "(<process\\s+[^>]*)(>)",
                "$1 version=\"" + versionStr + ".0.0\"$2");
    }
    // Use tenant-aware deploy so instance queries under the same tenant succeed
    java.io.ByteArrayInputStream bpmnStream = new java.io.ByteArrayInputStream(
            bpmnXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    smartEngine.getRepositoryCommandService()
            .deploy(bpmnStream, String.valueOf(tenantId));
    log.info("Deployed BPMN process to SmartEngine: tenantId={}, processKey={}, version={}",
            tenantId, dto.getKey(), version);
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew test --tests PluginProcessImportDeploymentTest 2>&1 | tee /tmp/pw-task0-step4.log
```

Expected: PASS

- [ ] **Step 5: 运行 workflow-demo 已有 E2E 确认 unskip**

```bash
cd /Users/ghj/work/auraboot/auraboot
./scripts/reset-and-init.sh   # 重新导入全部插件（含 workflow-demo）
cd web-admin
LOG=/tmp/pw-$(date +%Y%m%d-%H%M%S).log
NO_PROXY=localhost npx playwright test tests/e2e/workflow-demo/ 2>&1 | tee "$LOG"
```

Expected: workflow-demo 的 3 个 E2E 不再 "skip gracefully"；至少 1 个跑到流程启动并成功断言任务创建。

（若还有部分 skip 是因为 workflow-demo 插件自身 bug，记录在 commit message 里，不阻塞 Task 0 完成。核心判定：`startProcessInstance` 不再因部署缺失失败。）

- [ ] **Step 6: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot
git add platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java \
        platform/src/test/java/com/auraboot/framework/plugin/PluginProcessImportDeploymentTest.java
git commit -m "fix(bpm): plugin-imported processes deploy with tenant-aware SmartEngine call

Unify plugin-import deployment path with ProcessDeploymentService to use
deploy(stream, tenantId) instead of deployWithUTF8Content(xml). The
tenant-less call caused se_deployment_instance rows to be written under
a default scope while startProcess queries under the current tenant
returned empty, leaving imported processes un-runnable.

Fixes workflow-demo E2E tests that were skipping due to missing deployments."
```

---

## Task 1: Schema 扩展（流程定义 + cc 记录表）

**Files:**
- Modify: `platform/src/main/resources/database/schema.sql:2459-2526`
- Modify: `platform/src/main/resources/database/schema.sql` (append new table)

- [ ] **Step 1: 在 `ab_bpm_process_definition` 追加三字段**

Locate the table definition at lines 2459-2526 and add these columns before the closing paren:

```sql
    withdraw_policy VARCHAR(20) NOT NULL DEFAULT 'strict',
    cc_policy VARCHAR(20) NOT NULL DEFAULT 'all',
    required_permissions JSONB NOT NULL DEFAULT '{}'::jsonb,
```

- [ ] **Step 2: 在 schema.sql 追加 `ab_bpm_cc_record`**

Append after the existing bpm-related tables:

```sql
-- ================================================================
-- BPM CC Record — 抄送记录表
-- 每条记录代表一次"抄送行为"，承载 sender/receivers/comment/read_state
-- ================================================================
CREATE TABLE IF NOT EXISTS ab_bpm_cc_record (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    process_instance_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64),
    sender_id BIGINT NOT NULL,
    receiver_user_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    comment TEXT,
    read_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_flag BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_cc_process_instance ON ab_bpm_cc_record(process_instance_id);
CREATE INDEX idx_cc_tenant ON ab_bpm_cc_record(tenant_id);
CREATE INDEX idx_cc_sender ON ab_bpm_cc_record(sender_id);
```

- [ ] **Step 3: 运行 reset-db.sh 验证 schema 合法**

```bash
cd /Users/ghj/work/auraboot/auraboot
./scripts/reset-db.sh 2>&1 | tee /tmp/pw-task1-step3.log
psql -h localhost -U ghj -d aura_boot -P pager=off -c "\d ab_bpm_process_definition" | grep -E "withdraw_policy|cc_policy|required_permissions"
psql -h localhost -U ghj -d aura_boot -P pager=off -c "\d ab_bpm_cc_record"
```

Expected: 3 new columns present on ab_bpm_process_definition; ab_bpm_cc_record table exists with all columns.

- [ ] **Step 4: Commit**

```bash
git add platform/src/main/resources/database/schema.sql
git commit -m "feat(bpm): add withdraw_policy/cc_policy/required_permissions and ab_bpm_cc_record"
```

---

## Task 2: Policy enum + BpmProcessDefinition 字段扩展

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/bpm/model/WithdrawPolicy.java`
- Create: `platform/src/main/java/com/auraboot/framework/bpm/model/CcPolicy.java`
- Modify: `platform/src/main/java/com/auraboot/framework/plugin/entity/BpmProcessDefinition.java`
- Test: `platform/src/test/java/com/auraboot/framework/bpm/model/PolicyEnumTest.java`

- [ ] **Step 1: 创建 `WithdrawPolicy` enum**

```java
package com.auraboot.framework.bpm.model;

import java.util.Arrays;

/** Who/when a process instance can be withdrawn. All DB values are lowercase. */
public enum WithdrawPolicy {
    STRICT("strict"),   // Initiator only, before any approve
    LOOSE("loose"),     // Initiator only, anytime while running
    NONE("none");       // Disabled

    private final String code;
    WithdrawPolicy(String code) { this.code = code; }
    public String code() { return code; }

    public static WithdrawPolicy fromCode(String code) {
        if (code == null || code.isBlank()) return STRICT;
        return Arrays.stream(values())
                .filter(p -> p.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown WithdrawPolicy: " + code));
    }
}
```

- [ ] **Step 2: 创建 `CcPolicy` enum**

```java
package com.auraboot.framework.bpm.model;

import java.util.Arrays;

/** Who can initiate a CC on a process task. All DB values are lowercase. */
public enum CcPolicy {
    INITIATOR("initiator"),
    ASSIGNEE("assignee"),
    ALL("all");

    private final String code;
    CcPolicy(String code) { this.code = code; }
    public String code() { return code; }

    public static CcPolicy fromCode(String code) {
        if (code == null || code.isBlank()) return ALL;
        return Arrays.stream(values())
                .filter(p -> p.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown CcPolicy: " + code));
    }
}
```

- [ ] **Step 3: 写 enum 单元测试**

```java
package com.auraboot.framework.bpm.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyEnumTest {

    @Test void withdrawPolicyFromCodeLowercase() {
        assertThat(WithdrawPolicy.fromCode("strict")).isEqualTo(WithdrawPolicy.STRICT);
        assertThat(WithdrawPolicy.fromCode("LOOSE")).isEqualTo(WithdrawPolicy.LOOSE);
        assertThat(WithdrawPolicy.fromCode(null)).isEqualTo(WithdrawPolicy.STRICT);
        assertThat(WithdrawPolicy.fromCode("")).isEqualTo(WithdrawPolicy.STRICT);
    }

    @Test void withdrawPolicyRejectsUnknown() {
        assertThatThrownBy(() -> WithdrawPolicy.fromCode("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ccPolicyDefaultsToAll() {
        assertThat(CcPolicy.fromCode(null)).isEqualTo(CcPolicy.ALL);
        assertThat(CcPolicy.fromCode("initiator")).isEqualTo(CcPolicy.INITIATOR);
        assertThat(CcPolicy.fromCode("assignee")).isEqualTo(CcPolicy.ASSIGNEE);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew test --tests PolicyEnumTest 2>&1 | tee /tmp/pw-task2-step4.log
```

Expected: PASS

- [ ] **Step 5: 在 `BpmProcessDefinition` 加字段**

Edit `platform/src/main/java/com/auraboot/framework/plugin/entity/BpmProcessDefinition.java`, add after the existing `extension` JSONB field (around line 111):

```java
    /** Withdraw policy: strict | loose | none. Default strict. */
    @TableField(value = "withdraw_policy")
    private String withdrawPolicy;

    /** CC policy: initiator | assignee | all. Default all. */
    @TableField(value = "cc_policy")
    private String ccPolicy;

    /** Required permissions override, keyed by operation name (withdraw/cc/...). */
    @TableField(value = "required_permissions",
            typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> requiredPermissions;
```

- [ ] **Step 6: 运行编译 + 已有测试回归**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew compileJava 2>&1 | tee /tmp/pw-task2-step6a.log
./gradlew test --tests "com.auraboot.framework.bpm.*" 2>&1 | tee /tmp/pw-task2-step6b.log
```

Expected: compile success; existing BPM tests still pass.

- [ ] **Step 7: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/bpm/model/WithdrawPolicy.java \
        platform/src/main/java/com/auraboot/framework/bpm/model/CcPolicy.java \
        platform/src/main/java/com/auraboot/framework/plugin/entity/BpmProcessDefinition.java \
        platform/src/test/java/com/auraboot/framework/bpm/model/PolicyEnumTest.java
git commit -m "feat(bpm): add WithdrawPolicy/CcPolicy enums and process definition fields"
```

---

## Task 3: WithdrawService + endpoint

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/bpm/service/WithdrawService.java`
- Create: `platform/src/test/java/com/auraboot/framework/bpm/WithdrawServiceIntegrationTest.java`
- Modify: `platform/src/main/java/com/auraboot/framework/bpm/controller/TaskController.java`

- [ ] **Step 1: 写失败的集成测试**

```java
package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.framework.bpm.service.WithdrawService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WithdrawService")
class WithdrawServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private WithdrawService withdrawService;
    @Autowired private TestBpmFixture fixture;  // helper that deploys a simple process & starts instance

    @Test
    @DisplayName("Strict policy: initiator can withdraw before any approval")
    void strictAllowsWithdrawBeforeApproval() {
        var setup = fixture.startProcess("strict-before-approve", WithdrawPolicy.STRICT);

        withdrawService.withdraw(setup.taskId(), "typo in form");

        assertThat(fixture.getProcessStatus(setup.instanceId())).isEqualTo("withdrawn");
        assertThat(fixture.findAuditRecords(setup.instanceId()))
                .anyMatch(r -> "withdraw".equalsIgnoreCase(r.getOperation()));
    }

    @Test
    @DisplayName("Strict policy: rejects withdraw after first approval")
    void strictRejectsAfterApproval() {
        var setup = fixture.startProcess("strict-after-approve", WithdrawPolicy.STRICT);
        fixture.approveTask(setup.taskId(), "lgtm");

        var newTaskId = fixture.currentTaskId(setup.instanceId());
        assertThatThrownBy(() -> withdrawService.withdraw(newTaskId, "too late"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already approved");
    }

    @Test
    @DisplayName("Loose policy: initiator can withdraw even after approvals")
    void looseAllowsWithdrawAfterApproval() {
        var setup = fixture.startProcess("loose-after-approve", WithdrawPolicy.LOOSE);
        fixture.approveTask(setup.taskId(), "lgtm");

        var newTaskId = fixture.currentTaskId(setup.instanceId());
        withdrawService.withdraw(newTaskId, "late change of mind");

        assertThat(fixture.getProcessStatus(setup.instanceId())).isEqualTo("withdrawn");
    }

    @Test
    @DisplayName("None policy: withdraw is always rejected")
    void noneRejectsAlways() {
        var setup = fixture.startProcess("none-policy", WithdrawPolicy.NONE);
        assertThatThrownBy(() -> withdrawService.withdraw(setup.taskId(), "try"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    @DisplayName("Rejects withdraw by non-initiator user")
    void rejectsNonInitiator() {
        var setup = fixture.startProcessAsUser("non-initiator-test", 999L, WithdrawPolicy.STRICT);
        fixture.switchCurrentUserTo(1000L);
        assertThatThrownBy(() -> withdrawService.withdraw(setup.taskId(), "not mine"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("initiator");
    }
}
```

**Note:** `TestBpmFixture` is a helper bean you introduce in `platform/src/test/java/com/auraboot/framework/bpm/TestBpmFixture.java` that:
- deploys a 2-step process (start → userTask → approve → end) via existing `ProcessDeploymentService`
- exposes `startProcess(key, policy)`, `approveTask(taskId, comment)`, `currentTaskId(instanceId)`, `getProcessStatus(instanceId)`, `findAuditRecords(instanceId)`, `switchCurrentUserTo(userId)`

Create this helper in Step 1 alongside the test file. Refer to existing `ProcessOrchestrationServiceExtTest.createAndDeployProcess` for the deployment pattern.

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew test --tests WithdrawServiceIntegrationTest 2>&1 | tee /tmp/pw-task3-step2.log
```

Expected: FAIL — "WithdrawService not found"

- [ ] **Step 3: 实现 WithdrawService**

```java
package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditRecord;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.engine.adapter.SmartEngineBpmAdapter;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.service.param.query.TaskInstanceQueryParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private final SmartEngine smartEngine;
    private final SmartEngineBpmAdapter bpmAdapter;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final BpmAuditService auditService;

    @Transactional
    public void withdraw(String taskId, String reason) {
        Long currentUserId = BpmSecurityUtil.getCurrentUserIdAsLong();

        // 1. Resolve task → process instance → definition
        TaskInstanceQueryParam q = new TaskInstanceQueryParam();
        q.setTaskInstanceId(taskId);
        List<TaskInstance> tasks = smartEngine.getTaskQueryService().findList(q);
        if (tasks.isEmpty()) {
            throw new BusinessException("Task not found: " + taskId);
        }
        TaskInstance task = tasks.get(0);
        String processInstanceId = task.getProcessInstanceId();
        String processKey = task.getProcessDefinitionId();

        BpmProcessDefinition def = processDefinitionMapper.selectByProcessKey(
                MetaContext.getCurrentTenantId(), processKey);
        if (def == null) {
            throw new BusinessException("Process definition not found: " + processKey);
        }

        // 2. Policy gate
        WithdrawPolicy policy = WithdrawPolicy.fromCode(def.getWithdrawPolicy());
        if (policy == WithdrawPolicy.NONE) {
            throw new BusinessException("Withdraw is disabled for process: " + processKey);
        }

        // 3. Initiator check
        Long initiatorId = bpmAdapter.getProcessInitiator(processInstanceId);
        if (!currentUserId.equals(initiatorId)) {
            throw new BusinessException("Only the initiator can withdraw this process");
        }

        // 4. STRICT: reject if any task already approved
        if (policy == WithdrawPolicy.STRICT) {
            if (bpmAdapter.hasApprovedTask(processInstanceId)) {
                throw new BusinessException(
                        "Process has already approved tasks; withdraw not allowed under strict policy");
            }
        }

        // 5. Terminate
        bpmAdapter.terminateProcess(processInstanceId, "WITHDRAWN");

        // 6. Audit
        auditService.write(BpmAuditRecord.builder()
                .tenantId(String.valueOf(MetaContext.getCurrentTenantId()))
                .userId(String.valueOf(currentUserId))
                .operation("withdraw")
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .processDefinitionKey(processKey)
                .details(Map.of("reason", reason == null ? "" : reason))
                .timestamp(Instant.now())
                .result("success")
                .build());

        log.info("Process withdrawn: instanceId={}, by user {}, reason={}",
                processInstanceId, currentUserId, reason);
    }
}
```

**Note:** `SmartEngineBpmAdapter.getProcessInitiator(String)` and `hasApprovedTask(String)` and `terminateProcess(String, String)` — if they don't exist on the adapter, add thin pass-through methods. Read the adapter file first:
`platform/src/main/java/com/auraboot/framework/bpm/engine/adapter/SmartEngineBpmAdapter.java`

If any method is missing, add it here (small enough to stay within this task):
```java
public Long getProcessInitiator(String processInstanceId) { /* read from process instance variables or audit */ }
public boolean hasApprovedTask(String processInstanceId) { /* query task instances with outcome=approve */ }
```

Similarly, `BpmAuditService.write(BpmAuditRecord)` — if it's named differently (e.g., `record()` or `save()`), use the actual method name.

- [ ] **Step 4: 添加 endpoint**

Edit `platform/src/main/java/com/auraboot/framework/bpm/controller/TaskController.java`, add after the `/transfer` endpoint (around line 160):

```java
@PostMapping("/{taskId}/withdraw")
@RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
@Operation(summary = "撤回流程", description = "发起人撤回流程实例（受 withdrawPolicy 约束）")
public ApiResponse<Void> withdrawTask(
        @PathVariable String taskId,
        @RequestBody WithdrawRequest request) {
    log.info("Withdrawing process via task: {}", taskId);
    withdrawService.withdraw(taskId, request.reason());
    return ApiResponse.success();
}

public record WithdrawRequest(String reason) {}
```

Inject `private final WithdrawService withdrawService;` in the controller via `@RequiredArgsConstructor` pattern (it should already be using Lombok based on existing style).

- [ ] **Step 5: 运行测试确认通过**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew test --tests WithdrawServiceIntegrationTest 2>&1 | tee /tmp/pw-task3-step5.log
```

Expected: PASS (5 tests)

- [ ] **Step 6: 冒烟 API 测试**

```bash
# Prerequisite: backend running, workflow-demo imported
TOKEN=$(curl -s -X POST http://localhost:6443/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"Test2026x"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['jwt'])")

# Start a process then attempt withdraw — replace TASK_ID with a real task from the newly started instance
NO_PROXY=localhost curl -s -X POST \
  "http://localhost:6443/api/bpm/tasks/TASK_ID/withdraw" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "smoke test"}' | jq .
```

Expected: `{"code": 0, ...}` success response.

- [ ] **Step 7: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/bpm/service/WithdrawService.java \
        platform/src/main/java/com/auraboot/framework/bpm/controller/TaskController.java \
        platform/src/main/java/com/auraboot/framework/bpm/engine/adapter/SmartEngineBpmAdapter.java \
        platform/src/test/java/com/auraboot/framework/bpm/WithdrawServiceIntegrationTest.java \
        platform/src/test/java/com/auraboot/framework/bpm/TestBpmFixture.java
git commit -m "feat(bpm): implement withdraw endpoint with strict/loose/none policy"
```

---

## Task 4: BpmCcRecord entity + mapper

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/bpm/entity/BpmCcRecord.java`
- Create: `platform/src/main/java/com/auraboot/framework/bpm/mapper/BpmCcRecordMapper.java`

- [ ] **Step 1: 创建 entity**

```java
package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_cc_record", autoResultMap = true)
public class BpmCcRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("task_id")
    private String taskId;

    @TableField("sender_id")
    private Long senderId;

    @TableField(value = "receiver_user_ids",
            typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private List<Long> receiverUserIds;

    private String comment;

    @TableField(value = "read_state",
            typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> readState;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}
```

- [ ] **Step 2: 创建 mapper**

```java
package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.BpmCcRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BpmCcRecordMapper extends BaseMapper<BpmCcRecord> {

    @Select("""
        SELECT * FROM ab_bpm_cc_record
        WHERE tenant_id = #{tenantId}
          AND process_instance_id = #{processInstanceId}
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        ORDER BY created_at ASC
        """)
    List<BpmCcRecord> findByProcessInstance(@Param("tenantId") Long tenantId,
                                            @Param("processInstanceId") String processInstanceId);

    @Select("""
        SELECT * FROM ab_bpm_cc_record
        WHERE tenant_id = #{tenantId}
          AND receiver_user_ids @> CAST(#{userIdJson} AS JSONB)
          AND (deleted_flag = FALSE OR deleted_flag IS NULL)
        ORDER BY created_at DESC
        """)
    List<BpmCcRecord> findByReceiver(@Param("tenantId") Long tenantId,
                                     @Param("userIdJson") String userIdJson);
}
```

- [ ] **Step 3: 编译确认**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew compileJava 2>&1 | tee /tmp/pw-task4-step3.log
```

Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/bpm/entity/BpmCcRecord.java \
        platform/src/main/java/com/auraboot/framework/bpm/mapper/BpmCcRecordMapper.java
git commit -m "feat(bpm): add BpmCcRecord entity and mapper"
```

---

## Task 5: CcService + endpoint

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/bpm/service/CcService.java`
- Create: `platform/src/test/java/com/auraboot/framework/bpm/CcServiceIntegrationTest.java`
- Modify: `platform/src/main/java/com/auraboot/framework/bpm/controller/TaskController.java`

- [ ] **Step 1: 写失败的集成测试**

```java
package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmCcRecord;
import com.auraboot.framework.bpm.mapper.BpmCcRecordMapper;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.inbox.entity.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CcService")
class CcServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private CcService ccService;
    @Autowired private BpmCcRecordMapper ccMapper;
    @Autowired private InboxService inboxService;
    @Autowired private TestBpmFixture fixture;

    @Test
    @DisplayName("Policy=all, assignee sends cc: records + notifies + audits")
    void allPolicyAssigneeCc() {
        var setup = fixture.startProcess("cc-all-assignee", CcPolicy.ALL);
        fixture.switchCurrentUserTo(setup.assigneeId());

        ccService.cc(setup.taskId(), List.of(501L, 502L), "please be aware");

        List<BpmCcRecord> records = ccMapper.findByProcessInstance(
                MetaContext.getCurrentTenantId(), setup.instanceId());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getReceiverUserIds()).containsExactly(501L, 502L);
        assertThat(records.get(0).getComment()).isEqualTo("please be aware");

        var inbox501 = inboxService.listByUser(501L, MetaContext.getCurrentTenantId(),
                "bpm_cc", "active", 0, 10);
        assertThat(inbox501.getRecords()).hasSize(1);
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
    @DisplayName("Empty receivers rejected")
    void emptyReceiversRejected() {
        var setup = fixture.startProcess("cc-empty", CcPolicy.ALL);
        assertThatThrownBy(() -> ccService.cc(setup.taskId(), List.of(), "nobody"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew test --tests CcServiceIntegrationTest 2>&1 | tee /tmp/pw-task5-step2.log
```

Expected: FAIL — "CcService not found"

- [ ] **Step 3: 实现 CcService**

```java
package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditRecord;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.engine.adapter.SmartEngineBpmAdapter;
import com.auraboot.framework.bpm.entity.BpmCcRecord;
import com.auraboot.framework.bpm.mapper.BpmCcRecordMapper;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.inbox.entity.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.service.param.query.TaskInstanceQueryParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CcService {

    private final SmartEngine smartEngine;
    private final SmartEngineBpmAdapter bpmAdapter;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final BpmCcRecordMapper ccRecordMapper;
    private final InboxService inboxService;
    private final BpmAuditService auditService;

    @Transactional
    public BpmCcRecord cc(String taskId, List<Long> receiverUserIds, String comment) {
        if (receiverUserIds == null || receiverUserIds.isEmpty()) {
            throw new IllegalArgumentException("receiverUserIds must not be empty");
        }

        Long currentUserId = BpmSecurityUtil.getCurrentUserIdAsLong();
        Long tenantId = MetaContext.getCurrentTenantId();

        // Resolve task → process instance → definition
        TaskInstanceQueryParam q = new TaskInstanceQueryParam();
        q.setTaskInstanceId(taskId);
        List<TaskInstance> tasks = smartEngine.getTaskQueryService().findList(q);
        if (tasks.isEmpty()) {
            throw new BusinessException("Task not found: " + taskId);
        }
        TaskInstance task = tasks.get(0);
        String processInstanceId = task.getProcessInstanceId();
        String processKey = task.getProcessDefinitionId();

        BpmProcessDefinition def = processDefinitionMapper.selectByProcessKey(tenantId, processKey);
        if (def == null) {
            throw new BusinessException("Process definition not found: " + processKey);
        }

        // Policy gate
        CcPolicy policy = CcPolicy.fromCode(def.getCcPolicy());
        Long initiatorId = bpmAdapter.getProcessInitiator(processInstanceId);
        boolean isInitiator = currentUserId.equals(initiatorId);
        Long assigneeId = parseLongSafely(task.getAssignee());
        boolean isAssignee = currentUserId.equals(assigneeId);

        boolean allowed = switch (policy) {
            case INITIATOR -> isInitiator;
            case ASSIGNEE -> isAssignee;
            case ALL -> isInitiator || isAssignee;
        };
        if (!allowed) {
            throw new BusinessException(
                    "Current user does not satisfy cc policy: " + policy.code());
        }

        // Persist cc record
        BpmCcRecord record = BpmCcRecord.builder()
                .tenantId(tenantId)
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .senderId(currentUserId)
                .receiverUserIds(receiverUserIds)
                .comment(comment)
                .readState(new HashMap<>())
                .createdAt(Instant.now())
                .deletedFlag(false)
                .build();
        ccRecordMapper.insert(record);

        // Push Inbox notifications
        for (Long receiverId : receiverUserIds) {
            InboxItem item = InboxItem.builder()
                    .tenantId(tenantId)
                    .userId(receiverId)
                    .itemType("bpm_cc")
                    .clientItemId("bpm_cc_" + record.getId() + "_" + receiverId)
                    .title("流程抄送：" + processKey)
                    .summary(comment == null ? "" : comment)
                    .linkUrl("/p/bpm/process/" + processInstanceId)
                    .status("active")
                    .createdAt(Instant.now())
                    .build();
            inboxService.createItem(item);
        }

        // Audit
        auditService.write(BpmAuditRecord.builder()
                .tenantId(String.valueOf(tenantId))
                .userId(String.valueOf(currentUserId))
                .operation("cc")
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .processDefinitionKey(processKey)
                .details(Map.of(
                        "receiverIds", receiverUserIds,
                        "comment", comment == null ? "" : comment,
                        "ccRecordId", record.getId()))
                .timestamp(Instant.now())
                .result("success")
                .build());

        log.info("CC sent: instance={}, sender={}, receivers={}",
                processInstanceId, currentUserId, receiverUserIds);
        return record;
    }

    private Long parseLongSafely(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }
}
```

**Note:** If `InboxItem.builder()` requires different fields or `InboxService.createItem()` has a different signature, read the existing file at `platform/src/main/java/com/auraboot/framework/inbox/entity/InboxItem.java` and adjust. The core intent is: one InboxItem per receiver, itemType="bpm_cc", linkUrl points to process detail page.

- [ ] **Step 4: 添加 endpoint**

Edit `TaskController.java`, append after withdraw endpoint:

```java
@PostMapping("/{taskId}/cc")
@RequirePermission(MetaPermission.WORKFLOW_EXECUTE)
@Operation(summary = "抄送流程", description = "将流程抄送给指定用户（受 ccPolicy 约束）")
public ApiResponse<Long> ccTask(
        @PathVariable String taskId,
        @RequestBody CcRequest request) {
    log.info("CC task: {} to {}", taskId, request.receiverUserIds());
    var record = ccService.cc(taskId, request.receiverUserIds(), request.comment());
    return ApiResponse.success(record.getId());
}

public record CcRequest(List<Long> receiverUserIds, String comment) {}
```

Add `import java.util.List;` if missing. Inject `private final CcService ccService;`.

**Note:** If a `CcRequest` DTO already exists in the package (spec mentioned it), delete the old one or merge — don't keep two.

- [ ] **Step 5: 运行测试确认通过**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew test --tests CcServiceIntegrationTest 2>&1 | tee /tmp/pw-task5-step5.log
```

Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/bpm/service/CcService.java \
        platform/src/main/java/com/auraboot/framework/bpm/controller/TaskController.java \
        platform/src/test/java/com/auraboot/framework/bpm/CcServiceIntegrationTest.java
git commit -m "feat(bpm): implement cc endpoint with initiator/assignee/all policy"
```

---

## Task 6: BpmActionExecutor（action.executionMode = "bpm"）

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/action/executor/BpmActionExecutor.java`
- Create: `platform/src/test/java/com/auraboot/framework/action/BpmActionExecutorIntegrationTest.java`

- [ ] **Step 1: 写失败的集成测试**

```java
package com.auraboot.framework.action;

import com.auraboot.framework.action.executor.BpmActionExecutor;
import com.auraboot.framework.bpm.TestBpmFixture;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BpmActionExecutor")
class BpmActionExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired private BpmActionExecutor executor;
    @Autowired private TestBpmFixture fixture;

    @Test
    @DisplayName("executes action with executionMode=bpm and starts process")
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

        Object result = executor.execute(actionDef, record);

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) result;
        assertThat(r).containsKey("processInstanceId");
        assertThat(r.get("processInstanceId")).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("rejects duplicate businessKey")
    void rejectsDuplicateBusinessKey() {
        fixture.deployProcess("executor-dedup");
        Map<String, Object> actionDef = Map.of(
                "code", "submit",
                "executionMode", "bpm",
                "bpm", Map.of("processKey", "executor-dedup", "businessKeyField", "id"));
        Map<String, Object> record = Map.of("id", "rec-dup-1");

        executor.execute(actionDef, record);
        assertThatThrownBy(() -> executor.execute(actionDef, record))
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("supports() returns true for executionMode=bpm")
    void supportsDetectsBpmMode() {
        assertThat(executor.supports("bpm")).isTrue();
        assertThat(executor.supports("command")).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew test --tests BpmActionExecutorIntegrationTest 2>&1 | tee /tmp/pw-task6-step2.log
```

Expected: FAIL — "BpmActionExecutor not found"

- [ ] **Step 3: 实现 BpmActionExecutor**

```java
package com.auraboot.framework.action.executor;

import com.auraboot.framework.bpm.engine.adapter.SmartEngineBpmAdapter;
import com.auraboot.framework.exception.BusinessException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes action definitions whose {@code executionMode == "bpm"} by starting
 * a BPM process instance via SmartEngineBpmAdapter. Single-direction dependency:
 * framework/action/executor depends on framework/bpm, never the reverse.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmActionExecutor {

    private static final String EXECUTION_MODE_BPM = "bpm";

    private final SmartEngineBpmAdapter bpmAdapter;

    /** @return map with keys: processInstanceId, processKey, businessKey */
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

        // Check uniqueness: one record cannot start two instances
        if (bpmAdapter.hasRunningInstanceForBusinessKey(processKey, businessKey)) {
            throw new BusinessException(
                    "A process instance already exists for businessKey=" + businessKey);
        }

        // Extract variables via JSONPath
        Map<String, Object> variables = new HashMap<>();
        Object varsConfig = bpmConfig.get("variables");
        if (varsConfig instanceof Map<?, ?> varMap) {
            varMap.forEach((k, v) -> {
                String path = String.valueOf(v);
                Object extracted = extractByJsonPath(record, path);
                if (extracted != null) {
                    variables.put(String.valueOf(k), extracted);
                }
            });
        }

        String instanceId = bpmAdapter.startProcess(processKey, businessKey, variables);
        log.info("Started process via action executor: processKey={}, businessKey={}, instanceId={}",
                processKey, businessKey, instanceId);

        return Map.of(
                "processInstanceId", instanceId,
                "processKey", processKey,
                "businessKey", businessKey);
    }

    public boolean supports(String executionMode) {
        return EXECUTION_MODE_BPM.equalsIgnoreCase(executionMode);
    }

    private String requireString(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("action.bpm." + key + " is required");
        }
        return String.valueOf(v);
    }

    private Object extractByJsonPath(Map<String, Object> record, String path) {
        if (path == null || !path.startsWith("$")) return path;  // literal
        try {
            return JsonPath.read(record, path);
        } catch (PathNotFoundException e) {
            return null;
        }
    }
}
```

**Note:** Add to `SmartEngineBpmAdapter` if missing:
```java
public boolean hasRunningInstanceForBusinessKey(String processKey, String businessKey) { /* query SmartEngine by businessKey + running status */ }
public String startProcess(String processKey, String businessKey, Map<String, Object> variables) { /* delegate to ProcessCommandService */ }
```

Use the existing `smartEngine.getProcessCommandService().startProcessInstance(...)` call — search codebase for an existing example of `startProcessInstance` to mirror variable passing.

- [ ] **Step 4: 改 ActionDispatcher 分支 bpm**

找现有的 action 分发入口（可能是 `CompositeActionExecutor` 或 `ActionDispatcher`）。Grep:

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
grep -rn "executionMode" src/main/java/com/auraboot/framework/action/ 2>/dev/null | head -5
```

在识别出的 dispatcher 里加一个分支：
- 当 action def 的 `executionMode` 字段等于 `"bpm"`，调用 `BpmActionExecutor.execute(...)`
- 其他值走原分发

由于 dispatcher 当前具体实现未知，此步需要实现方先 read 相关 dispatcher 源码再做精准编辑。不要猜，不要加 fallback。

- [ ] **Step 5: 运行测试确认通过**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew test --tests BpmActionExecutorIntegrationTest 2>&1 | tee /tmp/pw-task6-step5.log
```

Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/action/executor/BpmActionExecutor.java \
        platform/src/main/java/com/auraboot/framework/bpm/engine/adapter/SmartEngineBpmAdapter.java \
        platform/src/test/java/com/auraboot/framework/action/BpmActionExecutorIntegrationTest.java
# include the dispatcher file(s) actually modified in step 4
git commit -m "feat(action): BpmActionExecutor handles executionMode=bpm via SmartEngine"
```

---

## Task 7: 前端 ActionDef 类型扩展 + executionMode:bpm 分支

**Files:**
- Modify: `web-admin/app/shared/dsl/types.ts`（或 ActionDef 类型所在文件）
- Modify: action execution dispatch（前端调 API 的入口，可能在 `app/shared/action/` 或 command bridge）
- Test: `web-admin/app/shared/dsl/__tests__/action-bpm.test.ts`

- [ ] **Step 1: 定位 ActionDef 类型**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
grep -rn "interface ActionDef\|type ActionDef" app/ 2>/dev/null | head -5
grep -rn "executionMode" app/ 2>/dev/null | head -10
```

记录准确路径（下面 step 2 里替换 `<ACTIONDEF_PATH>`）。

- [ ] **Step 2: 扩类型**

In the file from Step 1:

```typescript
export type ActionExecutionMode = 'command' | 'flow' | 'navigate' | 'bpm';

export interface ActionBpmConfig {
  processKey: string;
  businessKeyField: string;
  variables?: Record<string, string>;   // JSONPath values
  formBindingRef?: string;
  onSuccess?: { toast?: string; refresh?: boolean };
}

export interface ActionDef {
  // ... existing fields
  executionMode?: ActionExecutionMode;
  bpm?: ActionBpmConfig;   // required when executionMode === 'bpm'
}
```

- [ ] **Step 3: 在 action 执行入口加 bpm 分支**

找到前端按下 action 按钮后调 API 的函数（dispatch）。加分支：

```typescript
if (action.executionMode === 'bpm') {
  if (!action.bpm) {
    throw new Error(`action.bpm config required when executionMode=bpm: ${action.code}`);
  }
  const response = await fetch('/api/bpm/process-instances', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      processKey: action.bpm.processKey,
      businessKey: String(record[action.bpm.businessKeyField]),
      variables: extractVariables(record, action.bpm.variables ?? {}),
      formBindingRef: action.bpm.formBindingRef,
    }),
  });
  const result = await response.json();
  if (result.code !== 0) throw new Error(result.message);
  if (action.bpm.onSuccess?.toast) toast.success(action.bpm.onSuccess.toast);
  if (action.bpm.onSuccess?.refresh) eventBus.emit('record:refresh');
  return result.data;
}
```

`extractVariables` helper — JSONPath extraction on the client; use a small hand-rolled implementation since full JSONPath isn't needed (only `$.field` or `$.nested.field`):

```typescript
function extractVariables(record: Record<string, unknown>, paths: Record<string, string>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [varName, path] of Object.entries(paths)) {
    if (!path.startsWith('$.')) { out[varName] = path; continue; }
    const parts = path.slice(2).split('.');
    let v: unknown = record;
    for (const p of parts) {
      if (v && typeof v === 'object' && p in (v as object)) {
        v = (v as Record<string, unknown>)[p];
      } else { v = null; break; }
    }
    if (v !== null) out[varName] = v;
  }
  return out;
}
```

- [ ] **Step 4: 写单元测试**

```typescript
import { describe, it, expect, vi } from 'vitest';
import { dispatchAction } from '<actual-dispatch-path>';

describe('action.executionMode=bpm', () => {
  it('POSTs /api/bpm/process-instances with extracted variables', async () => {
    const fetchSpy = vi.spyOn(global, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ code: 0, data: { processInstanceId: 'pi-1' } })));
    const action = {
      code: 'submit', executionMode: 'bpm' as const,
      bpm: {
        processKey: 'leave_request',
        businessKeyField: 'id',
        variables: { days: '$.days', reason: '$.reason' },
      },
    };
    const record = { id: 'rec-1', days: 3, reason: 'vacation' };

    await dispatchAction(action, record);

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/bpm/process-instances',
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"processKey":"leave_request"'),
      })
    );
    const body = JSON.parse((fetchSpy.mock.calls[0][1] as RequestInit).body as string);
    expect(body.variables).toEqual({ days: 3, reason: 'vacation' });
  });

  it('throws when action.bpm is missing', async () => {
    await expect(dispatchAction({ code: 'x', executionMode: 'bpm' } as never, {})).rejects.toThrow(/bpm config/);
  });
});
```

- [ ] **Step 5: 运行 tsc + 测试**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
npx tsc --noEmit 2>&1 | tee /tmp/pw-task7-step5a.log
pnpm test:unit -- action-bpm 2>&1 | tee /tmp/pw-task7-step5b.log
```

Expected: no tsc errors; 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/shared/dsl/types.ts \
        web-admin/app/shared/action/  \
        web-admin/app/shared/dsl/__tests__/action-bpm.test.ts
# adjust paths to actual files modified
git commit -m "feat(web): ActionDef supports executionMode=bpm with bpm config"
```

---

## Task 8: bpm-panel block 骨架 + 注册

**Files:**
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/index.ts`
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx`
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/schema.ts`
- Modify: `web-admin/app/plugins/core-designer/components/studio/registry/blocks/index.ts`

- [ ] **Step 1: 创建 schema.ts（PropertySchema）**

```typescript
import type { PropertySchema } from '~/shared/designer/types';

export const bpmPanelSchema: PropertySchema<string>[] = [
  { key: 'bpm.processKey', label: 'Process Key', type: 'select',
    required: true, group: 'BPM',
    // options loaded from /api/bpm/process-definitions at runtime via 'remote-options' widget
  },
  { key: 'bpm.businessKeyField', label: 'Business Key Field', type: 'field-selector',
    required: true, group: 'BPM' },

  { key: 'bpm.sections.status.visible', label: 'Show Status', type: 'boolean',
    defaultValue: true, group: 'Status' },
  { key: 'bpm.sections.status.showAssignees', label: 'Show Assignees', type: 'boolean',
    defaultValue: true, group: 'Status',
    dependsOn: { field: 'bpm.sections.status.visible', value: true } },
  { key: 'bpm.sections.status.showSla', label: 'Show SLA Countdown', type: 'boolean',
    defaultValue: true, group: 'Status',
    dependsOn: { field: 'bpm.sections.status.visible', value: true } },

  { key: 'bpm.sections.diagram.visible', label: 'Show Diagram', type: 'boolean',
    defaultValue: true, group: 'Diagram' },
  { key: 'bpm.sections.diagram.highlightActiveNode', label: 'Highlight Active Node', type: 'boolean',
    defaultValue: true, group: 'Diagram',
    dependsOn: { field: 'bpm.sections.diagram.visible', value: true } },

  { key: 'bpm.sections.operations.visible', label: 'Show Operations', type: 'boolean',
    defaultValue: true, group: 'Operations' },
  { key: 'bpm.sections.operations.operations', label: 'Enabled Operations', type: 'multi-select',
    group: 'Operations',
    defaultValue: ['approve', 'reject', 'addSign', 'transfer', 'withdraw', 'cc'],
    options: [
      { label: 'Approve', value: 'approve' },
      { label: 'Reject', value: 'reject' },
      { label: 'Add Sign', value: 'addSign' },
      { label: 'Transfer', value: 'transfer' },
      { label: 'Withdraw', value: 'withdraw' },
      { label: 'CC', value: 'cc' },
    ],
    dependsOn: { field: 'bpm.sections.operations.visible', value: true } },

  { key: 'bpm.sections.history.visible', label: 'Show History', type: 'boolean',
    defaultValue: true, group: 'History' },
  { key: 'bpm.sections.history.showComments', label: 'Show Comments', type: 'boolean',
    defaultValue: true, group: 'History',
    dependsOn: { field: 'bpm.sections.history.visible', value: true } },
  { key: 'bpm.sections.history.showAttachments', label: 'Show Attachments', type: 'boolean',
    defaultValue: true, group: 'History',
    dependsOn: { field: 'bpm.sections.history.visible', value: true } },

  { key: 'visibleWhen', label: 'Visible when (expression)', type: 'expression',
    defaultValue: 'record.process_instance_id != null', group: 'Conditions' },
];
```

If `'multi-select'` or `'field-selector'` or `'remote-options'` widget types don't yet exist in `PropertyType`, add them to the enum in `app/shared/designer/types.ts` — they're needed features not hacks.

- [ ] **Step 2: 创建骨架 block component**

```tsx
import React from 'react';

interface BpmPanelConfig {
  bpm: {
    processKey: string;
    businessKeyField: string;
    sections: {
      status?: { visible: boolean; showAssignees?: boolean; showSla?: boolean };
      diagram?: { visible: boolean; highlightActiveNode?: boolean };
      operations?: { visible: boolean; operations?: string[] };
      history?: { visible: boolean; showComments?: boolean; showAttachments?: boolean };
    };
  };
  visibleWhen?: string;
}

interface BpmPanelBlockProps {
  config: BpmPanelConfig;
  record: Record<string, unknown>;
}

export function BpmPanelBlock({ config, record }: BpmPanelBlockProps) {
  const instanceId = record.process_instance_id as string | undefined;
  if (!instanceId) return null;

  const sections = config.bpm.sections ?? {};

  return (
    <div className="bpm-panel" data-testid="bpm-panel">
      {sections.status?.visible && (
        <div data-testid="bpm-panel-status">STATUS (Task 9)</div>
      )}
      {sections.diagram?.visible && (
        <div data-testid="bpm-panel-diagram">DIAGRAM (Task 10)</div>
      )}
      {sections.operations?.visible && (
        <div data-testid="bpm-panel-operations">OPERATIONS (Task 11)</div>
      )}
      {sections.history?.visible && (
        <div data-testid="bpm-panel-history">HISTORY (Task 12)</div>
      )}
    </div>
  );
}
```

- [ ] **Step 3: 创建 block 定义入口**

```typescript
import type { BlockDefinition } from '~/plugins/core-designer/components/studio/registry/block-registry';
import { bpmPanelSchema } from './schema';
import { BpmPanelBlock } from './BpmPanelBlock';

export const bpmPanelBlock: BlockDefinition = {
  type: 'bpm-panel',
  name: 'BPM Panel',
  icon: '✅',
  description: 'Approval status + diagram + operations + history for a BPM process instance',
  category: 'workflow',
  defaultColSpan: 6,
  schema: bpmPanelSchema,
  component: BpmPanelBlock,
};
```

（若现有 `BlockDefinition` 类型没有 `component` 字段，改为项目实际约定的 render 注册方式——读 `tableBlock` 等的导出结构作参照。）

- [ ] **Step 4: 注册到全局 registry**

Edit `web-admin/app/plugins/core-designer/components/studio/registry/blocks/index.ts`:

```typescript
import { bpmPanelBlock } from '~/plugins/core-bpm/blocks/bpm-panel';

export function registerAllBlocks(): void {
  BlockRegistry.register(tableBlock);
  // ... existing registrations ...
  BlockRegistry.register(bpmPanelBlock);   // new
}
```

- [ ] **Step 5: 运行 tsc**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
npx tsc --noEmit 2>&1 | tee /tmp/pw-task8-step5.log
```

Expected: 0 new errors.

- [ ] **Step 6: 单元测试骨架渲染**

Create `web-admin/app/plugins/core-bpm/blocks/bpm-panel/__tests__/BpmPanelBlock.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { BpmPanelBlock } from '../BpmPanelBlock';

describe('BpmPanelBlock skeleton', () => {
  const baseConfig = {
    bpm: {
      processKey: 'leave', businessKeyField: 'id',
      sections: {
        status: { visible: true }, diagram: { visible: true },
        operations: { visible: true }, history: { visible: true },
      },
    },
  };

  it('renders nothing when record has no process_instance_id', () => {
    render(<BpmPanelBlock config={baseConfig} record={{ id: 1 }} />);
    expect(screen.queryByTestId('bpm-panel')).toBeNull();
  });

  it('renders all 4 sections when record has process_instance_id', () => {
    render(<BpmPanelBlock config={baseConfig} record={{ id: 1, process_instance_id: 'pi-1' }} />);
    expect(screen.getByTestId('bpm-panel-status')).toBeInTheDocument();
    expect(screen.getByTestId('bpm-panel-diagram')).toBeInTheDocument();
    expect(screen.getByTestId('bpm-panel-operations')).toBeInTheDocument();
    expect(screen.getByTestId('bpm-panel-history')).toBeInTheDocument();
  });

  it('hides individual sections when visible=false', () => {
    const config = { ...baseConfig, bpm: { ...baseConfig.bpm,
      sections: { ...baseConfig.bpm.sections, diagram: { visible: false } } } };
    render(<BpmPanelBlock config={config} record={{ id: 1, process_instance_id: 'pi-1' }} />);
    expect(screen.queryByTestId('bpm-panel-diagram')).toBeNull();
  });
});
```

Run:
```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
pnpm test:unit -- BpmPanelBlock 2>&1 | tee /tmp/pw-task8-step6.log
```

Expected: 3 tests pass.

- [ ] **Step 7: Commit**

```bash
git add web-admin/app/plugins/core-bpm/blocks/bpm-panel/ \
        web-admin/app/plugins/core-designer/components/studio/registry/blocks/index.ts
git commit -m "feat(web): register bpm-panel block skeleton with 4 section placeholders"
```

---

## Task 9: BpmStatusSection — 当前节点 + 审批人 + SLA

**Files:**
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmStatusSection.tsx`
- Modify: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx`

- [ ] **Step 1: 读取后端 status API 响应 shape**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
grep -rn "/process-instances/.*/status\|getProcessStatus" src/main/java/com/auraboot/framework/bpm/ 2>/dev/null | head -5
```

如果 endpoint 不存在，这里需**先加后端 endpoint**（2 个 step）：

```java
// In ProcessDefinitionController or new ProcessInstanceController:
@GetMapping("/process-instances/{instanceId}/status")
public ApiResponse<ProcessStatusResponse> getStatus(@PathVariable String instanceId) {
    return ApiResponse.success(bpmAdapter.getProcessStatus(instanceId));
}

public record ProcessStatusResponse(
    String instanceId, String processKey, String currentNodeId, String currentNodeName,
    List<Long> currentAssigneeIds, String status, Instant slaDeadline) {}
```

记：若已存在等价 endpoint，直接复用。

- [ ] **Step 2: 写 status section 组件**

```tsx
import React, { useEffect, useState } from 'react';

interface ProcessStatus {
  instanceId: string;
  processKey: string;
  currentNodeId: string;
  currentNodeName: string;
  currentAssigneeIds: number[];
  status: string;
  slaDeadline: string | null;  // ISO-8601 or null
}

interface Props {
  instanceId: string;
  showAssignees?: boolean;
  showSla?: boolean;
}

export function BpmStatusSection({ instanceId, showAssignees = true, showSla = true }: Props) {
  const [status, setStatus] = useState<ProcessStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(`/api/bpm/process-instances/${instanceId}/status`)
      .then(r => r.json())
      .then(r => {
        if (r.code !== 0) throw new Error(r.message);
        setStatus(r.data);
      })
      .catch(e => setError(e.message));
  }, [instanceId]);

  if (error) return <div data-testid="bpm-panel-status" className="error">Error: {error}</div>;
  if (!status) return <div data-testid="bpm-panel-status" className="loading">Loading status...</div>;

  return (
    <div data-testid="bpm-panel-status" className="bpm-status">
      <span className="label">Current Node:</span>
      <span className="value">{status.currentNodeName}</span>
      {showAssignees && status.currentAssigneeIds?.length > 0 && (
        <span data-testid="bpm-status-assignees" className="assignees">
          {status.currentAssigneeIds.map(id => <AssigneeAvatar key={id} userId={id} />)}
        </span>
      )}
      {showSla && status.slaDeadline && (
        <span data-testid="bpm-status-sla" className="sla-countdown">
          <SlaCountdown deadline={status.slaDeadline} />
        </span>
      )}
    </div>
  );
}

function AssigneeAvatar({ userId }: { userId: number }) {
  return <span className="avatar">👤 {userId}</span>;  // replace with real user-display lookup
}

function SlaCountdown({ deadline }: { deadline: string }) {
  const remaining = new Date(deadline).getTime() - Date.now();
  if (remaining < 0) return <span className="overdue">Overdue</span>;
  const hrs = Math.floor(remaining / 3600_000);
  return <span>⏱ {hrs}h left</span>;
}
```

- [ ] **Step 3: 集成到 BpmPanelBlock**

Replace the placeholder div with:
```tsx
{sections.status?.visible && (
  <BpmStatusSection
    instanceId={instanceId}
    showAssignees={sections.status.showAssignees ?? true}
    showSla={sections.status.showSla ?? true}
  />
)}
```

- [ ] **Step 4: 单元测试**

```tsx
// BpmStatusSection.test.tsx
import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { BpmStatusSection } from '../BpmStatusSection';

describe('BpmStatusSection', () => {
  it('renders current node name from API', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      code: 0, data: {
        instanceId: 'pi-1', processKey: 'leave',
        currentNodeId: 'n1', currentNodeName: 'Manager Approval',
        currentAssigneeIds: [100], status: 'running',
        slaDeadline: new Date(Date.now() + 2 * 3600_000).toISOString(),
      }
    })));

    render(<BpmStatusSection instanceId="pi-1" />);

    await waitFor(() => expect(screen.getByText('Manager Approval')).toBeInTheDocument());
    expect(screen.getByTestId('bpm-status-assignees')).toBeInTheDocument();
    expect(screen.getByTestId('bpm-status-sla')).toBeInTheDocument();
  });

  it('hides assignees when showAssignees=false', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      code: 0, data: { instanceId: 'pi-1', processKey: 'x',
        currentNodeId: 'n', currentNodeName: 'X',
        currentAssigneeIds: [1], status: 'running', slaDeadline: null }
    })));
    render(<BpmStatusSection instanceId="pi-1" showAssignees={false} />);
    await waitFor(() => expect(screen.getByText('X')).toBeInTheDocument());
    expect(screen.queryByTestId('bpm-status-assignees')).toBeNull();
  });
});
```

- [ ] **Step 5: 运行测试**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
pnpm test:unit -- BpmStatusSection 2>&1 | tee /tmp/pw-task9-step5.log
```

Expected: 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmStatusSection.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/__tests__/ \
        platform/src/main/java/com/auraboot/framework/bpm/controller/ # if new controller/endpoint added
git commit -m "feat(web): bpm-panel status section with assignees and SLA countdown"
```

---

## Task 10: BpmDiagramSection — 只读 BPMN 图 + 高亮当前节点

**Files:**
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmDiagramSection.tsx`
- Modify: `BpmPanelBlock.tsx`

- [ ] **Step 1: 查找现有 BPMN viewer**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
grep -rn "BpmnViewer\|bpmn-designer.*viewer\|readonly.*bpmn" app/plugins/core-designer/ 2>/dev/null | head -10
```

记住 viewer 组件路径和必要 props（大概率是 `BpmnDesigner` + `mode="view"` 或类似）。

- [ ] **Step 2: 写 diagram section**

```tsx
import React, { useEffect, useState } from 'react';
import { BpmnViewer } from '~/plugins/core-designer/components/bpmn-designer/BpmnViewer';  // actual path from step 1

interface Props {
  instanceId: string;
  processKey: string;
  highlightActiveNode?: boolean;
}

export function BpmDiagramSection({ instanceId, processKey, highlightActiveNode = true }: Props) {
  const [designerJson, setDesignerJson] = useState<unknown | null>(null);
  const [activeNodeId, setActiveNodeId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Fetch process definition (to get designerJson)
    Promise.all([
      fetch(`/api/bpm/process-definitions?processKey=${processKey}`).then(r => r.json()),
      fetch(`/api/bpm/process-instances/${instanceId}/status`).then(r => r.json()),
    ]).then(([defResp, statusResp]) => {
      if (defResp.code !== 0) throw new Error(defResp.message);
      if (statusResp.code !== 0) throw new Error(statusResp.message);
      // The designer JSON lives on extension.designerJson — verify by reading backend
      const def = defResp.data?.[0] ?? defResp.data;
      const dj = def?.extension?.designerJson;
      setDesignerJson(typeof dj === 'string' ? JSON.parse(dj) : dj);
      if (highlightActiveNode) setActiveNodeId(statusResp.data.currentNodeId);
    }).catch(e => setError(e.message));
  }, [instanceId, processKey, highlightActiveNode]);

  if (error) return <div data-testid="bpm-panel-diagram" className="error">{error}</div>;
  if (!designerJson) return <div data-testid="bpm-panel-diagram" className="loading">Loading...</div>;

  return (
    <div data-testid="bpm-panel-diagram" className="bpm-diagram">
      <BpmnViewer designerJson={designerJson} highlightNodeId={activeNodeId} />
    </div>
  );
}
```

- [ ] **Step 3: BpmnViewer 不存在则创建最小 read-only wrapper**

如果 Step 1 没找到 viewer，创建：

```tsx
// web-admin/app/plugins/core-designer/components/bpmn-designer/BpmnViewer.tsx
import React from 'react';
import { ReactFlow, Background, Controls } from '@xyflow/react';
import '@xyflow/react/dist/style.css';

interface DesignerJson { nodes: unknown[]; edges: unknown[] }

interface Props {
  designerJson: DesignerJson;
  highlightNodeId?: string | null;
}

export function BpmnViewer({ designerJson, highlightNodeId }: Props) {
  const nodes = (designerJson.nodes as any[]).map(n => ({
    ...n,
    style: n.id === highlightNodeId ? { ...n.style, border: '2px solid #3b82f6', background: '#eff6ff' } : n.style,
  }));
  return (
    <div style={{ width: '100%', height: 300 }}>
      <ReactFlow nodes={nodes} edges={designerJson.edges as any[]} nodesDraggable={false}
                 nodesConnectable={false} elementsSelectable={false}>
        <Background /><Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
```

If the existing `BpmnDesigner` already supports a read-only mode, use that instead — don't duplicate.

- [ ] **Step 4: 集成到 BpmPanelBlock**

Replace placeholder:
```tsx
{sections.diagram?.visible && (
  <BpmDiagramSection
    instanceId={instanceId}
    processKey={config.bpm.processKey}
    highlightActiveNode={sections.diagram.highlightActiveNode ?? true}
  />
)}
```

- [ ] **Step 5: 单元测试**

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { BpmDiagramSection } from '../BpmDiagramSection';

vi.mock('~/plugins/core-designer/components/bpmn-designer/BpmnViewer', () => ({
  BpmnViewer: ({ highlightNodeId }: { highlightNodeId: string | null }) =>
    <div data-testid="viewer-mock">highlight={highlightNodeId ?? 'none'}</div>,
}));

describe('BpmDiagramSection', () => {
  it('passes currentNodeId as highlight', async () => {
    vi.spyOn(global, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: 0, data: [{ extension: { designerJson: '{"nodes":[],"edges":[]}' } }]
      })))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: 0, data: { currentNodeId: 'node-2', instanceId: 'pi-1' }
      })));

    render(<BpmDiagramSection instanceId="pi-1" processKey="leave" />);
    await waitFor(() => expect(screen.getByTestId('viewer-mock')).toHaveTextContent('highlight=node-2'));
  });

  it('does not highlight when highlightActiveNode=false', async () => {
    vi.spyOn(global, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 0, data: [{ extension: { designerJson: '{"nodes":[],"edges":[]}' } }] })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 0, data: { currentNodeId: 'n-2', instanceId: 'pi-1' } })));
    render(<BpmDiagramSection instanceId="pi-1" processKey="x" highlightActiveNode={false} />);
    await waitFor(() => expect(screen.getByTestId('viewer-mock')).toHaveTextContent('highlight=none'));
  });
});
```

- [ ] **Step 6: 运行 + commit**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
pnpm test:unit -- BpmDiagramSection 2>&1 | tee /tmp/pw-task10-step6.log
# commit
git add web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmDiagramSection.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/__tests__/BpmDiagramSection.test.tsx
# include BpmnViewer if newly created
git commit -m "feat(web): bpm-panel diagram section with active node highlight"
```

---

## Task 11: BpmOperationsSection — 6 按钮 + WithdrawDialog + CcDialog

**Files:**
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmOperationsSection.tsx`
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/dialogs/WithdrawDialog.tsx`
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/dialogs/CcDialog.tsx`
- Modify: `BpmPanelBlock.tsx`

- [ ] **Step 1: 先写 WithdrawDialog**

```tsx
import React, { useState } from 'react';

interface Props {
  taskId: string;
  onClose: () => void;
  onSuccess: () => void;
}

export function WithdrawDialog({ taskId, onClose, onSuccess }: Props) {
  const [reason, setReason] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const doWithdraw = async () => {
    setSubmitting(true);
    try {
      const r = await fetch(`/api/bpm/tasks/${taskId}/withdraw`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason }),
      });
      const result = await r.json();
      if (result.code !== 0) throw new Error(result.message);
      onSuccess();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div data-testid="withdraw-dialog" role="dialog">
      <h3>确认撤回流程？</h3>
      <textarea value={reason} onChange={e => setReason(e.target.value)}
                placeholder="撤回原因（可选）" data-testid="withdraw-reason" />
      {error && <div className="error" data-testid="withdraw-error">{error}</div>}
      <div className="actions">
        <button onClick={onClose} disabled={submitting}>取消</button>
        {!confirming ? (
          <button onClick={() => setConfirming(true)} data-testid="withdraw-confirm-start">撤回</button>
        ) : (
          <button onClick={doWithdraw} disabled={submitting}
                  data-testid="withdraw-confirm" className="danger">
            {submitting ? '撤回中...' : '确认撤回'}
          </button>
        )}
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 写 CcDialog**

```tsx
import React, { useState } from 'react';

interface Props {
  taskId: string;
  onClose: () => void;
  onSuccess: () => void;
}

export function CcDialog({ taskId, onClose, onSuccess }: Props) {
  const [receiverIds, setReceiverIds] = useState<number[]>([]);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const doCc = async () => {
    if (receiverIds.length === 0) { setError('至少选择一位接收人'); return; }
    setSubmitting(true);
    try {
      const r = await fetch(`/api/bpm/tasks/${taskId}/cc`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ receiverUserIds: receiverIds, comment }),
      });
      const result = await r.json();
      if (result.code !== 0) throw new Error(result.message);
      onSuccess();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div data-testid="cc-dialog" role="dialog">
      <h3>抄送流程给...</h3>
      <UserPicker value={receiverIds} onChange={setReceiverIds} data-testid="cc-receivers" />
      <textarea value={comment} onChange={e => setComment(e.target.value)}
                placeholder="留言（可选）" data-testid="cc-comment" />
      {error && <div className="error" data-testid="cc-error">{error}</div>}
      <div className="actions">
        <button onClick={onClose} disabled={submitting}>取消</button>
        <button onClick={doCc} disabled={submitting || receiverIds.length === 0}
                data-testid="cc-submit">
          {submitting ? '抄送中...' : '抄送'}
        </button>
      </div>
    </div>
  );
}

// Use the project's existing user picker component — grep for it
function UserPicker({ value, onChange }: { value: number[]; onChange: (v: number[]) => void }) {
  return <div data-testid="user-picker-stub">[UserPicker not wired — TODO: use existing project component]</div>;
}
```

**Note:** There is almost certainly an existing user picker in the project. Before committing, run:
```bash
grep -rn "UserPicker\|UserSelector\|SelectUser" web-admin/app/ 2>/dev/null | head -5
```
and replace `UserPicker` above with the real import. If none exists, keep the stub but mark as a follow-up TODO in commit message.

- [ ] **Step 3: 写 BpmOperationsSection**

```tsx
import React, { useState, useEffect } from 'react';
import { WithdrawDialog } from './dialogs/WithdrawDialog';
import { CcDialog } from './dialogs/CcDialog';

interface Props {
  instanceId: string;
  taskId: string | null;
  enabledOps: string[];
  onRefresh: () => void;
}

interface Permissions {
  approve: boolean; reject: boolean; addSign: boolean;
  transfer: boolean; withdraw: boolean; cc: boolean;
}

export function BpmOperationsSection({ instanceId, taskId, enabledOps, onRefresh }: Props) {
  const [perms, setPerms] = useState<Permissions | null>(null);
  const [activeDialog, setActiveDialog] = useState<string | null>(null);

  useEffect(() => {
    if (!instanceId) return;
    fetch(`/api/bpm/process-instances/${instanceId}/my-permissions`)
      .then(r => r.json())
      .then(r => setPerms(r.data));
  }, [instanceId]);

  const canShow = (op: string): boolean =>
    enabledOps.includes(op) && (perms?.[op as keyof Permissions] ?? false);

  return (
    <div data-testid="bpm-panel-operations" className="bpm-operations">
      {canShow('approve') && <button data-testid="op-approve">通过</button>}
      {canShow('reject') && <button data-testid="op-reject">驳回</button>}
      {canShow('addSign') && <button data-testid="op-add-sign">加签</button>}
      {canShow('transfer') && <button data-testid="op-transfer">转办</button>}
      {canShow('withdraw') && (
        <button data-testid="op-withdraw" onClick={() => setActiveDialog('withdraw')}>撤回</button>
      )}
      {canShow('cc') && (
        <button data-testid="op-cc" onClick={() => setActiveDialog('cc')}>抄送</button>
      )}

      {activeDialog === 'withdraw' && taskId && (
        <WithdrawDialog taskId={taskId} onClose={() => setActiveDialog(null)}
                        onSuccess={() => { setActiveDialog(null); onRefresh(); }} />
      )}
      {activeDialog === 'cc' && taskId && (
        <CcDialog taskId={taskId} onClose={() => setActiveDialog(null)}
                  onSuccess={() => { setActiveDialog(null); onRefresh(); }} />
      )}
    </div>
  );
}
```

**Note:** The `/api/bpm/process-instances/{id}/my-permissions` endpoint doesn't exist yet — this is where **身份推导 + IAM 覆盖** lives. Add it as part of this task:

Create `platform/src/main/java/com/auraboot/framework/bpm/service/BpmPermissionService.java`:
```java
@Service
@RequiredArgsConstructor
public class BpmPermissionService {
    private final SmartEngineBpmAdapter bpmAdapter;
    private final BpmProcessDefinitionMapper processDefinitionMapper;

    public Map<String, Boolean> resolveMyPermissions(String processInstanceId) {
        Long userId = BpmSecurityUtil.getCurrentUserIdAsLong();
        Long tenantId = MetaContext.getCurrentTenantId();

        Long initiatorId = bpmAdapter.getProcessInitiator(processInstanceId);
        boolean isInitiator = userId.equals(initiatorId);
        boolean isAssignee = bpmAdapter.isTaskAssignee(processInstanceId, userId);
        boolean isCcRecipient = bpmAdapter.isCcRecipient(processInstanceId, userId);

        String processKey = bpmAdapter.getProcessKey(processInstanceId);
        BpmProcessDefinition def = processDefinitionMapper.selectByProcessKey(tenantId, processKey);
        WithdrawPolicy wp = WithdrawPolicy.fromCode(def == null ? null : def.getWithdrawPolicy());
        CcPolicy cp = CcPolicy.fromCode(def == null ? null : def.getCcPolicy());

        // Base identity derivation
        Map<String, Boolean> p = new HashMap<>();
        p.put("approve", isAssignee);
        p.put("reject", isAssignee);
        p.put("addSign", isAssignee);
        p.put("transfer", isAssignee);
        p.put("withdraw", wp != WithdrawPolicy.NONE && isInitiator);
        p.put("cc", switch (cp) {
            case INITIATOR -> isInitiator;
            case ASSIGNEE -> isAssignee;
            case ALL -> isInitiator || isAssignee;
        });

        // IAM override (requiredPermissions) — AND in required permission checks
        if (def != null && def.getRequiredPermissions() != null) {
            for (Map.Entry<String, Object> entry : def.getRequiredPermissions().entrySet()) {
                String op = entry.getKey();
                String permKey = String.valueOf(entry.getValue());
                if (p.getOrDefault(op, false) && !PermissionUtil.hasPermission(permKey)) {
                    p.put(op, false);
                }
            }
        }
        return p;
    }
}
```

And endpoint:
```java
@GetMapping("/process-instances/{instanceId}/my-permissions")
@RequirePermission(MetaPermission.WORKFLOW_VIEW)
public ApiResponse<Map<String, Boolean>> myPermissions(@PathVariable String instanceId) {
    return ApiResponse.success(bpmPermissionService.resolveMyPermissions(instanceId));
}
```

`PermissionUtil.hasPermission(String)` — mirror existing permission check pattern in the codebase.

- [ ] **Step 4: 集成到 BpmPanelBlock + refresh 事件**

```tsx
const [refreshKey, setRefreshKey] = useState(0);
const forceRefresh = () => setRefreshKey(k => k + 1);

// ...
{sections.operations?.visible && (
  <BpmOperationsSection
    key={refreshKey}
    instanceId={instanceId}
    taskId={/* fetch from status API or via record.current_task_id */}
    enabledOps={sections.operations.operations ?? []}
    onRefresh={forceRefresh}
  />
)}
```

- [ ] **Step 5: 单元测试（基于按钮可见性 + dialog 开关）**

```tsx
// BpmOperationsSection.test.tsx
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { BpmOperationsSection } from '../BpmOperationsSection';

const mockPerms = (p: Partial<Record<string, boolean>>) => {
  vi.spyOn(global, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ code: 0, data: {
      approve: false, reject: false, addSign: false, transfer: false, withdraw: false, cc: false, ...p
    }})));
};

describe('BpmOperationsSection', () => {
  beforeEach(() => vi.clearAllMocks());

  it('only shows buttons user has permission for AND are in enabledOps', async () => {
    mockPerms({ approve: true, withdraw: true });
    render(<BpmOperationsSection instanceId="pi-1" taskId="t-1"
             enabledOps={['approve', 'reject', 'withdraw']} onRefresh={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('op-approve')).toBeInTheDocument());
    expect(screen.queryByTestId('op-reject')).toBeNull();   // no perm
    expect(screen.getByTestId('op-withdraw')).toBeInTheDocument();
    expect(screen.queryByTestId('op-cc')).toBeNull();        // not in enabledOps
  });

  it('opens withdraw dialog on button click', async () => {
    mockPerms({ withdraw: true });
    render(<BpmOperationsSection instanceId="pi-1" taskId="t-1"
             enabledOps={['withdraw']} onRefresh={() => {}} />);
    await waitFor(() => screen.getByTestId('op-withdraw'));
    fireEvent.click(screen.getByTestId('op-withdraw'));
    expect(screen.getByTestId('withdraw-dialog')).toBeInTheDocument();
  });
});
```

- [ ] **Step 6: 运行 + commit**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
pnpm test:unit -- BpmOperationsSection 2>&1 | tee /tmp/pw-task11-step6a.log
# also run integration test to ensure permission endpoint wiring
cd ../platform
./gradlew compileJava 2>&1 | tee /tmp/pw-task11-step6b.log
# commit
cd ..
git add web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmOperationsSection.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/dialogs/ \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/__tests__/BpmOperationsSection.test.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx \
        platform/src/main/java/com/auraboot/framework/bpm/service/BpmPermissionService.java \
        platform/src/main/java/com/auraboot/framework/bpm/controller/
git commit -m "feat(bpm): operations section with 6 buttons, permission resolution, withdraw/cc dialogs"
```

---

## Task 12: BpmHistorySection — 审计时间线含 cc 事件

**Files:**
- Create: `web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmHistorySection.tsx`
- Modify: `BpmPanelBlock.tsx`
- Modify: `platform/src/main/java/com/auraboot/framework/bpm/audit/BpmAuditService.java`（如需 endpoint）

- [ ] **Step 1: 确认审计列表 endpoint**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
grep -rn "audit-trail\|auditService.findByProcessInstance" src/main/java/com/auraboot/framework/bpm/ 2>/dev/null | head -5
```

如果没有列表 endpoint，在 audit controller/service 里加：

```java
@GetMapping("/process-instances/{instanceId}/audit-trail")
public ApiResponse<List<BpmAuditRecord>> auditTrail(@PathVariable String instanceId) {
    return ApiResponse.success(auditService.findByProcessInstance(instanceId));
}
```

- [ ] **Step 2: 写 BpmHistorySection**

```tsx
import React, { useEffect, useState } from 'react';

interface AuditRecord {
  id: number;
  operation: string;   // approve | reject | withdraw | cc | addSign | transfer | ...
  userId: string;
  timestamp: string;
  details: Record<string, unknown>;
}

interface Props {
  instanceId: string;
  showComments?: boolean;
  showAttachments?: boolean;
}

const OP_LABEL: Record<string, string> = {
  approve: '✅ 通过', reject: '❌ 驳回', withdraw: '↩️ 撤回', cc: '📧 抄送',
  addSign: '➕ 加签', transfer: '🔀 转办', submit: '📤 提交',
};

export function BpmHistorySection({ instanceId, showComments = true, showAttachments = true }: Props) {
  const [records, setRecords] = useState<AuditRecord[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(`/api/bpm/process-instances/${instanceId}/audit-trail`)
      .then(r => r.json())
      .then(r => {
        if (r.code !== 0) throw new Error(r.message);
        setRecords(r.data);
      })
      .catch(e => setError(e.message));
  }, [instanceId]);

  if (error) return <div data-testid="bpm-panel-history" className="error">{error}</div>;

  return (
    <div data-testid="bpm-panel-history" className="bpm-history">
      <h4>审批历史</h4>
      {records.length === 0 && <div className="empty">暂无记录</div>}
      <ul className="timeline">
        {records.map(r => (
          <li key={r.id} data-testid={`history-item-${r.operation}`}>
            <span className="op">{OP_LABEL[r.operation] ?? r.operation}</span>
            <span className="user">user {r.userId}</span>
            <span className="time">{new Date(r.timestamp).toLocaleString()}</span>
            {showComments && r.details?.comment != null && (
              <div className="comment">{String(r.details.comment)}</div>
            )}
            {r.operation === 'cc' && (
              <div className="cc-detail">
                抄送给: {JSON.stringify(r.details?.receiverIds ?? [])}
              </div>
            )}
            {showAttachments && Array.isArray(r.details?.attachments) && (
              <div className="attachments">
                {(r.details.attachments as unknown[]).length} attachment(s)
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
```

- [ ] **Step 3: 集成 + 单元测试**

Replace placeholder in BpmPanelBlock:
```tsx
{sections.history?.visible && (
  <BpmHistorySection
    instanceId={instanceId}
    showComments={sections.history.showComments ?? true}
    showAttachments={sections.history.showAttachments ?? true}
  />
)}
```

Test:
```tsx
import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { BpmHistorySection } from '../BpmHistorySection';

describe('BpmHistorySection', () => {
  it('renders all audit operations including cc', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      code: 0, data: [
        { id: 1, operation: 'submit',  userId: '100', timestamp: '2026-01-01T10:00:00Z', details: {} },
        { id: 2, operation: 'approve', userId: '101', timestamp: '2026-01-01T11:00:00Z',
          details: { comment: 'lgtm' } },
        { id: 3, operation: 'cc',      userId: '101', timestamp: '2026-01-01T11:05:00Z',
          details: { receiverIds: [200, 201], comment: 'fyi' } },
      ]
    })));

    render(<BpmHistorySection instanceId="pi-1" />);

    await waitFor(() => expect(screen.getByTestId('history-item-submit')).toBeInTheDocument());
    expect(screen.getByTestId('history-item-approve')).toHaveTextContent('lgtm');
    expect(screen.getByTestId('history-item-cc')).toHaveTextContent('[200,201]');
  });
});
```

- [ ] **Step 4: 运行 + commit**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
pnpm test:unit -- BpmHistorySection 2>&1 | tee /tmp/pw-task12-step4.log
# commit
cd ..
git add web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmHistorySection.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/BpmPanelBlock.tsx \
        web-admin/app/plugins/core-bpm/blocks/bpm-panel/__tests__/BpmHistorySection.test.tsx \
        platform/src/main/java/com/auraboot/framework/bpm/audit/ \
        platform/src/main/java/com/auraboot/framework/bpm/controller/
git commit -m "feat(bpm): history section renders audit trail including cc events"
```

---

## Task 13: action.bpm PropertySchema + 配置面板

**Files:**
- Create: `web-admin/app/shared/designer/schemas/action-bpm-schema.ts`
- Modify: `web-admin/app/shared/designer/ActionConfigPanel.tsx`（或等价 action 配置面板入口）

- [ ] **Step 1: 定位 action 配置面板**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
grep -rn "ActionConfigPanel\|action.*PropertySchema" app/ 2>/dev/null | head -5
```

- [ ] **Step 2: 创建 schema**

```typescript
import type { PropertySchema } from '~/shared/designer/types';

export const actionBpmSchema: PropertySchema<string>[] = [
  { key: 'executionMode', label: 'Execution Mode', type: 'select',
    defaultValue: 'command', group: 'Execution',
    options: [
      { label: 'Command', value: 'command' },
      { label: 'Flow', value: 'flow' },
      { label: 'Navigate', value: 'navigate' },
      { label: 'BPM Process', value: 'bpm' },
    ]
  },

  { key: 'bpm.processKey', label: 'Process Key', type: 'select',
    required: true, group: 'BPM',
    dependsOn: { field: 'executionMode', value: 'bpm' } },

  { key: 'bpm.businessKeyField', label: 'Business Key Field', type: 'field-selector',
    required: true, group: 'BPM',
    dependsOn: { field: 'executionMode', value: 'bpm' } },

  { key: 'bpm.variables', label: 'Variables (JSONPath)', type: 'key-value-editor',
    group: 'BPM',
    dependsOn: { field: 'executionMode', value: 'bpm' } },

  { key: 'bpm.formBindingRef', label: 'Form Binding Ref', type: 'text',
    group: 'BPM',
    dependsOn: { field: 'executionMode', value: 'bpm' } },

  { key: 'bpm.onSuccess.toast', label: 'Success Toast', type: 'text',
    group: 'BPM · On Success',
    dependsOn: { field: 'executionMode', value: 'bpm' } },

  { key: 'bpm.onSuccess.refresh', label: 'Refresh Record', type: 'boolean',
    defaultValue: true, group: 'BPM · On Success',
    dependsOn: { field: 'executionMode', value: 'bpm' } },
];
```

**Note:** If `'key-value-editor'` widget type doesn't exist in `PropertyType`, extend the enum and add a rendering branch to `PropertyFieldRenderer.tsx`:

```tsx
case 'key-value-editor':
  return <KeyValueEditor adapter={adapter} name={schema.key} label={label} />;
```

Create `web-admin/app/shared/designer/widgets/KeyValueEditor.tsx`:

```tsx
import React from 'react';
import type { FieldAdapter } from '~/shared/designer/types';

interface Props {
  adapter: FieldAdapter<Record<string, string>>;
  name: string;
  label: string;
}

export function KeyValueEditor({ adapter, name, label }: Props) {
  const value = (adapter.getValue(name) as Record<string, string>) ?? {};
  const entries = Object.entries(value);

  const setAt = (idx: number, key: string, v: string) => {
    const next: Record<string, string> = {};
    entries.forEach(([k, vv], i) => { next[i === idx ? key : k] = i === idx ? v : vv; });
    adapter.setValue(name, next);
  };
  const addRow = () => adapter.setValue(name, { ...value, '': '' });
  const removeRow = (key: string) => {
    const next = { ...value };
    delete next[key];
    adapter.setValue(name, next);
  };

  return (
    <div className="kv-editor" data-testid={`kv-editor-${name}`}>
      <label>{label}</label>
      {entries.map(([k, v], idx) => (
        <div key={idx} className="kv-row">
          <input value={k} placeholder="key"
                 onChange={e => setAt(idx, e.target.value, v)} />
          <input value={v} placeholder="value (JSONPath, e.g. $.field)"
                 onChange={e => setAt(idx, k, e.target.value)} />
          <button type="button" onClick={() => removeRow(k)}>×</button>
        </div>
      ))}
      <button type="button" onClick={addRow}>+ Add</button>
    </div>
  );
}
```

- [ ] **Step 3: 合并进 ActionConfigPanel**

In the existing ActionConfigPanel, merge `actionBpmSchema` into whatever schema it already uses. Follow the existing merge pattern.

- [ ] **Step 4: 单元测试 dependsOn 生效**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ActionConfigPanel } from '<path-from-step-1>';

describe('ActionConfigPanel.bpm schema', () => {
  it('hides bpm.* fields when executionMode != bpm', () => {
    render(<ActionConfigPanel value={{ executionMode: 'command' }} onChange={() => {}} />);
    expect(screen.queryByLabelText('Process Key')).toBeNull();
  });

  it('shows bpm.* fields when executionMode = bpm', () => {
    render(<ActionConfigPanel value={{ executionMode: 'bpm' }} onChange={() => {}} />);
    expect(screen.getByLabelText('Process Key')).toBeInTheDocument();
    expect(screen.getByLabelText('Business Key Field')).toBeInTheDocument();
  });
});
```

- [ ] **Step 5: 运行 + commit**

```bash
cd /Users/ghj/work/auraboot/auraboot/web-admin
npx tsc --noEmit 2>&1 | tee /tmp/pw-task13-step5a.log
pnpm test:unit -- ActionConfigPanel 2>&1 | tee /tmp/pw-task13-step5b.log
git add web-admin/app/shared/designer/
git commit -m "feat(designer): action config panel supports executionMode=bpm"
```

---

## Task 14: 文档同步 + smoke 验证 + 最终回归

**Files:**
- Modify: `docs/system-reference/` 相关 BPM 文档
- Create: `docs/system-reference/core/bpm-closure.md`（如不存在）

- [ ] **Step 1: 更新 BPM 子系统文档**

Locate and edit the main BPM subsystem doc (likely `docs/system-reference/subsystems/*bpm*.md`). Add sections:
- 审批语义：撤回（含 3 种 policy）、抄送（含 3 种 policy）、加签、转办（已有）
- Page DSL 集成：action.executionMode=bpm / bpm-panel block
- 权限模型：三层（action permission → 身份推导 → requiredPermissions IAM）
- 数据库：`ab_bpm_cc_record` 表字段说明，`ab_bpm_process_definition` 3 个新字段

Use full code examples from the spec (`docs/superpowers/specs/2026-04-16-oss-bpm-closure-spec1-design.md`), do not paraphrase.

- [ ] **Step 2: 更新 DSL 能力边界文档**

Edit `docs/system-reference/core/09-DSL能力边界完整参考.md`:
- Add `bpm-panel` under blockTypes section
- Add `executionMode: "bpm"` under ActionDef section
- Examples of both

- [ ] **Step 3: 更新数据库关键表 Schema 速查**

Edit `docs/system-reference/reference/01-数据库关键表Schema速查.md`:
- Append `ab_bpm_cc_record` entry
- Update `ab_bpm_process_definition` entry with 3 new columns

- [ ] **Step 4: 端到端冒烟**

```bash
cd /Users/ghj/work/auraboot/auraboot
./scripts/reset-and-init.sh 2>&1 | tee /tmp/pw-task14-step4a.log
cd web-admin
LOG=/tmp/pw-task14-final-$(date +%Y%m%d-%H%M%S).log
NO_PROXY=localhost npx playwright test tests/e2e/workflow-demo/ 2>&1 | tee "$LOG"
```

手动验证（最小 D-14 维度）：
- 登录 admin@example.com
- 浏览器打开 workflow-demo 列表页，确认"提交审批"按钮可见
- 点击提交审批，确认 toast 出现、详情页刷新、`bpm-panel` 渲染完整 4 section
- 在审批人账号下审批、抄送、撤回，确认操作反馈 toast、审计历史追加

验证失败不阻塞 commit（示例包 worktree 将补完整 E2E），但应记录失败原因到 commit body。

- [ ] **Step 5: 最终 compile + tsc 零新增错误**

```bash
cd /Users/ghj/work/auraboot/auraboot/platform
./gradlew compileJava 2>&1 | tee /tmp/pw-task14-step5a.log

cd /Users/ghj/work/auraboot/auraboot/web-admin
npx tsc --noEmit 2>&1 | tee /tmp/pw-task14-step5b.log
pnpm test:unit 2>&1 | tee /tmp/pw-task14-step5c.log
```

Expected: 0 new compile/tsc errors; unit tests all pass.

- [ ] **Step 6: Commit 文档 + 标记 Spec 1 完成**

```bash
git add docs/
git commit -m "docs(bpm): sync BPM closure docs for withdraw/cc/executionMode=bpm/bpm-panel"
```

---

## E2E TODO（合并示例包后）

此 plan **不含 E2E spec 文件**。待 workflow-demo worktree 合并后，在单独 plan 中补：

- `tests/e2e/bpm-closure/simple-approval.spec.ts` — D1-D14 基础闭环
- `tests/e2e/bpm-closure/withdraw-policies.spec.ts` — strict/loose/none 三种 policy
- `tests/e2e/bpm-closure/cc-policies.spec.ts` — initiator/assignee/all 三种 policy
- `tests/e2e/bpm-closure/sla-withdraw-interaction.spec.ts` — SLA 超时与撤回的交互
- `tests/e2e/bpm-closure/permission-overrides.spec.ts` — requiredPermissions IAM 覆盖

每个 spec 遵守金标准 `web-admin/tests/e2e/templates/thr-leave-request-lifecycle.spec.ts`。

---

## 风险与处置

| 风险 | 处置 |
|------|------|
| Task 0 根因假说错误（不是 tenant 问题） | 回退，diagnose → 写 Spec 0 拆分 |
| SmartEngine `startProcessInstance` 签名与假设不符 | 读 smart-engine 源码，修正 adapter；若缺 variables 支持，拆 Task 6a |
| 前端 UserPicker 复用组件不存在 | CcDialog 用 stub，commit body 标 TODO，不阻塞 |
| `requiredPermissions` 的 PermissionUtil.hasPermission 行为不明 | Task 11 先不集成 IAM 覆盖，写 TODO 注释，Spec 1.1 迭代补齐 |
| 现有 ActionDispatcher 结构差异大 | Task 6 Step 4 要求实现方先 read 再改，不强行替换 |
