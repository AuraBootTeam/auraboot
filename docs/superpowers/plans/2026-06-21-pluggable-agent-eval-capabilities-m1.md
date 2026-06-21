---
type: plan-impl
status: closed
created: 2026-06-21
distilled_to: docs/plugin-development/agent-capabilities-in-plugins.md
---

# Pluggable Per-Business AI Eval Capabilities — M1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the device agent eval cases out of the OSS core (`AgentArchetypeEvalCases`) into the pcba-manufacturing plugin's `agent-definitions.json`, backed by a new `ab_agent_eval_case` table read at runtime — so vertical AI evaluation knowledge is injected per-plugin, never compiled into aurabot core.

**Architecture:** eval cases ride along with the agent definition as a sub-resource (D1b). `importAgentDefinition` writes them to `ab_agent_eval_case`; `rollbackResource`/`restoreResource` follow the agent's lifecycle. `CapabilityEvalService.loadRegisteredCases(tenantId)` reads them from DB; `ScheduledCapabilityEvalJob` consumes that instead of the hardcoded `all()`. A pure `EvalCaseStructureValidator` (extracted from today's `AgentArchetypeEvalCasesTest` assertions) runs both in OSS deterministic tests (over a vertical-free `GenericEvalCaseFixture`) and as an import-time gate. A boundary linter forbids vertical command prefixes in OSS `framework/agent`.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus (BaseMapper + JSONB typehandlers), PostgreSQL JSONB, Flyway, JUnit 5, PF4J plugins, DeepSeek live eval (opt-in via `DEEPSEEK_API_KEY`).

## Global Constraints

- **Worktree:** All edits happen in `/Users/ghj/work/auraboot/auraboot-agent-eval-boundary` on branch `feat/pluggable-agent-eval-boundary`. Before editing any file run `pwd` to confirm you are inside that worktree; before any commit run `pwd` again. The pcba-manufacturing plugin lives in a separate repo `/Users/ghj/work/auraboot/plugins` — Task 9's JSON edit needs its own branch/worktree there (see Task 9).
- **JSONB columns:** entity JSONB fields MUST use `@TableField(typeHandler = JsonbListTypeHandler.class)` (lists) / `JsonbMapTypeHandler.class` (maps) with `@TableName(autoResultMap = true)`. Writes MUST go through `mapper.insert` / `mapper.updateById` (whole entity) — never `LambdaUpdateWrapper.set` on a JSONB column. Before committing any entity change run `bash /Users/ghj/work/auraboot/auraboot/scripts/check-jsonb-typehandler.sh` (if present in worktree) and fix any flag.
- **Flyway:** new migration goes in `platform/src/main/resources/db/migration/core/`, named `V<YYYYMMDD><6-digit>__<desc>.sql`. Use `V20260621000000__agent_eval_case.sql`. Before push, `git fetch origin && ls` the migration dir on `origin/main` to confirm no concurrent session took a colliding/newer version; bump the 6-digit suffix if needed. Never edit an already-applied `V*.sql`.
- **OSS boundary:** OSS code (`platform/`, `plugins/` under auraboot) MUST NOT `import ...enterprise...`. OSS `framework/agent/**` MUST NOT hardcode eval cases referencing vertical command prefixes (`crm:`, `qc:`, `iot_`, `pe:`, `mfg:`). Run `bash auraboot/scripts/check-oss-boundary.sh` before push.
- **No self-heal / catch(Exception):** data/config gaps throw; no `ensure/backfill/repair`. New `catch` needs a comment. Aux ops use `@Transactional(NOT_SUPPORTED)`.
- **Backend host-first test invocation (AUTHORITATIVE — overrides the `:platform:` prefix written in any task step below):** the gradle wrapper is at `platform/gradlew`, NOT worktree root; `platform` IS the gradle root. Always `cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary/platform` first, use module path `:test` / `:compileJava` / `:compileTestJava` (leading colon, NO `platform:` segment), and `unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL` so release deps resolve from shared `~/.m2`. Do NOT set `GRADLE_USER_HOME` to an empty dir. Judge pass/fail by `BUILD SUCCESSFUL` + `build/test-results/**/*.xml`, NOT by a piped tail exit code.
- **IT database:** integration-test profile targets the shared `aura_boot` on `localhost:5432`, username `ghj`, empty password (already configured in `platform/src/test/resources/application-integration-test.yml`). Shared DB = concurrent sessions may reset it; if an IT fails with `relation ... does not exist`, re-apply migrations and re-run before judging it a product bug. To apply this task's migration manually: `psql -h localhost -U ghj -d aura_boot -f platform/src/main/resources/db/migration/core/V20260621000000__agent_eval_case.sql`.
- **i18n:** any user-facing text is `$i18n:` / `LocalizedText`; no hardcoded zh-CN in code/DTO/JSON.

---

## File Structure

