# Bootstrap UX 重设计 实施 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除 SSR loader 中"未初始化即静默 redirect 到 `/setup`"的反模式，改为后端返回结构化缺失项 + 前端显示横幅 + 用户主动进入向导。

**Architecture:** 后端扩展 `BootstrapStatusResponse`（新增 `missingParts`/`reason`）+ 新建 `BootstrapStatusEvaluator` 检查 admin/租户/system_config 三项；前端 `root.tsx` loader 注入 `bootstrapStatus`，新增 `BootstrapBanner` 组件、业务路由"未就绪"空状态、`SetupWizard` 已初始化态。

**Tech Stack:** Spring Boot + MyBatis（后端）/ React Router 7 + TypeScript + Tailwind（前端）/ JUnit5 + AssertJ（后端测试）/ Playwright（前端 E2E）

**Spec:** `docs/plans/2026-04/2026-04-17-bootstrap-ux-redesign-design.md`

---

## 文件结构

### 后端（`auraboot/platform/src/main/java/com/auraboot/framework/saas/bootstrap/`）

| 文件 | 责任 |
|------|------|
| `dto/BootstrapStatusResponse.java` | 修改：新增 `missingParts: List<String>` / `reason: String` |
| `constant/BootstrapMissingPart.java` | 新建：缺失项常量（admin_user / default_tenant / system_config） |
| `BootstrapStatusEvaluator.java` | 新建：检查实际数据状态，返回 `missingParts` |
| `controller/BootstrapController.java` | 修改：`getStatus()` 装配新字段 |
| `BootstrapStartupLogger.java` | 新建：`ApplicationRunner`，启动时打印未初始化警告 |

### 后端测试

| 文件 | 责任 |
|------|------|
| `src/test/java/com/auraboot/framework/saas/bootstrap/BootstrapStatusEvaluatorTest.java` | 新建：单元测试 4 场景 |
| `src/test/java/com/auraboot/framework/saas/bootstrap/BootstrapControllerIntegrationTest.java` | 新建：API 集成测试 |

### 前端（`auraboot/web-admin/app/`）

| 文件 | 责任 |
|------|------|
| `services/bootstrapStatus.ts` | 新建：fetch + 类型定义 |
| `components/BootstrapBanner.tsx` | 新建：顶部横幅组件 |
| `components/BootstrapNotReady.tsx` | 新建：业务路由"未就绪"空状态卡 |
| `root.tsx` | 修改：移除 redirect，注入 `bootstrapStatus` 到 loader data，渲染横幅 |
| `routes/setup/SetupWizard.tsx` | 修改：已初始化态渲染"已完成"页面 |
| `i18n/locales/zh-CN/bootstrap.json` | 新建：i18n 文案 |
| `i18n/locales/en-US/bootstrap.json` | 新建：i18n 文案（en） |

### E2E

| 文件 | 责任 |
|------|------|
| `web-admin/tests/e2e/setup/setup-wizard.spec.ts` | 修改：扩展 6 个场景 |

---

## Task 1：后端常量类 + DTO 扩展

**Files:**
- Create: `auraboot/platform/src/main/java/com/auraboot/framework/saas/bootstrap/constant/BootstrapMissingPart.java`
- Modify: `auraboot/platform/src/main/java/com/auraboot/framework/saas/bootstrap/dto/BootstrapStatusResponse.java`

- [ ] **Step 1：新建常量类**

```java
// constant/BootstrapMissingPart.java
package com.auraboot.framework.saas.bootstrap.constant;

public final class BootstrapMissingPart {
    public static final String ADMIN_USER = "admin_user";
    public static final String DEFAULT_TENANT = "default_tenant";
    public static final String SYSTEM_CONFIG = "system_config";

    private BootstrapMissingPart() {}
}
```

- [ ] **Step 2：扩展 DTO**

```java
// dto/BootstrapStatusResponse.java
package com.auraboot.framework.saas.bootstrap.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class BootstrapStatusResponse {
    private boolean initialized;
    private boolean inProgress;
    private String mode;
    private List<String> missingParts;
    private String reason;
}
```

- [ ] **Step 3：编译验证**

Run: `cd auraboot/platform && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL（`getStatus()` 暂未填充新字段会留 null，编译通过）

- [ ] **Step 4：Commit**

```bash
cd auraboot
git add platform/src/main/java/com/auraboot/framework/saas/bootstrap/constant/BootstrapMissingPart.java \
        platform/src/main/java/com/auraboot/framework/saas/bootstrap/dto/BootstrapStatusResponse.java
