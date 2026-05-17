# Reset / Init Lifecycle

本页定义本地与 Docker 环境重置脚本的职责边界，避免把“重置 DB、启动服务、导入插件、同步 marketplace、跑 E2E”混在一起理解。

## 背景与问题复盘

本轮 reset/init hardening 暴露出一个核心问题：`/api/bootstrap/setup` 被当成了“基础初始化 + 插件导入 + demo seed 开关”的混合入口。它原本适合首次安装向导和 Quickstart 使用，但实际执行链路里又通过 `BuiltinPluginImportService` 同步导入 core/demo 插件，导致职责变宽、耗时变长、失败面变大。

这带来了几个直接后果：

- Quickstart 只想验证“空库能初始化并登录”，却被迫等待 core/demo 插件导入，BFF 30 秒默认 proxy timeout 会把长耗时 setup 包成 502。
- Host reset、Docker reset、企业 reset 对“插件到底由谁导入”理解不一致：有的依赖 bootstrap 内部导入，有的脚本自己导入。
- `seedDemoData=true` 同时表达“导 demo 插件”和“灌 demo 数据”的意图，语义过载。
- `scripts/dev/plugin-import-profiles.json` 里的 `default` 既不是最小 core，也不是完整 demo，后续维护者容易误解。

因此新的方向是把系统初始化、插件导入、demo seed、marketplace catalog sync 分成四个明确阶段，由 reset/init 脚本编排，而不是把它们塞进 `/api/bootstrap/setup`。

本轮 Quickstart 失败不是端口映射错误：`localhost:3000` 已能通过 frontend BFF 访问 backend，`/api/bootstrap/status` 也能返回 200。真正问题是 `/api/bootstrap/setup` 内部执行了插件导入，超过了 BFF 默认 30 秒 proxy timeout，最后表现成 502。把 `/api/bootstrap/setup` 加进“长耗时 API 白名单”只能掩盖症状，不能解决职责混乱，所以最终修复方向必须是收窄 bootstrap，而不是继续扩大 timeout。

## 环境分类

- Host 环境：直接在本机跑 Postgres、后端、前端。入口保留为 `scripts/oss-reset-and-init.sh`，企业仓保留自己的 `scripts/reset-and-init.sh`。
- Docker OSS 环境：统一入口是 `scripts/env/reset-and-init.sh --product=oss --runtime=docker`，底层使用 GA E2E compose 栈。
- Docker 企业环境：统一入口是 `scripts/env/reset-and-init.sh --product=enterprise --runtime=docker`，底层使用 isolated compose 栈，并挂载企业插件目录与企业 backend handler jars。
- E2E 验证：不是 reset 的一部分。reset 只保证服务、插件、catalog、基础 seed ready；Playwright smoke/full gate 由调用方显式执行。

Docker 镜像地址、Maven / pnpm cache、host shared volume 只属于构建与启动加速策略，不应改变初始化职责。可以继续用 host shared cache 降低镜像构建成本，但它不能替代 bootstrap、插件导入和 seed 的边界拆分。

## 脚本职责

- `scripts/env/reset-and-init.sh`：规范化入口。只负责解析 product/runtime/profile，选择底层 host 或 Docker 工作流，并在 Docker 工作流里串起 bootstrap、插件导入、marketplace catalog 同步。
- `scripts/docker-ga-e2e-up.sh`：启动 OSS Docker 栈并等待 backend/frontend 可访问。
- `scripts/docker-ga-e2e-bootstrap.sh`：对 OSS Docker 栈执行 bootstrap、导入 OSS 插件、创建测试用户、生成 Playwright storage、运行 showcase seed sequence。
- `scripts/import-plugins.sh`（目标形态）：唯一插件导入执行器。按 profile 导入不同插件列表，负责登录、选择 business tenant、按顺序导入、retry、验证最新 import history 成功。
- `scripts/dev/import-isolated-plugins.sh`（过渡期）：对 isolated 栈导入指定 profile 的插件。后续应收敛为 `scripts/import-plugins.sh` 的 Docker/isolated 参数组合或兼容 wrapper。
- `scripts/sync-marketplace-catalog.sh`：从 plugin manifests 同步 marketplace catalog 到 System tenant。它不安装插件，也不替代插件导入。
- `scripts/seed-marketplace.sh`：旧兼容入口。实际职责已归一到 `sync-marketplace-catalog.sh`，保留这个文件只是为了老脚本和旧文档不立刻失效。

## 目标流水线

完整 reset/init 流程应固定为：

