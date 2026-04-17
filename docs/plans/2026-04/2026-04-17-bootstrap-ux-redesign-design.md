# Bootstrap 未初始化态 UX 重设计

- 日期：2026-04-17
- 范围：OSS 仓库（`auraboot/`）
- 类型：design doc（实施 plan 另起）

## 背景

当前 `auraboot/web-admin/app/root.tsx:86-104` 的 SSR loader 会在 `bootstrap.initialized=false` 时**对任何路由静默 redirect 到 `/setup`**。这造成三个问题：

1. **角色错位**：`/setup` 是首次部署初始化向导，应只对运维/首位部署者可见；当前对所有访问者（开发刷新、E2E、最终用户）都触发跳转。
2. **无错误信息**：用户看不到原因 —— DB 被清？连错库？bootstrap 部分失败？只能靠经验猜。
3. **违反"禁止自愈"红线**：当前行为把"数据缺失"翻译为"跳转向导"，掩盖真实原因，与项目硬约束冲突（`docs/standards/code-quality.md`）。

## 目标

- 移除静默 redirect，改为**显式提示 + 用户主动进入**
- 后端返回**结构化原因**（缺什么、为什么），前端展示具体信息
- 已初始化后 `/setup` 路由本身**不可重复触发**
- 开发环境继续依赖脚本层 bootstrap，不在后端做 dev-profile 自愈

## 非目标

- 不重写 `/setup` 向导本身（页面 UI 与现有 `SetupWizard.tsx` 保持兼容）
- 不引入一次性 token / localhost 限制等访问控制（`已初始化后接口失效` 已足够防恶意）
- 不改变 bootstrap 后端执行逻辑（`BootstrapEngineService` / 各 seeder 不动）

## 当前实现

### 后端

- `BootstrapController.getStatus()` 返回 `{ initialized, inProgress, mode }`（`auraboot/platform/.../bootstrap/controller/BootstrapController.java`）
- `BootstrapStatusResponse` 仅含布尔值，无明细
- `BootstrapController.setup()` 已做幂等检查（`isInitialized()` 时返回错误）

### 前端

- `root.tsx:86-104`：loader 内 fetch `/api/bootstrap/status`，未初始化即 `redirect('/setup')`
- `sessionMiddlewareFactory.ts:13`：`/setup` 在 `PUBLIC_ROUTES` 白名单中
- `routes.ts:15`：`/setup` 注册到 `SetupWizard.tsx`
- `SetupWizard.tsx:73`：调用 `POST /api/bootstrap/setup`

## 新设计

### 1. 后端 `BootstrapStatusResponse` 增强

```java
public class BootstrapStatusResponse {
    private boolean initialized;
    private boolean inProgress;
    private String mode;
    // 新增
    private List<String> missingParts;   // ["admin_user", "default_tenant", "system_config"]
    private String reason;               // human-readable, e.g. "No admin user in iam_user"
}
```

`SystemConfigService.isInitialized()` 当前只看一个标记位。需要拆分为：

- `getMissingParts()` —— 逐项检查 admin 用户、默认租户、系统配置标记，返回缺失列表
- `isInitialized()` 保持 `missingParts.isEmpty()` 的语义（向后兼容）

`missingParts` 取值约定（常量化，避免魔术字符串）：
- `admin_user` —— `iam_user` 表无任何 admin 角色用户
- `default_tenant` —— `iam_tenant` 表无默认租户
- `system_config` —— `system_config` 表 `bootstrap.completed` 标记缺失

i18n key 由前端维护：`bootstrap.missing.admin_user` 等。

### 2. 前端 SSR loader 改造（`root.tsx`）

**移除 redirect**，改为注入 `bootstrapStatus` 到 `RootLoaderData`：

```ts
type BootstrapStatus = {
  initialized: boolean;
  inProgress: boolean;
  missingParts: string[];
  reason?: string;
};

// loader 返回
return { ..., bootstrapStatus };
```

逻辑：
- 后端不可达 → `bootstrapStatus = null`（不展示横幅，让正常错误处理生效）
- `initialized=true` → 不展示横幅
- `initialized=false` → 通过 root layout 渲染**顶部固定横幅**

### 3. 全局横幅组件

- 位置：root layout 顶部，所有路由（含 `/setup`、`/login`）都可见，但 `/setup` 路由本身不展示（避免重复）
- 样式：黄色（`bg-yellow-50 border-yellow-300`），不是红色
- 内容：
  - 标题：`系统未完成初始化`
  - 详情：动态拼接 `missingParts` 的 i18n 文案，如「缺少：管理员账户、默认租户」
  - CTA：「前往初始化」按钮 → `/setup`
- 不可关闭（避免用户误关后忘记初始化）

### 4. 业务路由空状态

未初始化时业务 API 必然 401/500。在 `ErrorBoundary` 加分支：
- 若 `bootstrapStatus.initialized=false`，渲染**专门的"系统未就绪"空状态卡片**（而非常规 401/500 页），CTA 同样指向 `/setup`
- 这样即使用户忽略横幅强行点菜单，也能拿到清晰提示