git commit -m "feat(bootstrap): add missingParts/reason fields to status response"
```

---

## Task 2：BootstrapStatusEvaluator + 单元测试（TDD）

**Files:**
- Create: `auraboot/platform/src/main/java/com/auraboot/framework/saas/bootstrap/BootstrapStatusEvaluator.java`
- Test: `auraboot/platform/src/test/java/com/auraboot/framework/saas/bootstrap/BootstrapStatusEvaluatorTest.java`

> 设计：用 `@Mock` IamUserMapper / IamTenantMapper / SystemConfigService，纯单元测试（不需要 DB）。返回 `List<String> missingParts` + `String reason`，封装为内部 record `Result`。

- [ ] **Step 1：写失败测试**

```java
// BootstrapStatusEvaluatorTest.java
package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.iam.user.mapper.IamUserMapper;
import com.auraboot.framework.iam.tenant.mapper.IamTenantMapper;
import com.auraboot.framework.saas.bootstrap.constant.BootstrapMissingPart;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapStatusEvaluatorTest {

    @Mock IamUserMapper userMapper;
    @Mock IamTenantMapper tenantMapper;
    @Mock SystemConfigService systemConfigService;
    @InjectMocks BootstrapStatusEvaluator evaluator;

    @Test
    void empty_database_lists_all_missing_parts() {
        when(userMapper.countAdminUsers()).thenReturn(0L);
        when(tenantMapper.countDefaultTenants()).thenReturn(0L);
        when(systemConfigService.isInitialized()).thenReturn(false);

        var result = evaluator.evaluate();

        assertThat(result.missingParts()).containsExactlyInAnyOrder(
                BootstrapMissingPart.ADMIN_USER,
                BootstrapMissingPart.DEFAULT_TENANT,
                BootstrapMissingPart.SYSTEM_CONFIG);
        assertThat(result.reason()).contains("admin_user", "default_tenant", "system_config");
    }

    @Test
    void only_admin_missing_lists_only_admin() {
        when(userMapper.countAdminUsers()).thenReturn(0L);
        when(tenantMapper.countDefaultTenants()).thenReturn(1L);
        when(systemConfigService.isInitialized()).thenReturn(true);

        var result = evaluator.evaluate();

        assertThat(result.missingParts()).containsExactly(BootstrapMissingPart.ADMIN_USER);
        assertThat(result.reason()).contains("admin_user");
    }

    @Test
    void fully_initialized_returns_empty_list_and_null_reason() {
        when(userMapper.countAdminUsers()).thenReturn(1L);
        when(tenantMapper.countDefaultTenants()).thenReturn(1L);
        when(systemConfigService.isInitialized()).thenReturn(true);

        var result = evaluator.evaluate();

        assertThat(result.missingParts()).isEmpty();
        assertThat(result.reason()).isNull();
    }

    @Test
    void only_system_config_missing_returns_only_system_config() {
        when(userMapper.countAdminUsers()).thenReturn(1L);
        when(tenantMapper.countDefaultTenants()).thenReturn(1L);
        when(systemConfigService.isInitialized()).thenReturn(false);

        var result = evaluator.evaluate();

        assertThat(result.missingParts()).containsExactly(BootstrapMissingPart.SYSTEM_CONFIG);
    }
}
```

- [ ] **Step 2：运行测试，确认失败**

Run: `cd auraboot/platform && ./gradlew test --tests BootstrapStatusEvaluatorTest`
Expected: FAIL，类不存在编译错误

- [ ] **Step 3：实现 Evaluator**

> 注：`IamUserMapper.countAdminUsers()` / `IamTenantMapper.countDefaultTenants()` 若不存在，需在对应 Mapper 添加（`@Select` 原生 SQL，记得加软删除条件）。先在 Mapper 添加方法签名 + XML/注解 SQL。

```java
// BootstrapStatusEvaluator.java
package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.iam.user.mapper.IamUserMapper;
import com.auraboot.framework.iam.tenant.mapper.IamTenantMapper;
import com.auraboot.framework.saas.bootstrap.constant.BootstrapMissingPart;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BootstrapStatusEvaluator {

    private final IamUserMapper userMapper;
    private final IamTenantMapper tenantMapper;
    private final SystemConfigService systemConfigService;

    public Result evaluate() {
        List<String> missing = new ArrayList<>();
        if (userMapper.countAdminUsers() == 0L) {
            missing.add(BootstrapMissingPart.ADMIN_USER);
        }
        if (tenantMapper.countDefaultTenants() == 0L) {
            missing.add(BootstrapMissingPart.DEFAULT_TENANT);
        }
        if (!systemConfigService.isInitialized()) {
            missing.add(BootstrapMissingPart.SYSTEM_CONFIG);
        }
        String reason = missing.isEmpty() ? null
                : "Missing bootstrap data: " + String.join(", ", missing);
        return new Result(missing, reason);
    }

    public record Result(List<String> missingParts, String reason) {}
}
```

补 Mapper 方法（示例）：

```java
// IamUserMapper.java 新增
@Select("SELECT COUNT(*) FROM iam_user u "
      + "JOIN iam_user_role ur ON ur.user_id = u.id "
      + "JOIN iam_role r ON r.id = ur.role_id "
      + "WHERE r.code = 'TENANT_ADMIN' "
      + "AND (u.deleted_flag = FALSE OR u.deleted_flag IS NULL)")
