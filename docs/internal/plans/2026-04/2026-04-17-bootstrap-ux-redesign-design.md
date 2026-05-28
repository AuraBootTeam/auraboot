# Bootstrap 未初始化态 UX 重设计

- 日期：2026-04-17（v1）/ 2026-04-17（v2 修订）
- 范围：OSS 仓库（`auraboot/`）
- 状态：v2 为现行版本；v1 已被 PoC 证伪

---

## 修订记录

| 版本 | 日期 | 决策 |
|------|------|------|
| v1 | 2026-04-17 上午 | 三维 evaluator（admin_user / default_tenant / system_config）+ 结构化 missingParts |
| **v2** | 2026-04-17 下午 | **退化为单一信号 `system_config.system.initialized`；删 evaluator 多维检查** |

### v1 为什么错

PoC 后发现两个根本问题：

1. **`platform_admin` 不是通用信号**。它是 SaaS-Kernel 多租户场景下"系统租户管理员"的专属角色，OSS 单租户部署里业务侧用 `tenant_admin`。用 `platform_admin` 检查会**与脚本恢复路径冲突**：`oss-reset-and-init.sh` 的 recovery 分支跳过 step 8.5（创建 platform_admin），导致 evaluator 误报"admin 缺失"。
2. **三维检查是在重复 `SystemConfigService.isInitialized()`**。`BootstrapEngineService` 在所有 step（含 admin/tenant/role 创建）完成后才写 `system.initialized=true`，**这一个标志位就是权威信号**。多查 admin/tenant 不增加确定性，反而引入耦合 + 假阳性。

### v2 怎么改

- evaluator 收敛到只调 `isInitialized()`
- DTO 仍保留 `missingParts` 字段（前端兼容），但只可能 `[]` 或 `["system_config"]`
- 删 `BootstrapStatusMapper` + 集成测试 + `BootstrapMissingPart.ADMIN_USER` / `DEFAULT_TENANT` 常量

---

## 背景

`auraboot/web-admin/app/root.tsx` 的 SSR loader（旧）会在 `bootstrap.initialized=false` 时**对任何路由静默 redirect 到 `/setup`**。问题：

1. **角色错位**：`/setup` 应只对运维/首位部署者可见；当前对所有访问者（开发刷新、E2E、最终用户）都触发跳转
2. **无错误信息**：用户看不到原因 —— DB 被清？连错库？bootstrap 部分失败？
3. **违反"禁止自愈"红线**：把"数据缺失"翻译为"跳转向导"，掩盖真实原因（`docs/standards/code-quality.md`）

## 目标（v2）

- 移除静默 redirect，改为**显式横幅提示 + 用户主动进入向导**
- 已初始化后 `/setup` 路由显示"已完成"页，不重复触发向导
- 不在后端做自愈（dev profile 自动 seed / 半提交补偿等）
- **`oss-reset-and-init.sh` 同步去掉 recovery 分支**（见后文）

## 非目标

- 不重写 `/setup` 向导本身
- 不引入访问控制 token（已初始化后 `/api/bootstrap/setup` 自带幂等拒绝足够防恶意）
- 不改 `BootstrapEngineService` / seeder 业务逻辑

---

## 关键概念：admin 用户的双重角色

`BootstrapEngineService.execute()` 完整路径下，admin 是**两个 tenant 的 member**：

| Tenant | 名称 | id | admin 角色 | scope | 职责 |
|--------|------|----|-----------|-------|-----|
| **System Tenant** | "System" | `1`（`SystemTenantContextExecutor.SYSTEM_TENANT_ID`） | `platform_admin` | global | 跨租户系统配置、租户管理、平台菜单 |
| **Business Tenant** | 用户 setup 时填的 companyName | bootstrap 新建 | `tenant_admin` | tenant | 租户内 user/role/data |

两个角色不冲突，对应不同维度。完整 bootstrap step 8.5 创建 `platform_admin` role + 分配；recovery 路径跳过这步 = **bug 状态**，不应被脚本掩盖。

后续如有跨平台/SaaS 模式需要细分检查，可重新引入 evaluator。OSS 单租户部署里两者一起判断没有增量价值。

---

## 设计

### 1. 后端

`BootstrapStatusResponse`（保留 v1 字段）：
```java
public class BootstrapStatusResponse {
    private boolean initialized;
    private boolean inProgress;
    private String mode;
    private List<String> missingParts;   // [] or ["system_config"]
    private String reason;               // null or "Bootstrap not completed"
}
```

`BootstrapController.getStatus()`：
```java
boolean initialized = systemConfigService.isInitialized();
List<String> missingParts = initialized
        ? List.of()
        : List.of(BootstrapMissingPart.SYSTEM_CONFIG);
String reason = initialized ? null : "Bootstrap not completed";
```

直接调 `SystemConfigService.isInitialized()`，不经 evaluator。