### 5. `/setup` 已初始化态

- **前端**：`SetupWizard.tsx` loader 内检查 `bootstrapStatus.initialized`，若 `true` 则渲染"系统已初始化"页面（带返回首页 CTA），不渲染向导表单
- **后端**：`POST /api/bootstrap/setup` 已有幂等检查，保持不动；可选增强错误码（如 `BOOTSTRAP_ALREADY_INITIALIZED`）方便前端区分

### 6. 后端启动日志警告

`BootstrapEngineService` 或 `ApplicationRunner` 在启动时检查 `isInitialized()`，若未初始化则打印**显眼 WARN 日志**：

```
================================================
  ⚠️  AuraBoot Bootstrap NOT INITIALIZED
  Missing: [admin_user, default_tenant]
  Run: scripts/reset-and-init.sh
  Or:  visit http://localhost:5173/setup
================================================
```

仅日志，**不自动 seed**（坚守"禁止自愈"红线）。

## 影响面

| 文件 | 改动 |
|------|------|
| `auraboot/platform/.../bootstrap/dto/BootstrapStatusResponse.java` | 新增 `missingParts` / `reason` 字段 |
| `auraboot/platform/.../saas/config/service/SystemConfigService.java` | 新增 `getMissingParts()`，`isInitialized()` 复用 |
| `auraboot/platform/.../bootstrap/controller/BootstrapController.java` | `getStatus()` 装配新字段 |
| `auraboot/platform/.../bootstrap/BootstrapEngineService.java` | 启动日志警告（可选放 ApplicationRunner） |
| `auraboot/web-admin/app/root.tsx` | 移除 redirect，注入 bootstrapStatus |
| `auraboot/web-admin/app/components/BootstrapBanner.tsx` | **新增**横幅组件 |
| `auraboot/web-admin/app/routes/setup/SetupWizard.tsx` | 已初始化态渲染 |
| `auraboot/web-admin/app/root.tsx` ErrorBoundary | 未就绪空状态分支 |
| i18n 资源 | 新增 `bootstrap.banner.*` / `bootstrap.missing.*` keys |

## 测试策略

### 后端集成测试

- `BootstrapControllerIntegrationTest`
  - 空库 → `getStatus` 返回 `initialized=false`、`missingParts=[admin_user, default_tenant, system_config]`
  - 仅缺 admin → `missingParts=[admin_user]`
  - 完整初始化 → `initialized=true`、`missingParts=[]`
  - 已初始化后 `POST /setup` 返回错误

### 前端 E2E（`auraboot/web-admin/tests/e2e/setup/`）

复用已有 `setup-wizard.spec.ts` 文件，扩展场景：

1. **未初始化访问根路由 → 不再 redirect，看到横幅**（断言 banner DOM + 文案 + CTA href）
2. **横幅 CTA 点击 → 跳转 /setup**
3. **完成 setup → 横幅消失**
4. **已初始化后访问 /setup → 显示"已初始化"页面，不显示向导表单**
5. **未初始化访问业务路由 → 看到"系统未就绪"空状态卡，不是普通 401**
6. **缺失项文案断言**：仅缺 admin 时横幅文案精确匹配 i18n

E2E 用 `auraboot/scripts/oss-test.sh`（OSS 测试脚本，见 memory `reference_oss_test_runner`）。

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| 移除 redirect 后，某些 E2E 假设"未初始化必跳 /setup" | grep 全仓 `redirect.*setup` / `toHaveURL.*setup`，逐个改为断言横幅 |
| `missingParts` 检查成本高（多次 DB query） | `getStatus` 加短 TTL 内存缓存（5s），避免高频刷新打 DB |
| 横幅在 `/login` 页可能挤压布局 | 横幅渲染于 `<body>` 顶部 fixed，业务内容 `padding-top` 兼容 |
| 国际化 fallback：未提供 i18n key 时显示 raw `missingParts` 值 | i18n 解析层已做 fallback，验收时检查无 key 泄漏 |

## 与既有红线的对齐

- **禁止自愈/Ensure**：✅ 不做 dev-profile 自动 seed
- **禁止魔术字符串**：✅ `missingParts` 值用常量类（如 `BootstrapMissingParts.ADMIN_USER`）
- **i18n 规范**：✅ 所有横幅/空状态文案走 i18n key
- **测试即交付件**：✅ 后端集成测试 + 前端 E2E 双覆盖
- **UX 交互设计**：✅ 横幅 + 空状态 + CTA + 已初始化拒绝四态俱全

## 后续 plan

实施 plan 拆分为 6 个 task，参见 `2026-04-17-bootstrap-ux-redesign.md`（待写）：

1. 后端 `BootstrapStatusResponse` 字段扩展 + `SystemConfigService.getMissingParts()` + 集成测试
2. 后端启动日志警告
3. 前端横幅组件 + i18n
4. 前端 `root.tsx` loader 改造（移除 redirect）+ 业务路由空状态
5. `SetupWizard.tsx` 已初始化态
6. E2E 扩展 + 浏览器手动验收