long countAdminUsers();

// IamTenantMapper.java 新增
@Select("SELECT COUNT(*) FROM iam_tenant "
      + "WHERE is_default = TRUE "
      + "AND (deleted_flag = FALSE OR deleted_flag IS NULL)")
long countDefaultTenants();
```

> Controller 在写实现前查 admin role code 与 `is_default` 字段名是否准确：`psql \d iam_user_role` / `psql \d iam_tenant`。如不一致按实际字段调整。

- [ ] **Step 4：运行测试，确认通过**

Run: `cd auraboot/platform && ./gradlew test --tests BootstrapStatusEvaluatorTest`
Expected: 4 tests PASS

- [ ] **Step 5：Commit**

```bash
cd auraboot
git add platform/src/main/java/com/auraboot/framework/saas/bootstrap/BootstrapStatusEvaluator.java \
        platform/src/main/java/com/auraboot/framework/iam/user/mapper/IamUserMapper.java \
        platform/src/main/java/com/auraboot/framework/iam/tenant/mapper/IamTenantMapper.java \
        platform/src/test/java/com/auraboot/framework/saas/bootstrap/BootstrapStatusEvaluatorTest.java
git commit -m "feat(bootstrap): add BootstrapStatusEvaluator with missing-part detection"
```

---

## Task 3：Controller 装配 + 集成测试

**Files:**
- Modify: `auraboot/platform/src/main/java/com/auraboot/framework/saas/bootstrap/controller/BootstrapController.java`
- Test: `auraboot/platform/src/test/java/com/auraboot/framework/saas/bootstrap/BootstrapControllerIntegrationTest.java`

- [ ] **Step 1：写失败的集成测试**

```java
// BootstrapControllerIntegrationTest.java
package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BootstrapControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void status_returns_missing_parts_for_uninitialized_db() throws Exception {
        // BaseIntegrationTest 默认提供干净 DB，未初始化态
        mockMvc.perform(get("/api/bootstrap/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.initialized").value(false))
            .andExpect(jsonPath("$.data.missingParts").isArray())
            .andExpect(jsonPath("$.data.missingParts").isNotEmpty())
            .andExpect(jsonPath("$.data.reason").isString());
    }

    @Test
    void status_returns_empty_missing_parts_after_bootstrap() throws Exception {
        bootstrapTenant();  // BaseIntegrationTest 提供的 helper（如不存在则 inline 调用 BootstrapEngineService）

        mockMvc.perform(get("/api/bootstrap/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.initialized").value(true))
            .andExpect(jsonPath("$.data.missingParts").isEmpty())
            .andExpect(jsonPath("$.data.reason").doesNotExist());
    }
}
```

> 若 `BaseIntegrationTest` 未提供 `bootstrapTenant()` helper，改用 `@Autowired BootstrapEngineService` + 构造 `BootstrapRequest` 直接调用。

- [ ] **Step 2：运行测试确认失败**

Run: `cd auraboot/platform && ./gradlew test --tests BootstrapControllerIntegrationTest`
Expected: FAIL —— `missingParts` 字段为 null

- [ ] **Step 3：修改 Controller 装配新字段**

```java
// BootstrapController.java
@RestController
@RequestMapping("/api/bootstrap")
@RequiredArgsConstructor
public class BootstrapController {

    private final BootstrapEngineService bootstrapEngineService;
    private final BootstrapStatusEvaluator statusEvaluator;