`BootstrapStartupLogger` 保留（启动时打印 WARN）。`BootstrapStatusEvaluator` / `BootstrapStatusMapper` / 集成测试 **删除**。

### 2. 前端

- `bootstrapStatus.ts` —— 不变（fetch + types）
- `bootstrapTexts.ts` —— 文案简化为单一态："System not initialized" / "Initialize now"，不再列具体缺失项
- `BootstrapBanner.tsx` —— 不显示 missingParts 详情，文案变为通用提示
- `BootstrapNotReady.tsx` / `SetupWizard.tsx` already-done —— 不变
- `root.tsx` —— 不变

### 3. 脚本（`oss-reset-and-init.sh`）

#### 现状（v1 时期，违反红线）

step 4.5 三个 case：
- A: 已 initialized → 跳过
- B: **admin 已存在但 flag 没设 → `mark_initialized_flag()` 直接写 system_config**（自愈）
- C: 什么都没 → 调 `/api/bootstrap/setup`

step 7.1 也是补偿：System Tenant 缺了就 INSERT id=1，绕过 BootstrapEngineService。

这两处都在掩盖 bootstrap 流程的真实失败。

#### v2

| 模式 | 命令 | 行为 |
|------|------|------|
| **默认（导数据）** | `./scripts/oss-reset-and-init.sh` | reset DB → 启后端 → POST `/api/bootstrap/setup` → 启前端 → 导插件 → seed showcase |
| **不导数据** | `./scripts/oss-reset-and-init.sh --no-bootstrap` | reset DB → 启后端（**未初始化态**）→ 启前端，停在这里 |

改动：
- **删** step 4.5 的 case B（`mark_initialized_flag` 函数 + 调用）
- **删** step 7.1 的补偿 SQL（System Tenant 由 BootstrapEngineService 在 step 8.5 创建）
- **加** `--no-bootstrap` 开关：跳过 step 4.5 / 6 / 7 / 7.1 / 7.5 / 8，只做 reset + 启服务
- 默认路径必须从空 DB 走完整 `/api/bootstrap/setup`，不允许"恢复"

不导数据时：
- 浏览器访问 `/` → banner 显示
- 用户点 banner CTA / 直接访问 `/setup` → 走真实向导
- 完成后系统进入正常态
- **整条链路成为端到端真测**，不再依赖 mock

---

## 影响面

| 文件 | 改动 |
|------|------|
| `platform/.../bootstrap/dto/BootstrapStatusResponse.java` | 字段不变 |
| `platform/.../bootstrap/controller/BootstrapController.java` | `getStatus()` 直接用 `systemConfigService` |
| `platform/.../bootstrap/BootstrapStatusEvaluator.java` | **删** |
| `platform/.../bootstrap/mapper/BootstrapStatusMapper.java` | **删** |
| `platform/.../bootstrap/constant/BootstrapMissingPart.java` | 只留 `SYSTEM_CONFIG` |
| `platform/.../bootstrap/BootstrapStartupLogger.java` | 适配（用 `isInitialized()`） |
| `src/test/.../BootstrapStatusEvaluatorTest.java` | **删** |
| `src/test/.../BootstrapStatusMapperIntegrationTest.java` | **删** |
| `src/test/.../BootstrapControllerIntegrationTest.java` | 简化为 2 case（initialized true/false） |
| `web-admin/app/services/bootstrapTexts.ts` | 简化 missing parts 标签为通用文案 |
| `web-admin/app/components/BootstrapBanner.tsx` | 不渲染具体 missingParts |
| `scripts/oss-reset-and-init.sh` | 删 recovery 分支 + 加 `--no-bootstrap` 开关 |

---

## 测试策略

### 后端

- `BootstrapControllerIntegrationTest` —— 2 case：initialized=true → empty / =false → `["system_config"]`
- 删除 evaluator 单测、mapper 集成测试

### 前端 E2E

- 已有 active tests 不变（已初始化态 + banner-not-visible-on-init）
- 用 `--no-bootstrap` 模式跑曾经 skip 的 4 个 banner 场景：现在可以**真测**
- 把 `tests/e2e/setup/setup-wizard.spec.ts` 里相关 `test.skip` 改回 `test`

---

## 与红线对齐

- ✅ 禁止自愈：脚本删 mark_initialized_flag、删 step 7.1 补偿；后端 evaluator 删多维检查
- ✅ 禁止魔术字符串：剩余 `system.initialized` 走 `SystemConfigKeys` 常量
- ✅ i18n 规范：bootstrapTexts 4 语言保留
- ✅ 测试即交付件：`--no-bootstrap` 让 banner 路径有真测覆盖

---

## 后续工作

实施按 task 分批：
1. 后端简化（evaluator/mapper/常量删除 + Controller 直连 + 测试调整）
2. 前端文案简化
3. 脚本改造（`--no-bootstrap` + 删 recovery）
4. E2E：unskip banner 场景，加 `--no-bootstrap` 模式跑测
5. 文档同步（本 doc 已先行）

每批独立 commit。