OSS repo (`/Users/ghj/work/auraboot/auraboot-agent-eval-boundary/platform/`):
- **Create** `src/main/resources/db/migration/core/V20260621000000__agent_eval_case.sql` — new table.
- **Create** `src/main/java/com/auraboot/framework/agent/entity/AgentEvalCase.java` — ORM entity.
- **Create** `src/main/java/com/auraboot/framework/agent/mapper/AgentEvalCaseMapper.java` — BaseMapper.
- **Create** `src/main/java/com/auraboot/framework/agent/eval/EvalCaseStructureValidator.java` — pure validator.
- **Create** `src/main/java/com/auraboot/framework/agent/eval/GenericEvalCaseFixture.java` — vertical-free fixture.
- **Modify** `src/main/java/com/auraboot/framework/plugin/dto/imports/AgentDefinitionDTO.java` — add `evalCases`.
- **Modify** `src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java` — write cases in `importAgentDefinition`; clean up in `rollbackResource`/`restoreResource`.
- **Modify** `src/main/java/com/auraboot/framework/agent/service/CapabilityEvalService.java` — add `loadRegisteredCases`.
- **Modify** `src/main/java/com/auraboot/framework/agent/eval/ScheduledCapabilityEvalJob.java` — consume `loadRegisteredCases` instead of `all()`.
- **Modify** `src/main/java/com/auraboot/framework/agent/eval/AgentArchetypeEvalCases.java` — delete `deviceAgent()` / `deviceOperationsAgent()`, drop them from `all()`.
- **Modify** `src/test/java/com/auraboot/framework/agent/eval/AgentArchetypeEvalCasesTest.java` — retarget at validator + fixture.
- **Modify** `src/test/java/com/auraboot/framework/agent/DeviceAgentLiveEvalIT.java` + `DeviceOperationsAgentLiveEvalIT.java` — read cases from DB.
- **Create** `src/test/java/com/auraboot/framework/agent/eval/EvalCaseStructureValidatorTest.java`, `.../agent/AgentEvalCaseImportIT.java`, `.../agent/MultiPluginEvalCaseCoexistenceIT.java` — tests.
- **Create** `scripts/check-agent-eval-boundary.mjs` (or extend `scripts/check-oss-boundary.sh`).

Plugin repo (`/Users/ghj/work/auraboot/plugins`, separate branch — Task 9):
- **Modify** `pcba-manufacturing/config/agent-definitions.json` — inline `evalCases` on both device agents.

---

## Task 1: `ab_agent_eval_case` table + migration

**Files:**
- Create: `platform/src/main/resources/db/migration/core/V20260621000000__agent_eval_case.sql`

**Interfaces:**
- Produces: table `ab_agent_eval_case` with columns `id, pid, tenant_id, agent_code, case_id, category, task_description, expected_tool_codes(jsonb), forbidden_tool_codes(jsonb), expected_input_keys(jsonb), expected_risk_level, expects_confirmation, plugin_source, deleted_flag, created_at, updated_at`; unique index `uq_agent_eval_case` on `(tenant_id, agent_code, case_id) WHERE deleted_flag = FALSE`.

- [ ] **Step 1: Write the migration** (modeled on `ab_agent_definition` DDL)

```sql
-- V20260621000000__agent_eval_case.sql
-- Per-business agent eval cases (injected via plugin agent-definitions.json),
-- read at runtime by CapabilityEvalService.loadRegisteredCases. Sub-resource of
-- the agent definition: lifecycle follows the agent (rollback/restore/overwrite).
CREATE TABLE IF NOT EXISTS ab_agent_eval_case (
  id                  BIGSERIAL PRIMARY KEY,
  pid                 VARCHAR(26) UNIQUE NOT NULL,
  tenant_id           BIGINT NOT NULL,
  agent_code          VARCHAR(100) NOT NULL,
  case_id             VARCHAR(150) NOT NULL,
  category            VARCHAR(100),
  task_description    TEXT NOT NULL,
  expected_tool_codes JSONB NOT NULL DEFAULT '[]',
  forbidden_tool_codes JSONB NOT NULL DEFAULT '[]',
  expected_input_keys JSONB NOT NULL DEFAULT '{}',
  expected_risk_level VARCHAR(20),
  expects_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
  plugin_source       VARCHAR(100),
  deleted_flag        BOOLEAN DEFAULT FALSE,
  created_at          TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_eval_case
  ON ab_agent_eval_case (tenant_id, agent_code, case_id) WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_agent_eval_case_tenant_agent
  ON ab_agent_eval_case (tenant_id, agent_code) WHERE deleted_flag = FALSE;
```

- [ ] **Step 2: Confirm no version collision**

Run: `git fetch origin && ls platform/src/main/resources/db/migration/core/ | tail -5`
Expected: no existing `V20260621*`. If a newer/colliding version exists, rename to the next free `V20260621NNNNNN`.

- [ ] **Step 3: Apply against a fresh isolated DB to verify it parses**