    @GetMapping("/status")
    public ApiResponse<BootstrapStatusResponse> getStatus() {
        var result = statusEvaluator.evaluate();
        BootstrapProgressResponse progress = bootstrapEngineService.getProgress();
        boolean inProgress = "running".equals(progress.getStatus())
                          || "pending".equals(progress.getStatus());

        return ApiResponse.success(BootstrapStatusResponse.builder()
                .initialized(result.missingParts().isEmpty())
                .inProgress(inProgress)
                .missingParts(result.missingParts())
                .reason(result.reason())
                .build());
    }

    // setup() / progress() 不变
}
```

> `SystemConfigService` 字段去掉（已被 evaluator 接管）。

- [ ] **Step 4：运行集成测试确认通过**

Run: `cd auraboot/platform && ./gradlew test --tests BootstrapControllerIntegrationTest`
Expected: 2 tests PASS

- [ ] **Step 5：Commit**

```bash
cd auraboot
git add platform/src/main/java/com/auraboot/framework/saas/bootstrap/controller/BootstrapController.java \
        platform/src/test/java/com/auraboot/framework/saas/bootstrap/BootstrapControllerIntegrationTest.java
git commit -m "feat(bootstrap): expose missingParts/reason via /api/bootstrap/status"
```

---

## Task 4：启动日志警告

**Files:**
- Create: `auraboot/platform/src/main/java/com/auraboot/framework/saas/bootstrap/BootstrapStartupLogger.java`

- [ ] **Step 1：实现 ApplicationRunner**

```java
// BootstrapStartupLogger.java
package com.auraboot.framework.saas.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(Integer.MAX_VALUE)
@RequiredArgsConstructor
public class BootstrapStartupLogger implements ApplicationRunner {

    private final BootstrapStatusEvaluator evaluator;

    @Override
    public void run(ApplicationArguments args) {
        var result = evaluator.evaluate();
        if (result.missingParts().isEmpty()) {
            return;
        }
        log.warn("================================================");
        log.warn("  AuraBoot Bootstrap NOT INITIALIZED");
        log.warn("  Missing: {}", result.missingParts());
        log.warn("  Run: scripts/reset-and-init.sh");
        log.warn("  Or:  visit http://localhost:5173/setup");
        log.warn("================================================");
    }
}
```

- [ ] **Step 2：手动验证（启动后端观察日志）**

Run: `cd auraboot/scripts && ./reset-db.sh && cd ../platform && ./gradlew bootRun`
Expected: 启动日志出现 `Bootstrap NOT INITIALIZED` 警示块

随后跑 bootstrap：

```bash
NO_PROXY=localhost curl -sX POST http://localhost:6443/api/bootstrap/setup \
  -H 'Content-Type: application/json' \
  -d '{"tenantName":"Default","adminEmail":"admin@example.com","adminPassword":"Test2026x"}'
```

重启后端，确认警告不再出现。

- [ ] **Step 3：Commit**

```bash
cd auraboot
git add platform/src/main/java/com/auraboot/framework/saas/bootstrap/BootstrapStartupLogger.java
git commit -m "feat(bootstrap): warn at startup when bootstrap not initialized"
```

---

## Task 5：前端 service + i18n 资源

**Files:**
- Create: `auraboot/web-admin/app/services/bootstrapStatus.ts`
- Create: `auraboot/web-admin/app/i18n/locales/zh-CN/bootstrap.json`
- Create: `auraboot/web-admin/app/i18n/locales/en-US/bootstrap.json`

> 路径若实际不同（如 i18n 资源在 `public/locales/` 或 `app/i18n/`），按现有约定调整。本步骤前先 `ls auraboot/web-admin/app/i18n/` 或 `grep -r "bootstrap.banner" auraboot/web-admin/app/` 确认。

- [ ] **Step 1：定位现有 i18n 目录**

Run: `find auraboot/web-admin/app -type d -name "locales" -o -name "i18n" 2>/dev/null | head -5`

按结果决定 `bootstrap.json` 实际放置位置。

- [ ] **Step 2：新建 service 文件**

```ts
// services/bootstrapStatus.ts
export type BootstrapStatus = {
  initialized: boolean;
  inProgress: boolean;
  mode?: string;
  missingParts: string[];
  reason?: string;
};

const BFF_URL = process.env.BFF_INTERNAL_URL || 'http://127.0.0.1:6443';