```text
reset DB
  -> start backend and wait for health
  -> POST /api/bootstrap/setup
  -> import plugins by profile
  -> sync marketplace catalog
  -> run runtime seeds / demo seeds
  -> generate Playwright storage state
  -> run targeted smoke / E2E gates when requested
```

`/api/bootstrap/setup` 只创建平台最小生命体；插件导入、marketplace 和 seed 都由脚本显式编排。

## Marketplace Seed 设计

Marketplace catalog 是“可发现插件目录”，不是“已安装插件列表”。因此它应该由插件 manifest 派生，并在 reset 流程中作为独立的 catalog sync 步骤执行。

插件自身仍拥有自己的功能 seed、demo data、config resources；marketplace catalog sync 只把插件元数据投影到 `ab_marketplace_plugin` / `ab_marketplace_version`。这允许 OSS 插件和企业插件共存，也允许企业同名插件通过 manifest metadata 表达“从 OSS/template 升级而来”。

## Import History 契约

插件导入校验看“每个 pluginId 最新一条 import history 是否 success”，不是要求整个 history 里没有 failed。

原因是导入脚本允许 retry：例如第一次遇到短暂连接中断会记录 failed，第二次重试成功后，最终环境应视为可用。历史 failed 作为审计记录保留，不能反过来让 reset 误判失败。

如果最新状态不是 success，reset 必须失败；如果只有历史 failed 且最新 success，reset 可以继续。

## Bootstrap 契约

`/api/bootstrap/setup` 是默认管理员与租户初始化的唯一写入口。Docker compose、Kubernetes pod、host `bootRun` 启动阶段都不应依赖 `AURABOOT_BOOTSTRAP_ENABLED` 或 startup runner 自动写库。

Quickstart、reset/init、E2E bootstrap 脚本必须在服务健康后显式判断 `/api/bootstrap/status`，未初始化时调用 `/api/bootstrap/setup`，然后再执行登录、插件导入、marketplace 同步或 Playwright storage 生成。

`/api/bootstrap/setup` 的职责范围限定为：

- 创建或修正 system config。
- 创建 System Tenant。
- 创建 admin user。
- 创建默认 Business Tenant。
- 建立 admin 在 System Tenant 和 Business Tenant 的 membership。
- 初始化 Business Tenant 的基础 roles / permissions。
- 初始化 System Tenant 下的 `platform_admin` role，并给 admin 授权。

`/api/bootstrap/setup` 不应承担：

- 导入 `core-meta`、`core-bpm`、`core-aurabot`、`page-manager`、`org-management`、`platform-admin` 等 core 插件。
- 导入 demo 插件。
- 执行 showcase / CRM / workflow / enterprise demo seed。
- 同步 marketplace catalog。

`seedDemoData` 不再作为 bootstrap API 的执行开关。为了兼容旧客户端，字段可以短期保留在 DTO 中，但服务端应忽略它，或只返回明确的 deprecation 语义。是否导入 demo 插件和 demo 数据由 reset/init 脚本的 profile 参数决定。

## 插件导入契约

插件导入应收敛为“一个脚本 + 一个 profile 配置文件”：

- 执行器：`scripts/import-plugins.sh`
- 列表源：`scripts/dev/plugin-import-profiles.json`

执行器负责所有环境通用逻辑：

- 等待或检查 backend health。
- 以 admin 登录。
- 如果登录未返回 business tenant JWT，则通过 tenant selection 选择 business tenant。
- 根据 profile 顺序逐个调用 `/api/plugins/import/import-directory-sync`。
- 支持 retry。
- 记录每个成功导入返回的 pluginId。
- 用“每个 pluginId 最新一条 import history 必须为 success”作为最终校验。

环境差异只通过参数表达：

```bash
scripts/import-plugins.sh \
  --profile=demo \
  --edition=oss \
  --backend-url=http://localhost:6443 \
  --plugin-root=/Users/ghj/work/auraboot/auraboot/plugins
```

Docker 或 enterprise 只替换路径和 edition：

```bash
scripts/import-plugins.sh \
  --profile=enterprise-demo \
  --edition=enterprise \
  --backend-url=http://localhost:${BE_PORT} \
  --plugin-root=/app/plugins \
  --enterprise-plugin-root=/app/plugins-enterprise
```

## Profile 命名

`default` 这个名字废弃。原因是它没有清晰语义：当前列表既不是最小 core，也不是完整 demo，容易被误认为“平台默认必须导入的最小集”。

目标 profile 命名如下：