Apply via the project's reset path (host-first isolated runtime, zero docker — see `docs/agent-rules/flyway-schema-change-and-local-bringup.md`). Verify table exists:
Run (against the isolated runtime's DB): `psql "$PG_URL" -c '\d ab_agent_eval_case'`
Expected: table with the 16 columns and `uq_agent_eval_case` listed.

- [ ] **Step 4: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add platform/src/main/resources/db/migration/core/V20260621000000__agent_eval_case.sql
git commit -m "feat(agent-eval): add ab_agent_eval_case table"
```

---

## Task 2: `AgentEvalCase` entity + mapper

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/agent/entity/AgentEvalCase.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/mapper/AgentEvalCaseMapper.java`

**Interfaces:**
- Consumes: table from Task 1; `JsonbListTypeHandler`, `JsonbMapTypeHandler` (existing, used by `AgentDefinition`).
- Produces: `AgentEvalCase` entity (fields mirror table; `expectedToolCodes`/`forbiddenToolCodes` are `List<String>`, `expectedInputKeys` is `Map<String,Object>`); `AgentEvalCaseMapper extends BaseMapper<AgentEvalCase>`.

- [ ] **Step 1: Write the entity** (mirror `AgentDefinition`'s JSONB handling)

```java
package com.auraboot.framework.agent.entity;

import com.auraboot.framework.common.mybatis.JsonbListTypeHandler;
import com.auraboot.framework.common.mybatis.JsonbMapTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
@TableName(value = "ab_agent_eval_case", autoResultMap = true)
public class AgentEvalCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private String agentCode;
    private String caseId;
    private String category;
    private String taskDescription;

    @TableField(typeHandler = JsonbListTypeHandler.class)
    private List<String> expectedToolCodes;

    @TableField(typeHandler = JsonbListTypeHandler.class)
    private List<String> forbiddenToolCodes;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> expectedInputKeys;

    private String expectedRiskLevel;
    private Boolean expectsConfirmation;
    private String pluginSource;
    private Boolean deletedFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
```

> Verify the `JsonbListTypeHandler` / `JsonbMapTypeHandler` import path matches what `AgentDefinition.java` uses (`grep "import .*JsonbListTypeHandler" platform/src/main/java/com/auraboot/framework/agent/entity/AgentDefinition.java`) and copy it verbatim.

- [ ] **Step 2: Write the mapper**

```java
package com.auraboot.framework.agent.mapper;

import com.auraboot.framework.agent.entity.AgentEvalCase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentEvalCaseMapper extends BaseMapper<AgentEvalCase> {
}
```

- [ ] **Step 3: Compile**

Run: `cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL && ./gradlew :platform:compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: jsonb typehandler gate + commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
[ -f scripts/check-jsonb-typehandler.sh ] && bash scripts/check-jsonb-typehandler.sh || echo "gate script absent — skip"
git add platform/src/main/java/com/auraboot/framework/agent/entity/AgentEvalCase.java \
        platform/src/main/java/com/auraboot/framework/agent/mapper/AgentEvalCaseMapper.java
git commit -m "feat(agent-eval): AgentEvalCase entity + mapper"
```

---

## Task 3: `EvalCaseStructureValidator` (pure) + unit test

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/agent/eval/EvalCaseStructureValidator.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/eval/EvalCaseStructureValidatorTest.java`

**Interfaces:**
- Consumes: `CapabilityEvalCase` (existing DTO: `caseId, taskDescription, expectedToolCodes(List<String>), forbiddenToolCodes(List<String>), expectedInputKeys(Map), category, expectedRiskLevel, expectsConfirmation`).
- Produces: `static List<String> validate(List<CapabilityEvalCase> cases)` returning a list of human-readable violation strings (empty = valid). Encodes today's `AgentArchetypeEvalCasesTest` rules: caseId non-blank & unique, category non-blank, taskDescription length ≥ 8, expectedToolCodes non-empty with no null, forbiddenToolCodes no null, expected ∩ forbidden = ∅.

- [ ] **Step 1: Write the failing test**

```java
package com.auraboot.framework.agent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvalCaseStructureValidatorTest {

    private CapabilityEvalCase good() {
        return CapabilityEvalCase.builder()
            .caseId("demo-1").category("demo")
            .taskDescription("a valid task description")
            .expectedToolCodes(List.of("demo:echo"))
            .forbiddenToolCodes(List.of("demo:delete"))
            .build();
    }

    @Test
    void validCasesProduceNoViolations() {
        assertTrue(EvalCaseStructureValidator.validate(List.of(good())).isEmpty());
    }

    @Test
    void blankCaseIdIsAViolation() {
        CapabilityEvalCase c = good(); c.setCaseId("  ");
        assertFalse(EvalCaseStructureValidator.validate(List.of(c)).isEmpty());
    }

    @Test
    void duplicateCaseIdIsAViolation() {
        assertFalse(EvalCaseStructureValidator.validate(List.of(good(), good())).isEmpty());
    }

    @Test
    void expectedForbiddenOverlapIsAViolation() {
        CapabilityEvalCase c = good();
        c.setExpectedToolCodes(List.of("demo:echo"));
        c.setForbiddenToolCodes(List.of("demo:echo"));
        assertEquals(1, EvalCaseStructureValidator.validate(List.of(c)).size());
    }

    @Test
    void emptyExpectedToolsIsAViolation() {
        CapabilityEvalCase c = good(); c.setExpectedToolCodes(List.of());
        assertFalse(EvalCaseStructureValidator.validate(List.of(c)).isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL && ./gradlew :platform:test --tests '*EvalCaseStructureValidatorTest*'`
Expected: FAIL — `EvalCaseStructureValidator` does not exist.

- [ ] **Step 3: Write the validator**

```java
package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structural validation for eval cases — the mechanism that replaces the
 *  hardcoded AgentArchetypeEvalCasesTest assertions. Used by OSS deterministic
 *  tests (over GenericEvalCaseFixture) and as a plugin import-time gate. */
public final class EvalCaseStructureValidator {

    private EvalCaseStructureValidator() {}

    public static List<String> validate(List<CapabilityEvalCase> cases) {
        List<String> violations = new ArrayList<>();
        if (cases == null) {
            return violations;
        }
        Set<String> seen = new HashSet<>();
        for (CapabilityEvalCase c : cases) {
            String id = c.getCaseId();
            if (id == null || id.isBlank()) {
                violations.add("caseId must be non-blank");
                continue;
            }
            if (!seen.add(id)) {
                violations.add(id + ": duplicate caseId");
            }
            if (c.getCategory() == null || c.getCategory().isBlank()) {
                violations.add(id + ": category must be non-blank");
            }
            if (c.getTaskDescription() == null || c.getTaskDescription().trim().length() < 8) {
                violations.add(id + ": taskDescription must be >= 8 chars");
            }
            List<String> expected = c.getExpectedToolCodes();
            if (expected == null || expected.isEmpty()) {
                violations.add(id + ": expectedToolCodes must be non-empty");
            } else if (expected.contains(null)) {
                violations.add(id + ": expectedToolCodes contains null");
            }
            List<String> forbidden = c.getForbiddenToolCodes();
            if (forbidden != null && forbidden.contains(null)) {
                violations.add(id + ": forbiddenToolCodes contains null");
            }
            if (expected != null && forbidden != null && !Collections.disjoint(expected, forbidden)) {
                violations.add(id + ": expected and forbidden tool codes overlap");
            }
        }
        return violations;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :platform:test --tests '*EvalCaseStructureValidatorTest*'`
Expected: BUILD SUCCESSFUL, 5 tests pass (check `build/test-results`).

- [ ] **Step 5: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add platform/src/main/java/com/auraboot/framework/agent/eval/EvalCaseStructureValidator.java \
        platform/src/test/java/com/auraboot/framework/agent/eval/EvalCaseStructureValidatorTest.java
git commit -m "feat(agent-eval): EvalCaseStructureValidator (mechanism extracted from archetype test)"
```

---

## Task 4: `GenericEvalCaseFixture` + retarget `AgentArchetypeEvalCasesTest`

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/agent/eval/GenericEvalCaseFixture.java`
- Modify: `platform/src/test/java/com/auraboot/framework/agent/eval/AgentArchetypeEvalCasesTest.java`

**Interfaces:**
- Consumes: `EvalCaseStructureValidator.validate` (Task 3); `CapabilityEvalCase`.
- Produces: `GenericEvalCaseFixture.cases()` returning a vertical-free list (references only `demo:*` tools, NO `crm:`/`qc:`/`iot_`/`pe:`/`mfg:`).

- [ ] **Step 1: Write the fixture**

```java
package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import java.util.List;
import java.util.Map;

/** Vertical-free eval cases used only to self-test the eval mechanism in
 *  deterministic OSS CI. Contains NO business command codes — see
 *  scripts/check-agent-eval-boundary.mjs. */
public final class GenericEvalCaseFixture {

    private GenericEvalCaseFixture() {}

    public static List<CapabilityEvalCase> cases() {
        return List.of(
            CapabilityEvalCase.builder()
                .caseId("generic-read-not-write")
                .category("generic")
                .taskDescription("look up the current value of an item")
                .expectedToolCodes(List.of("demo:query"))
                .expectedInputKeys(Map.of())
                .forbiddenToolCodes(List.of("demo:delete"))
                .expectsConfirmation(false)
                .build(),
            CapabilityEvalCase.builder()
                .caseId("generic-action-with-confirm")
                .category("generic")
                .taskDescription("perform a guarded write action after confirmation")
                .expectedToolCodes(List.of("demo:write"))
                .expectedInputKeys(Map.of())
                .forbiddenToolCodes(List.of("demo:delete"))
                .expectsConfirmation(true)
                .build()
        );
    }
}
```

- [ ] **Step 2: Retarget the test** (replace the body of `AgentArchetypeEvalCasesTest.java` — it must NOT reference `AgentArchetypeEvalCases` device/vertical case factories; it now tests the *mechanism*)

```java
package com.auraboot.framework.agent.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The eval mechanism is validated over a vertical-free fixture. Vertical
 *  archetype cases now live in their plugins (ab_agent_eval_case) and are
 *  checked at import time by the same EvalCaseStructureValidator. */
class AgentArchetypeEvalCasesTest {

    @Test
    void genericFixtureIsStructurallyWellFormed() {
        List<String> violations = EvalCaseStructureValidator.validate(GenericEvalCaseFixture.cases());
        assertTrue(violations.isEmpty(), () -> "fixture violations: " + violations);
    }
}
```

> Note: `AgentArchetypeEvalCases.java` still has `csAgent()`/`pcbaQualityAgent()`/`competitiveAgent()` at this point (removed in M2). This test no longer asserts over them; that's intentional — their structural check moves to import-time in M2 when they migrate.

- [ ] **Step 3: Run**

Run: `./gradlew :platform:test --tests '*AgentArchetypeEvalCasesTest*'`
Expected: BUILD SUCCESSFUL, 1 test passes.

- [ ] **Step 4: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add platform/src/main/java/com/auraboot/framework/agent/eval/GenericEvalCaseFixture.java \
        platform/src/test/java/com/auraboot/framework/agent/eval/AgentArchetypeEvalCasesTest.java
git commit -m "test(agent-eval): retarget archetype test at mechanism + generic fixture"
```

---

## Task 5: `AgentDefinitionDTO.evalCases` + write cases in `importAgentDefinition` + import-time gate

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/plugin/dto/imports/AgentDefinitionDTO.java`
- Modify: `platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/AgentEvalCaseImportIT.java`

**Interfaces:**
- Consumes: `AgentEvalCaseMapper` (Task 2), `EvalCaseStructureValidator` (Task 3), `CapabilityEvalCase` (DTO).
- Produces: `AgentDefinitionDTO.getEvalCases()` (`List<CapabilityEvalCase>`); `importAgentDefinition` persists cases to `ab_agent_eval_case` (DELETE existing rows for `(tenant_id, agent_code)` then INSERT) after the agent row is written, on both CREATE and UPDATE paths; throws `PluginException` if `EvalCaseStructureValidator` reports violations.

- [ ] **Step 1: Add the DTO field**

In `AgentDefinitionDTO.java`, add alongside the existing fields:

```java
    private java.util.List<com.auraboot.framework.agent.dto.CapabilityEvalCase> evalCases;
```

(Or add a proper import at top: `import com.auraboot.framework.agent.dto.CapabilityEvalCase;` then `private List<CapabilityEvalCase> evalCases;`.)

- [ ] **Step 2: Write the failing IT** (real isolated stack — verifies import lands cases + idempotent re-import)

```java
package com.auraboot.framework.agent;
// imports: Spring Boot test base used by other agent ITs (copy from DeviceAgentSeedImportIT),
// AgentDefinitionDTO, CapabilityEvalCase, AgentEvalCaseMapper, ImportRequest.ConflictStrategy,
// LambdaQueryWrapper, AgentEvalCase, assertions.

class AgentEvalCaseImportIT /* extends <same base as DeviceAgentSeedImportIT> */ {

    // @Autowired PluginResourceImporter importer; @Autowired AgentEvalCaseMapper evalCaseMapper;
    // long tenantId = getTestTenant().getId();

    // @Test
    void importAgentWritesEvalCasesAndReimportReplaces() {
        // build AgentDefinitionDTO with agentCode "eval_it_agent", one evalCase
        //   caseId "eval-it-1", expected ["dsl.query"], forbidden ["x:write"], category "test"
        // importer.importAgentDefinition(dto, "plugin-pid", "imp-1", tenantId, OVERWRITE_SAFE);
        // assert 1 row in ab_agent_eval_case for (tenantId, "eval_it_agent"), deleted_flag=false
        // re-import dto with the SAME caseId but different taskDescription
        // assert still exactly 1 active row, with the new taskDescription (DELETE+INSERT, no dup)
    }
}
```

> Use `DeviceAgentSeedImportIT` as the structural template (same base class, tenant accessor, teardown). **Copy its teardown too** — eval-case rows must be cleaned per-tenant to avoid `uq_agent_eval_case` duplicate-key flakiness across IT runs.

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :platform:test --tests '*AgentEvalCaseImportIT*'`
Expected: FAIL — cases not persisted yet.

- [ ] **Step 4: Persist cases in `importAgentDefinition`**

In `PluginResourceImporterImpl.importAgentDefinition`, after the agent row is written on BOTH the `exists` (updateById) and new (`insert`) branches — i.e. just before each `return createResourceRecord(...)` for CREATE and UPDATE — call a new private helper. Add the helper:

```java
private void replaceEvalCases(Long tenantId, AgentDefinitionDTO dto, String pluginPid) {
    java.util.List<com.auraboot.framework.agent.dto.CapabilityEvalCase> cases =
            dto.getEvalCases() == null ? java.util.List.of() : dto.getEvalCases();
    java.util.List<String> violations = EvalCaseStructureValidator.validate(cases);
    if (!violations.isEmpty()) {
        throw new PluginException("Invalid eval cases for agent " + dto.getAgentCode() + ": " + violations);
    }
    // clean slate for this agent (physical replace — cases are an overwrite-on-import sub-resource)
    jdbcTemplate.update(
        "DELETE FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ?",
        tenantId, dto.getAgentCode());
    Instant now = Instant.now();
    for (com.auraboot.framework.agent.dto.CapabilityEvalCase c : cases) {
        AgentEvalCase row = new AgentEvalCase();
        row.setPid(UlidGenerator.generate());
        row.setTenantId(tenantId);
        row.setAgentCode(dto.getAgentCode());
        row.setCaseId(c.getCaseId());
        row.setCategory(c.getCategory());
        row.setTaskDescription(c.getTaskDescription());
        row.setExpectedToolCodes(c.getExpectedToolCodes());
        row.setForbiddenToolCodes(c.getForbiddenToolCodes());
        row.setExpectedInputKeys(c.getExpectedInputKeys());
        row.setExpectedRiskLevel(c.getExpectedRiskLevel());
        row.setExpectsConfirmation(c.isExpectsConfirmation());
        row.setPluginSource(pluginPid);
        row.setDeletedFlag(false);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        agentEvalCaseMapper.insert(row);
    }
}
```

Inject the mapper (add `private final AgentEvalCaseMapper agentEvalCaseMapper;` to the constructor / `@RequiredArgsConstructor` field set — match the class's existing DI style). Call `replaceEvalCases(tenantId, dto, pluginPid);` right before the CREATE and UPDATE `return createResourceRecord(...)` lines. Do NOT call it on the SKIP/ERROR branches (no agent write happened).

> The surrounding import runs in the caller's transaction (the method has no `@Transactional`), so the DELETE+INSERT joins the agent write atomically — correct.

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :platform:test --tests '*AgentEvalCaseImportIT*'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add platform/src/main/java/com/auraboot/framework/plugin/dto/imports/AgentDefinitionDTO.java \
        platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java \
        platform/src/test/java/com/auraboot/framework/agent/AgentEvalCaseImportIT.java
git commit -m "feat(agent-eval): import eval cases with agent definition + structure gate"
```

---

## Task 6: Cleanup on rollback / restore

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java`
- Test: add a method to `AgentEvalCaseImportIT.java`

**Interfaces:**
- Consumes: existing `rollbackResource` / `restoreResource` `case AGENT_DEFINITION` branches (rollback ~line 2361).
- Produces: rolling back an agent soft-deletes its eval cases; restoring un-deletes them.

- [ ] **Step 1: Write the failing test method** (append to `AgentEvalCaseImportIT`)

```java
    // @Test
    void rollbackAndRestoreFollowsAgentLifecycle() {
        // import agent "eval_it_agent" with one eval case → PluginResource pr
        // importer.rollbackResource(pr);
        // assert 0 ACTIVE (deleted_flag=false) rows for (tenantId,"eval_it_agent")
        // importer.restoreResource(pr);
        // assert 1 ACTIVE row again
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :platform:test --tests '*AgentEvalCaseImportIT*rollback*'`
Expected: FAIL — cases still active after rollback.

- [ ] **Step 3: Extend the rollback/restore branches**

In `rollbackResource`, inside `case AGENT_DEFINITION ->`, after the existing agent UPDATE, add:

```java
            jdbcTemplate.update("""
                    UPDATE ab_agent_eval_case
                    SET deleted_flag = TRUE, updated_at = NOW()
                    WHERE tenant_id = ? AND agent_code = ?
                    """, resource.getTenantId(), resource.getResourceCode());
```

In `restoreResource`'s `case AGENT_DEFINITION ->` (find it the same way), after the agent restore, add the mirror with `deleted_flag = FALSE`. (`resource.getResourceCode()` holds the agentCode — it was passed as the code arg in `createResourceRecord`.)

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :platform:test --tests '*AgentEvalCaseImportIT*'`
Expected: BUILD SUCCESSFUL (both methods).

- [ ] **Step 5: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java \
        platform/src/test/java/com/auraboot/framework/agent/AgentEvalCaseImportIT.java
git commit -m "feat(agent-eval): eval cases follow agent rollback/restore lifecycle"
```

---

## Task 7: `CapabilityEvalService.loadRegisteredCases` + dependency-aware skip (D3a)

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/agent/service/CapabilityEvalService.java`
- Test: add to `AgentEvalCaseImportIT.java` (DB read) — the skip behavior is unit-tested where the catalog filter is applied.

**Interfaces:**
- Consumes: `AgentEvalCaseMapper`.
- Produces: `public List<CapabilityEvalCase> loadRegisteredCases(Long tenantId)` — reads active rows for the tenant, maps `AgentEvalCase` → `CapabilityEvalCase`.

- [ ] **Step 1: Write the failing test method** (append to `AgentEvalCaseImportIT`)

```java
    // @Autowired CapabilityEvalService capabilityEvalService;
    // @Test
    void loadRegisteredCasesReturnsImportedCases() {
        // import agent "eval_it_agent" with caseId "eval-it-1"
        // List<CapabilityEvalCase> loaded = capabilityEvalService.loadRegisteredCases(tenantId);
        // assert loaded stream anyMatch caseId == "eval-it-1" with expectedToolCodes == ["dsl.query"]
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :platform:test --tests '*AgentEvalCaseImportIT*loadRegistered*'`
Expected: FAIL — method missing.

- [ ] **Step 3: Implement `loadRegisteredCases`**

```java
public List<CapabilityEvalCase> loadRegisteredCases(Long tenantId) {
    List<AgentEvalCase> rows = agentEvalCaseMapper.selectList(
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentEvalCase>()
            .eq(AgentEvalCase::getTenantId, tenantId)
            .and(w -> w.eq(AgentEvalCase::getDeletedFlag, false).or().isNull(AgentEvalCase::getDeletedFlag)));
    List<CapabilityEvalCase> out = new ArrayList<>();
    for (AgentEvalCase r : rows) {
        out.add(CapabilityEvalCase.builder()
            .caseId(r.getCaseId())
            .category(r.getCategory())
            .taskDescription(r.getTaskDescription())
            .expectedToolCodes(r.getExpectedToolCodes())
            .forbiddenToolCodes(r.getForbiddenToolCodes())
            .expectedInputKeys(r.getExpectedInputKeys())
            .expectedRiskLevel(r.getExpectedRiskLevel())
            .expectsConfirmation(Boolean.TRUE.equals(r.getExpectsConfirmation()))
            .build());
    }
    return out;
}
```

Inject `AgentEvalCaseMapper` into `CapabilityEvalService` (match existing DI style).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :platform:test --tests '*AgentEvalCaseImportIT*'`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Dependency-aware skip (D3a) inside `evaluateToolSelection`**

The harness already only scores tools present in the catalog it is given. Document and assert that contract: in `evaluateToolSelection(tenantId, evalMode, cases)`, when a case's `expectedToolCodes` are entirely absent from the resolvable tool catalog for that tenant, mark the case result `status=unavailable` and exclude it from accuracy denominators (do NOT count it as failed). Locate where the per-case loop builds its result map and add the skip branch. Add a unit test asserting an unresolvable-expected-tool case yields `unavailable`, not a failure.

> If the existing harness already drops out-of-catalog tools silently (verify by reading the per-case loop), the minimal change is to emit an explicit `unavailable` marker rather than a 0-score — so multi-plugin gaps are observable, not silent (AGENTS: no silent caps).

- [ ] **Step 6: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add platform/src/main/java/com/auraboot/framework/agent/service/CapabilityEvalService.java \
        platform/src/test/java/com/auraboot/framework/agent/AgentEvalCaseImportIT.java
git commit -m "feat(agent-eval): loadRegisteredCases from DB + dependency-aware skip"
```

---

## Task 8: `ScheduledCapabilityEvalJob` consumes DB cases

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/agent/eval/ScheduledCapabilityEvalJob.java`

**Interfaces:**
- Consumes: `CapabilityEvalService.loadRegisteredCases` (Task 7).
- Produces: `runOnce` no longer references `AgentArchetypeEvalCases.all()`.

- [ ] **Step 1: Replace the archetype source** (line ~117)

Change:
```java
if (includeArchetypeCases) {
    cases.addAll(AgentArchetypeEvalCases.all());
}
```
to:
```java
if (includeArchetypeCases) {
    cases.addAll(evalService.loadRegisteredCases(tenantId));
}
```

> `RegressionGate` keeps its current run-level aggregate behavior in M1 — identical to today's `all()` aggregation, so no regression. **Per-agent gate isolation (spec D3 scoring isolation) is deferred to M2**: it needs an `agent_code` dimension on `ab_capability_eval_run` and only bites once multiple vertical agents' cases co-run. M1 has at most the two device agents, so aggregate scoring does not regress anything. (Flagged in handoff.)

- [ ] **Step 2: Compile + run the job's existing tests**

Run: `./gradlew :platform:test --tests '*ScheduledCapabilityEval*'`
Expected: BUILD SUCCESSFUL (or no such test — then `:platform:compileJava` succeeds and `*CapabilityEval*` suite is green).

- [ ] **Step 3: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add platform/src/main/java/com/auraboot/framework/agent/eval/ScheduledCapabilityEvalJob.java
git commit -m "feat(agent-eval): scheduled eval reads registered cases from DB"
```

---

## Task 9: Migrate device cases to plugin + delete from OSS + repoint live ITs

**Files:**
- Modify (plugin repo): `/Users/ghj/work/auraboot/plugins/pcba-manufacturing/config/agent-definitions.json`
- Modify (OSS): `platform/src/main/java/com/auraboot/framework/agent/eval/AgentArchetypeEvalCases.java`
- Modify (OSS test): `platform/src/test/java/com/auraboot/framework/agent/DeviceAgentLiveEvalIT.java`, `DeviceOperationsAgentLiveEvalIT.java`

**Interfaces:**
- Consumes: `CapabilityEvalService.loadRegisteredCases` (Task 7); the device cases' content currently in `AgentArchetypeEvalCases.deviceAgent()` / `deviceOperationsAgent()`.
- Produces: device eval cases live in the plugin JSON; OSS no longer has `deviceAgent()`/`deviceOperationsAgent()`; live ITs read from DB.

> **Plugin edit needs its own worktree** in the `plugins` repo (it is a separate git repo). Before editing: `cd /Users/ghj/work/auraboot/plugins && git fetch origin && git worktree add -b feat/pcba-device-eval-cases /Users/ghj/work/auraboot/plugins-device-eval origin/main`. Edit the JSON there; commit on that branch.

- [ ] **Step 1: Read the exact device cases to migrate**

Run: `sed -n '/deviceAgent()/,/^    }/p;/deviceOperationsAgent()/,/^    }/p' platform/src/main/java/com/auraboot/framework/agent/eval/AgentArchetypeEvalCases.java`
Record each case's `caseId / category / taskDescription / expectedToolCodes / forbiddenToolCodes / expectedInputKeys / expectsConfirmation` verbatim — these become the JSON.

- [ ] **Step 2: Add `evalCases` to both agents in the plugin JSON**

In `/Users/ghj/work/auraboot/plugins-device-eval/pcba-manufacturing/config/agent-definitions.json`, add an `evalCases` array to `device_diagnostics_agent` and `device_operations_agent`, transcribing each case from Step 1. Example shape (use the real cases):

```jsonc
"evalCases": [
  {
    "caseId": "device-diag-readonly-no-write",
    "category": "device_agent",
    "taskDescription": "<verbatim from deviceAgent()>",
    "expectedToolCodes": ["dsl.query"],
    "forbiddenToolCodes": ["iot_device:invoke_service", "iot_alarm_event:ack", "iot_alarm_event:clear"],
    "expectedInputKeys": {},
    "expectsConfirmation": false
  }
]
```

Run: `node -e "JSON.parse(require('fs').readFileSync('/Users/ghj/work/auraboot/plugins-device-eval/pcba-manufacturing/config/agent-definitions.json','utf8'))" && echo OK`
Expected: OK (valid JSON).

- [ ] **Step 3: Delete the device factories from OSS**

In `AgentArchetypeEvalCases.java`: delete the `deviceAgent()` and `deviceOperationsAgent()` methods and remove `cases.addAll(deviceAgent()); cases.addAll(deviceOperationsAgent());` from `all()`. (Leave `csAgent`/`pcbaQualityAgent`/`competitiveAgent` — M2.)

Run: `grep -n "deviceAgent\|deviceOperationsAgent" platform/src/main/java/com/auraboot/framework/agent/eval/AgentArchetypeEvalCases.java`
Expected: no matches.

- [ ] **Step 4: Repoint the live ITs to DB**

In `DeviceAgentLiveEvalIT.java`, replace `for (CapabilityEvalCase c : AgentArchetypeEvalCases.deviceAgent())` with iteration over `capabilityEvalService.loadRegisteredCases(tenantId)` filtered to `category().equals("device_agent")` (or the device agentCode). Add a `@BeforeEach` step that imports the pcba device agent definition (via the same import path used by `DeviceAgentSeedImportIT`) so the cases are in DB for this tenant. Mirror in `DeviceOperationsAgentLiveEvalIT.java` for the operations agent. Keep the `Assumptions.assumeTrue(DEEPSEEK_API_KEY)` gate and the existing assertions.

- [ ] **Step 5: Verify OSS compiles + boundary clean**

Run: `unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL && ./gradlew :platform:compileTestJava -q && bash scripts/check-oss-boundary.sh`
Expected: BUILD SUCCESSFUL; boundary check passes.

- [ ] **Step 6: Run device live ITs (with key) — M1 acceptance**

Run: `DEEPSEEK_API_KEY=$DEEPSEEK_API_KEY ./gradlew :platform:test --tests '*DeviceAgentLiveEvalIT*' --tests '*DeviceOperationsAgentLiveEvalIT*'`
Expected: BUILD SUCCESSFUL; both pass reading cases from DB (5/5 stable). Capture wire evidence (reactor-netty DEBUG: DeepSeek POST). If `DEEPSEEK_API_KEY` is unset they skip — record the block point and run a non-live mechanism IT instead.

- [ ] **Step 7: Commit (two repos)**

```bash
# plugin repo
cd /Users/ghj/work/auraboot/plugins-device-eval && pwd
git add pcba-manufacturing/config/agent-definitions.json
git commit -m "feat(pcba): inline device agent eval cases (moved out of OSS core)"
# OSS worktree
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add platform/src/main/java/com/auraboot/framework/agent/eval/AgentArchetypeEvalCases.java \
        platform/src/test/java/com/auraboot/framework/agent/DeviceAgentLiveEvalIT.java \
        platform/src/test/java/com/auraboot/framework/agent/DeviceOperationsAgentLiveEvalIT.java
git commit -m "refactor(agent-eval): remove device archetype cases from OSS core, read from DB"
```

---

## Task 10: Boundary linter `check-agent-eval-boundary`

**Files:**
- Create: `auraboot/scripts/check-agent-eval-boundary.mjs` (worktree path: `scripts/check-agent-eval-boundary.mjs`)

**Interfaces:**
- Produces: a script that fails if OSS `platform/src/main/java/com/auraboot/framework/agent/**` contains an eval-case literal referencing a vertical command prefix (`crm:`, `qc:`, `iot_`, `pe:`, `mfg:`).

- [ ] **Step 1: Write the linter**

```javascript
#!/usr/bin/env node
// Fails if OSS framework/agent hardcodes eval cases referencing vertical command prefixes.
import { execSync } from 'node:child_process';
const ROOT = 'platform/src/main/java/com/auraboot/framework/agent';
const PREFIXES = ['crm:', 'qc:', 'iot_', 'pe:', 'mfg:'];
let hits = [];
for (const p of PREFIXES) {
  try {
    const out = execSync(`grep -rn "${p}" ${ROOT} || true`, { encoding: 'utf8' });
    out.split('\n').filter(Boolean)
       .filter(l => /expectedToolCodes|forbiddenToolCodes|CapabilityEvalCase|EvalCase/.test(l))
       .forEach(l => hits.push(l));
  } catch {}
}
if (hits.length) {
  console.error('OSS agent eval boundary violation — vertical eval cases must live in plugins:\n' + hits.join('\n'));
  process.exit(1);
}
console.log('agent-eval boundary OK');
```

- [ ] **Step 2: Run it — must pass now that device cases are gone**

Run: `cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && node scripts/check-agent-eval-boundary.mjs`
Expected: `agent-eval boundary OK`, exit 0.

- [ ] **Step 3: Negative check (temporary)**

Temporarily add a fake `iot_device:invoke_service` in a `CapabilityEvalCase` comment in any `framework/agent` file, re-run the linter, confirm it exits 1, then revert.

- [ ] **Step 4: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add scripts/check-agent-eval-boundary.mjs
git commit -m "chore(agent-eval): boundary linter forbidding vertical eval cases in OSS"
```

---

## Task 11: Multi-plugin coexistence regression IT

**Files:**
- Test: `platform/src/test/java/com/auraboot/framework/agent/MultiPluginEvalCaseCoexistenceIT.java`

**Interfaces:**
- Consumes: `importAgentDefinition` (Task 5), `rollbackResource` (Task 6), `loadRegisteredCases` (Task 7).

- [ ] **Step 1: Write the IT**

```java
package com.auraboot.framework.agent;
// same base/teardown as AgentEvalCaseImportIT

class MultiPluginEvalCaseCoexistenceIT /* extends <same base> */ {

    // @Test
    void twoPluginsCoexistRollbackIsolatedDependencySkip() {
        // import agent "agent_a" (pluginPid "plugin-a") with caseId "a-1"
        // import agent "agent_b" (pluginPid "plugin-b") with caseId "b-1"
        // loadRegisteredCases(tenant) contains both a-1 and b-1
        // rollbackResource(prA)  → a-1 inactive, b-1 STILL active   (isolation)
        // (dependency skip) a case whose expectedToolCodes are not in catalog →
        //    evaluateToolSelection marks it unavailable, not failed
    }
}
```

- [ ] **Step 2: Run to verify it fails, implement nothing new (logic exists from Tasks 5-7), then pass**

Run: `./gradlew :platform:test --tests '*MultiPluginEvalCaseCoexistenceIT*'`
Expected: PASS once written (it exercises Tasks 5-7). If isolation fails, the bug is a too-broad DELETE/UPDATE scope in Task 5/6 — fix the WHERE to include the agent_code scope.

- [ ] **Step 3: Full agent-eval regression**

Run: `unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL && ./gradlew :platform:test --tests '*AgentEvalCase*' --tests '*EvalCaseStructureValidator*' --tests '*AgentArchetypeEvalCasesTest*' --tests '*MultiPluginEvalCase*'`
Expected: BUILD SUCCESSFUL across all.

- [ ] **Step 4: Commit**

```bash
cd /Users/ghj/work/auraboot/auraboot-agent-eval-boundary && pwd
git add platform/src/test/java/com/auraboot/framework/agent/MultiPluginEvalCaseCoexistenceIT.java
git commit -m "test(agent-eval): multi-plugin coexistence + rollback isolation + dependency skip"
```

---

## Final Verification (before PR)

- [ ] `git fetch origin` then confirm migration version still free; rebase if needed.
- [ ] `unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL && ./gradlew :platform:test --tests '*Eval*' --tests '*AgentEvalCase*'` → all green (check `build/test-results`).
- [ ] `bash scripts/check-oss-boundary.sh && node scripts/check-agent-eval-boundary.mjs` → both pass.
- [ ] With `DEEPSEEK_API_KEY`: device live ITs 5/5 from DB + wire evidence captured.
- [ ] Two branches ready: `feat/pluggable-agent-eval-boundary` (OSS) + `feat/pcba-device-eval-cases` (plugins). PR the OSS one first (plugin JSON depends on the import path but is data-only; coordinate merge order in the PR description).

## Acceptance (M1 done)

Device agent eval cases live in `pcba-manufacturing/config/agent-definitions.json` → `ab_agent_eval_case`; OSS `AgentArchetypeEvalCases` has no device factories; device live ITs pass reading from DB; multi-plugin coexistence IT green; both boundary linters green. Deferred to M2 (flagged): migrate cs/pcbaQuality/competitive, delete `AgentArchetypeEvalCases.java`, per-agent gate scoring isolation (D3b).