export async function fetchBootstrapStatus(): Promise<BootstrapStatus | null> {
  try {
    const res = await fetch(`${BFF_URL}/api/bootstrap/status`);
    if (!res.ok) return null;
    const json = await res.json();
    if (json?.code !== '0' || !json?.data) return null;
    return {
      initialized: Boolean(json.data.initialized),
      inProgress: Boolean(json.data.inProgress),
      mode: json.data.mode,
      missingParts: Array.isArray(json.data.missingParts) ? json.data.missingParts : [],
      reason: json.data.reason,
    };
  } catch {
    return null;
  }
}
```

- [ ] **Step 3：新增 i18n 文案**

```json
// zh-CN/bootstrap.json
{
  "bootstrap.banner.title": "系统未完成初始化",
  "bootstrap.banner.detail": "缺少：{{parts}}",
  "bootstrap.banner.cta": "前往初始化",
  "bootstrap.missing.admin_user": "管理员账户",
  "bootstrap.missing.default_tenant": "默认租户",
  "bootstrap.missing.system_config": "系统配置标记",
  "bootstrap.notReady.title": "系统未就绪",
  "bootstrap.notReady.body": "请先完成系统初始化后再使用此功能。",
  "bootstrap.notReady.cta": "前往初始化",
  "bootstrap.alreadyDone.title": "系统已初始化",
  "bootstrap.alreadyDone.body": "无需重复操作。",
  "bootstrap.alreadyDone.cta": "返回首页"
}
```

```json
// en-US/bootstrap.json
{
  "bootstrap.banner.title": "System not initialized",
  "bootstrap.banner.detail": "Missing: {{parts}}",
  "bootstrap.banner.cta": "Initialize now",
  "bootstrap.missing.admin_user": "Admin account",
  "bootstrap.missing.default_tenant": "Default tenant",
  "bootstrap.missing.system_config": "System config flag",
  "bootstrap.notReady.title": "System not ready",
  "bootstrap.notReady.body": "Please complete system initialization first.",
  "bootstrap.notReady.cta": "Initialize now",
  "bootstrap.alreadyDone.title": "System already initialized",
  "bootstrap.alreadyDone.body": "No further action needed.",
  "bootstrap.alreadyDone.cta": "Back to home"
}
```

- [ ] **Step 4：Commit**

```bash
cd auraboot
git add web-admin/app/services/bootstrapStatus.ts \
        web-admin/app/i18n/locales/  # 按实际路径
git commit -m "feat(web): add bootstrap status service and i18n strings"
```

---

## Task 6：BootstrapBanner 组件

**Files:**
- Create: `auraboot/web-admin/app/components/BootstrapBanner.tsx`

- [ ] **Step 1：实现组件**

```tsx
// components/BootstrapBanner.tsx
import { Link } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import type { BootstrapStatus } from '~/services/bootstrapStatus';

interface Props {
  status: BootstrapStatus;
}

export function BootstrapBanner({ status }: Props) {
  const { t } = useI18n();
  if (status.initialized) return null;

  const partsText = status.missingParts
    .map((p) => t(`bootstrap.missing.${p}`, { defaultValue: p }))
    .join('、');

  return (
    <div
      role="alert"
      data-testid="bootstrap-banner"
      className="fixed top-0 left-0 right-0 z-[1000] bg-yellow-50 border-b border-yellow-300 px-4 py-2 flex items-center justify-between text-yellow-900 text-sm"
    >
      <div>
        <span className="font-medium mr-2">{t('bootstrap.banner.title')}</span>
        <span>{t('bootstrap.banner.detail', { parts: partsText })}</span>
      </div>
      <Link
        to="/setup"
        data-testid="bootstrap-banner-cta"
        className="ml-4 px-3 py-1 bg-yellow-600 text-white rounded hover:bg-yellow-700"
      >
        {t('bootstrap.banner.cta')}
      </Link>
    </div>
  );
}
```

- [ ] **Step 2：编译验证**

Run: `cd auraboot/web-admin && npx tsc --noEmit`
Expected: 无新增错误（旧错误如有可忽略，但本文件不能贡献新错误）

- [ ] **Step 3：Commit**

```bash
cd auraboot
git add web-admin/app/components/BootstrapBanner.tsx
git commit -m "feat(web): add BootstrapBanner component"
```

---

## Task 7：root.tsx 改造（移除 redirect + 注入状态 + 渲染横幅）

**Files:**
- Modify: `auraboot/web-admin/app/root.tsx:26-36` (RootLoaderData 类型)
- Modify: `auraboot/web-admin/app/root.tsx:81-104` (loader 逻辑)
- Modify: `auraboot/web-admin/app/root.tsx`（Layout 渲染处，加横幅 + body padding）

- [ ] **Step 1：扩展 RootLoaderData 类型**

```ts
// root.tsx 顶部
import type { BootstrapStatus } from '~/services/bootstrapStatus';
import { fetchBootstrapStatus } from '~/services/bootstrapStatus';
import { BootstrapBanner } from '~/components/BootstrapBanner';

