# Reset / Init Lifecycle

本页定义本地与 Docker 环境重置脚本的职责边界，避免把“重置 DB、启动服务、导入插件、同步 marketplace、跑 E2E”混在一起理解。

## 环境分类

- Host 环境：直接在本机跑 Postgres、后端、前端。入口保留为 `scripts/oss-reset-and-init.sh`，企业仓保留自己的 `scripts/reset-and-init.sh`。
- Docker OSS 环境：统一入口是 `scripts/env/reset-and-init.sh --product=oss --runtime=docker`，底层使用 GA E2E compose 栈。
- Docker 企业环境：统一入口是 `scripts/env/reset-and-init.sh --product=enterprise --runtime=docker`，底层使用 isolated compose 栈，并挂载企业插件目录与企业 backend handler jars。
- E2E 验证：不是 reset 的一部分。reset 只保证服务、插件、catalog、基础 seed ready；Playwright smoke/full gate 由调用方显式执行。

## 脚本职责

- `scripts/env/reset-and-init.sh`：规范化入口。只负责解析 product/runtime/profile，选择底层 host 或 Docker 工作流，并在 Docker 工作流里串起 bootstrap、插件导入、marketplace catalog 同步。
- `scripts/docker-ga-e2e-up.sh`：启动 OSS Docker 栈并等待 backend/frontend 可访问。
- `scripts/docker-ga-e2e-bootstrap.sh`：对 OSS Docker 栈执行 bootstrap、导入 OSS 插件、创建测试用户、生成 Playwright storage、运行 showcase seed sequence。
- `scripts/dev/import-isolated-plugins.sh`：对 isolated 栈导入指定 profile 的插件。企业与 OSS 同名插件冲突时，enterprise edition 优先使用企业目录，企业插件应在 manifest 中声明 `edition`、`upgradesFrom` / `replaces`。
- `scripts/sync-marketplace-catalog.sh`：从 plugin manifests 同步 marketplace catalog 到 System tenant。它不安装插件，也不替代插件导入。
- `scripts/seed-marketplace.sh`：旧兼容入口。实际职责已归一到 `sync-marketplace-catalog.sh`，保留这个文件只是为了老脚本和旧文档不立刻失效。

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

## 推荐验证层级

1. Health / preflight：Docker disk、backend health、frontend health、bootstrap status。
2. Contract：`./scripts/check-reset-init-contracts.sh`。
3. Targeted smoke：marketplace smoke、installed plugins smoke、插件升级 API/路径 smoke。
4. Slice E2E：选择最小 Playwright slice 验证页面能消费 marketplace 和已安装插件数据。
5. Full gate：只在环境健康且 targeted/slice 通过后执行。