| Profile | 语义 | 插件范围 |
|---------|------|----------|
| `core` | 平台最小能力插件 | `core-meta`, `core-bpm`, `core-aurabot`, `page-manager`, `org-management`, `platform-admin` |
| `demo` | OSS demo 环境 | `core` + `crm-starter`, `showcase`, `agent-control-plane`, `workflow-demo` |
| `e2e` | OSS E2E 环境 | `demo` + E2E 必需插件，例如 `core-announcement`, `test-fixtures` 等 |
| `enterprise-demo` | 企业完整 demo 环境 | 企业 profile 列表，enterprise 同名插件优先 |
| `pcba-agent` | PCBA / agent 专项验证环境 | PCBA 专项插件列表 |

兼容策略：

- `default` 可以短期保留为 alias，但脚本应打印 deprecation warning。
- 新文档、新脚本、新 CI 不再使用 `default`。
- 后续迁移完成后删除 `default`。

## OSS 与企业同名插件

企业版允许与 OSS 存在同名插件，设计意图是“OSS 插件可无缝升级为企业同名插件”。导入脚本必须让这个规则显式化：

- `--edition=oss`：只从 OSS plugin root 查找。
- `--edition=enterprise`：优先从 enterprise plugin root 查找，找不到再从 OSS plugin root 查找。
- `--edition=auto`：有 enterprise root 时按 enterprise 优先，否则按 OSS。

当企业插件 shadow OSS 同名插件时，企业插件 manifest 必须声明 `edition`、`upgradesFrom` / `replaces` 等升级元数据。marketplace sync 和 upgrade smoke 依赖这些元数据判断“可升级”而不是把同名插件视为冲突。

## 各入口接入方式

| 入口 | 接入方式 |
|------|----------|
| Quickstart workflow | 只调用 `/api/bootstrap/setup` 并验证登录；不导 demo，不跑 showcase seed。 |
| OSS host reset | `bootstrap/setup` 后调用 `scripts/import-plugins.sh --profile=demo --edition=oss`；`SKIP_SEED=1` 时可降为 `--profile=core`。 |
| OSS Docker reset | `docker-ga-e2e-bootstrap.sh` 只负责 bootstrap、测试用户、storage state；插件导入委托 `scripts/import-plugins.sh --profile=e2e --edition=oss`。 |
| Enterprise host reset | 删除手写逐个导入列表，委托统一导入脚本；profile 使用 `enterprise-demo` 或企业专项 profile。 |
| Enterprise Docker reset | `scripts/env/reset-and-init.sh --product=enterprise --runtime=docker` 调用统一导入脚本，传入 `/app/plugins` 与 `/app/plugins-enterprise`。 |
| Mobile E2E bootstrap | 删除内置插件数组，复用统一 profile，移动端只保留自己的 seed/校验步骤。 |
| Setup Wizard | 只提交基础 bootstrap 字段；移除或隐藏 `Load demo data`，demo 导入作为后续明确动作处理。 |

## 迁移计划

1. 收窄 backend `/api/bootstrap/setup`：移除 `executeRuntimeSetup` 里的 built-in plugin import 调用，保留基础 bootstrap 与 progress/finalize。
2. 在 `plugin-import-profiles.json` 中新增 `core`、`demo`、`e2e`，把 `default` 标记为 deprecated alias。
3. 新增或重命名统一导入脚本 `scripts/import-plugins.sh`，复用 `import-isolated-plugins.sh` 中已有的登录、tenant selection、retry、latest history 校验逻辑。
4. 改 OSS host reset：恢复独立插件导入阶段，不再依赖 setup 内部导插件。
5. 改 OSS Docker / enterprise Docker / mobile bootstrap：删除内置插件列表或手写导入，统一委托 profile 导入。
6. 改 Quickstart：`/api/bootstrap/setup` 不传 `seedDemoData=true`，只验证初始化和登录。
7. 改 Setup Wizard：删除 demo seed checkbox，避免把 demo 数据表达为 bootstrap 职责。
8. 更新 reset-init contract tests，明确断言 setup 不驱动插件导入，导入脚本按 profile 执行。
9. 更新企业仓运行文档中“core/demo 插件由 bootstrap 导入”的旧说法。注意不要覆盖 Android/mobile 相关未提交改动。

## 推荐验证层级

1. Health / preflight：Docker disk、backend health、frontend health、bootstrap status。
2. Contract：`./scripts/check-reset-init-contracts.sh`。
3. Targeted smoke：marketplace smoke、installed plugins smoke、插件升级 API/路径 smoke。
4. Slice E2E：选择最小 Playwright slice 验证页面能消费 marketplace 和已安装插件数据。
5. Full gate：只在环境健康且 targeted/slice 通过后执行。