export interface RootLoaderData {
  user: any;
  permissions: any;
  preferences: any;
  menus: any[];
  i18n: Record<string, string>;
  locale: string;
  initialTimezone?: string;
  edition: string;
  spaces: any[];
  bootstrapStatus: BootstrapStatus | null;
}
```

- [ ] **Step 2：替换 loader 内 bootstrap 检查段**

定位 `root.tsx:86-104`（"Bootstrap check: redirect..." 段），整段替换为：

```ts
  // Bootstrap check: inject status, never redirect
  const bootstrapStatus = await fetchBootstrapStatus();
```

并在 loader 最终 `return { ... }` 中加入 `bootstrapStatus`。

> 注意：`fetchBootstrapStatus()` 内已 try/catch + 返回 null，不会抛异常。

- [ ] **Step 3：在 Layout 渲染横幅**

定位 root layout 的 JSX（含 `<Outlet />` 的部分），在 `<body>` 直接子节点最顶层加：

```tsx
const data = useLoaderData<RootLoaderData>();
// ...
{data?.bootstrapStatus && !data.bootstrapStatus.initialized && (
  <BootstrapBanner status={data.bootstrapStatus} />
)}
<div className={data?.bootstrapStatus && !data.bootstrapStatus.initialized ? 'pt-10' : ''}>
  <Outlet />
</div>
```

> `pt-10` 给固定横幅留出空间，避免遮挡。

- [ ] **Step 4：编译 + dev 启动手动验证**

Run: `cd auraboot/web-admin && npx tsc --noEmit`
Expected: 无新增错误

启动验证：

```bash
cd auraboot/scripts && ./reset-db.sh
cd auraboot/platform && ./gradlew bootRun &  # 后台
cd auraboot/web-admin && pnpm dev:full &
# 浏览器访问 http://localhost:5173/
```

预期：根路径不再被 redirect，看到顶部黄色横幅 + "缺少：管理员账户、默认租户、系统配置标记"。

- [ ] **Step 5：Commit**

```bash
cd auraboot
git add web-admin/app/root.tsx
git commit -m "feat(web): replace bootstrap redirect with banner"
```

---

## Task 8：SetupWizard 已初始化态

**Files:**
- Modify: `auraboot/web-admin/app/routes/setup/SetupWizard.tsx`

- [ ] **Step 1：在 SetupWizard 顶部加 loader / useEffect 检查**

读现有 `SetupWizard.tsx`，确认是否有 React Router loader。如没有，新增：

```ts
// SetupWizard.tsx
import { redirect, useLoaderData, Link } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { fetchBootstrapStatus, type BootstrapStatus } from '~/services/bootstrapStatus';
import { useI18n } from '~/contexts/I18nContext';

export async function loader(_args: LoaderFunctionArgs): Promise<{ status: BootstrapStatus | null }> {
  const status = await fetchBootstrapStatus();
  return { status };
}
```

- [ ] **Step 2：组件开头分支渲染**

```tsx
export default function SetupWizard() {
  const { status } = useLoaderData<typeof loader>();
  const { t } = useI18n();

  if (status?.initialized) {
    return (
      <div data-testid="bootstrap-already-done" className="max-w-md mx-auto mt-20 p-6 bg-white border rounded shadow">
        <h1 className="text-xl font-semibold mb-2">{t('bootstrap.alreadyDone.title')}</h1>
        <p className="text-gray-600 mb-4">{t('bootstrap.alreadyDone.body')}</p>
        <Link to="/" className="px-4 py-2 bg-blue-600 text-white rounded">
          {t('bootstrap.alreadyDone.cta')}
        </Link>
      </div>
    );
  }

  // 原有向导渲染保持不动
  return <ExistingWizardJSX />;
}
```

- [ ] **Step 3：编译 + 手动验证**

Run: `cd auraboot/web-admin && npx tsc --noEmit`

完成 bootstrap 后访问 `http://localhost:5173/setup`：预期看到"系统已初始化"页面 + "返回首页"按钮，不再渲染表单。

- [ ] **Step 4：Commit**

```bash
cd auraboot
git add web-admin/app/routes/setup/SetupWizard.tsx
git commit -m "feat(web): show already-done page when /setup accessed after init"
```

---

## Task 9：业务路由"未就绪"空状态（ErrorBoundary 分支）

**Files:**
- Create: `auraboot/web-admin/app/components/BootstrapNotReady.tsx`
- Modify: `auraboot/web-admin/app/root.tsx`（ErrorBoundary 部分）

- [ ] **Step 1：实现空状态卡组件**

```tsx
// components/BootstrapNotReady.tsx
import { Link } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';

export function BootstrapNotReady() {
  const { t } = useI18n();
  return (
    <div data-testid="bootstrap-not-ready" className="flex flex-col items-center justify-center min-h-[60vh]">
      <div className="max-w-md text-center bg-white border rounded shadow p-8">
        <h1 className="text-2xl font-semibold mb-2">{t('bootstrap.notReady.title')}</h1>
        <p className="text-gray-600 mb-6">{t('bootstrap.notReady.body')}</p>
        <Link to="/setup" className="px-4 py-2 bg-yellow-600 text-white rounded">
          {t('bootstrap.notReady.cta')}
        </Link>
      </div>
    </div>
  );
}
```

- [ ] **Step 2：在 root ErrorBoundary 中分支判断**

定位 `root.tsx` 内 `ErrorBoundary` export，开头加：

```tsx
export function ErrorBoundary() {
  const data = useRouteLoaderData('root') as RootLoaderData | undefined;
  if (data?.bootstrapStatus && !data.bootstrapStatus.initialized) {
    return <BootstrapNotReady />;
  }
  // 原有错误处理保持不动
  // ...
}
```

import：`import { BootstrapNotReady } from '~/components/BootstrapNotReady';`

- [ ] **Step 3：编译 + 手动验证**

Run: `cd auraboot/web-admin && npx tsc --noEmit`

未初始化态点击侧边栏菜单进入业务路由，预期看到"系统未就绪"卡片。

- [ ] **Step 4：Commit**

```bash
cd auraboot
git add web-admin/app/components/BootstrapNotReady.tsx \
        web-admin/app/root.tsx
git commit -m "feat(web): show 'not ready' card when business routes hit before bootstrap"
```

---

## Task 10：E2E 扩展（金标准对照）

**Files:**
- Modify: `auraboot/web-admin/tests/e2e/setup/setup-wizard.spec.ts`

> 写测试前先读 `auraboot/web-admin/tests/e2e/templates/`（金标准模板，如不存在跳过），并熟悉 `auraboot/scripts/oss-test.sh` 用法。

- [ ] **Step 1：检查现有 spec**

Run: `cat auraboot/web-admin/tests/e2e/setup/setup-wizard.spec.ts`

理解既有测试结构，下一步在同一文件 append 新 describe block。

- [ ] **Step 2：扩展 6 个场景**

```ts
// 在 setup-wizard.spec.ts 文件末尾新增
import { test, expect } from '@playwright/test';

test.describe('Bootstrap UX redesign', () => {
  test.beforeEach(async () => {
    // 重置 DB（脚本调用，不在测试体内 inline）
    // 假设有 fixture 或 oss-test.sh --reset 选项；若无，预置脚本前置跑 ./scripts/reset-db.sh
  });

  test('uninitialized root path shows banner instead of redirect', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL('/');  // 不再被 redirect 到 /setup
    const banner = page.getByTestId('bootstrap-banner');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText('系统未完成初始化');
    await expect(banner).toContainText('管理员账户');
  });

  test('banner CTA navigates to /setup', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('bootstrap-banner-cta').click();
    await expect(page).toHaveURL('/setup');
  });

  test('business route shows not-ready card before bootstrap', async ({ page }) => {
    await page.goto('/p/iam_user');  // 任意业务路由
    await expect(page.getByTestId('bootstrap-not-ready')).toBeVisible();
  });

  test('after bootstrap, banner disappears', async ({ page }) => {
    await page.goto('/setup');
    await page.fill('[name="tenantName"]', 'Default');
    await page.fill('[name="adminEmail"]', 'admin@example.com');
    await page.fill('[name="adminPassword"]', 'Test2026x');
    await page.click('button[type="submit"]');
    // 等待向导成功跳转
    await page.goto('/');
    await expect(page.getByTestId('bootstrap-banner')).toBeHidden();
  });

  test('after bootstrap, /setup shows already-done page', async ({ page }) => {
    // 假设上一个测试已 bootstrap；如果用独立 worker，需在此处先 bootstrap via API
    await page.goto('/setup');
    await expect(page.getByTestId('bootstrap-already-done')).toBeVisible();
    await expect(page.locator('text=系统已初始化')).toBeVisible();
  });

  test('only admin missing produces specific banner detail', async ({ page, request }) => {
    // 设定 DB 仅缺 admin（通过 SQL 或 API 准备数据）
    // 此测试可放后续扩展，先标记 .skip 也可
    test.skip(true, 'requires custom DB seeding helper');
  });
});
```

> 字段 `name` 属性需对照实际 `SetupWizard.tsx` 表单调整。如表单用 `id` 或 `data-testid`，按实际改。

- [ ] **Step 3：跑测试**

Run:
```bash
cd auraboot
LOG=/tmp/pw-bootstrap-$(date +%Y%m%d-%H%M%S).log
echo "Log: $LOG"
./scripts/oss-test.sh --grep "Bootstrap UX redesign" 2>&1 | tee "$LOG"
```

Expected: 5 通过 / 1 skip。失败时读 `$LOG` 全文定位。

- [ ] **Step 4：Commit**

```bash
cd auraboot
git add web-admin/tests/e2e/setup/setup-wizard.spec.ts
git commit -m "test(e2e): cover bootstrap banner / not-ready / already-done UX"
```

---

## Task 11：旧测试与代码 grep 清理

**Files:** 视 grep 结果而定

- [ ] **Step 1：grep 全仓 redirect 假设**

Run:
```bash
cd auraboot
grep -rn "redirect.*setup\|toHaveURL.*setup" web-admin/tests/ web-admin/app/ 2>/dev/null
```

逐条检查：
- 业务测试若假设"未初始化必跳 /setup" → 改为断言 banner 存在
- 文档若描述"loader 会 redirect" → 更新为新行为

- [ ] **Step 2：grep 旧 bootstrap.status 调用**

```bash
cd auraboot
grep -rn "bootstrap/status" web-admin/app/ 2>/dev/null
```

确认所有调用方都迁移到 `fetchBootstrapStatus()` service，无散落 fetch。

- [ ] **Step 3：跑全量 OSS E2E（冒烟）**

Run:
```bash
cd auraboot
LOG=/tmp/pw-smoke-$(date +%Y%m%d-%H%M%S).log
echo "Log: $LOG"
./scripts/oss-test.sh 2>&1 | tee "$LOG"
```

Expected: 不引入新 failures（与 baseline 一致或更好）。

- [ ] **Step 4：Commit（如有清理）**

```bash
cd auraboot
git add -p  # 选择性添加
git commit -m "chore(bootstrap): clean up legacy redirect assumptions"
```

---

## Task 12：浏览器手动验收 + 文档同步

**Files:**
- Modify: `auraboot/docs/system-reference/`（如有 bootstrap 相关章节）

- [ ] **Step 1：浏览器交互全链路验证**

委托 subagent 用 chrome-devtools MCP 跑完整链路（避免主上下文污染，符合 MCP token 优化红线）：

子任务：
1. `./scripts/reset-db.sh` 后访问 `http://localhost:5173/` —— 截屏 + DOM snapshot 验证横幅可见、文案正确
2. 点击 banner CTA —— 验证跳到 `/setup`
3. 完成 setup 表单 —— 验证 DB 中 admin/tenant/config 三项就位
4. 返回 `/` —— 验证横幅消失
5. 再访问 `/setup` —— 验证 "已初始化" 页面

- [ ] **Step 2：更新文档**

检查并更新：
- `auraboot/docs/getting-started/`：首次部署流程是否提到 `/setup` 行为
- `auraboot/docs/architecture/`：bootstrap 流程图是否需要更新
- 不需要更新的话，跳过本步

- [ ] **Step 3：写 memory（如有非显然结论）**

如本次实施暴露非显然约束（如某 mapper 字段名 vs 文档不一致），按 memory 规范写入。

- [ ] **Step 4：最终 Commit**

```bash
cd auraboot
git add docs/
git commit -m "docs: update bootstrap flow after UX redesign"
```

---

## 验收标准（整 plan 完成 = 全部满足）

- [x] 后端 `BootstrapStatusEvaluator` 单元测试 4 PASS
- [x] 后端 `BootstrapControllerIntegrationTest` 集成测试 2 PASS
- [x] 启动日志在未初始化时显示警示块
- [x] 前端 `npx tsc --noEmit` 无新增错误
- [x] E2E `Bootstrap UX redesign` describe block 5 PASS / 1 acceptable skip
- [x] OSS 冒烟全量测试与 baseline 一致
- [x] 浏览器手动验证 5 链路全部通过
- [x] grep 无残留 `redirect.*setup` 假设
- [x] 所有 commit 不含 Co-Authored-By
