# DDR-2026-05-26-docker-stack-convergence

> 类型:架构决策记录(docker 隔离栈收敛) · 决策人:owner(yaoyi) · 日期:2026-05-26
> 关联:`auraboot/docs/operations/ga-e2e-docker-stack.md`、`auraboot-enterprise/docs/standards/core/testing-docker-e2e.md`、`auraboot/docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md`

---

## Context(背景 / 触发链)

跑 T4 前端的 Web E2E 时,误用 `start-isolated.sh` 起栈,登录 401 失败。根因排查暴露了**两套 docker 栈各对一半**的结构性矛盾:

| 维度 | `start-isolated.sh` | `docker-ga-e2e-*.sh` |
|------|---------------------|----------------------|
| 端口/项目隔离 | ✅ slug + 端口偏移(`--offset` 可固定) | ❌ 固定 `COMPOSE_PROJECT_NAME=auraboot-ga-e2e` + 固定端口 |
| .m2 隔离 | ✅ 容器内 build + 独立 `$AURA_CONTAINER_CACHE_ROOT/m2` | ❌ **host** `./gradlew bootJar` + `publishToMavenLocal` + 企业 `buildAllPluginJars` → 写 host `~/.m2` |
| E2E 撒种 | ❌ 不撒种(backend community profile 不 auto-bootstrap) | ✅ `bootstrap.sh`:`/api/bootstrap/setup`(admin@auraboot.com/Test2026x) + `import-plugins --profile=e2e` |

**并发开发(≥2 worktree)的真实冲突点**:GA 栈固定 project name/端口 → 不能并存;GA 在 host 跑 gradle build/publish → host `~/.m2` 竞争。`start-isolated` 这两点都已解决,但不撒 E2E 种,所以跑不了 Web E2E。

### 核验到的关键事实
1. `application-community.yml` 明确"startup must NOT repair/bootstrap implicitly";无 `BootstrapStartupRunner` → `start-isolated`(community,test)backend **不 auto-bootstrap**,fresh DB 上第一个调 `/api/bootstrap/setup` 的写入者用 E2E 凭据即可。
2. `start-isolated.sh` 支持 `--offset=<n>` 固定端口偏移(跳过 probe)。
3. `isolated.yml` 的企业插件/jar 是 **optional**(OSS-only 默认挂空目录);OSS E2E `--edition=oss` 不需 host `buildAllPluginJars` → 该 .m2 冲突源对 OSS E2E 直接消失。企业 E2E 走已有 `ENTERPRISE_PLUGINS_DIR/JARS_DIR` 容器挂载(也不 host build)。
4. `docker-ga-e2e-bootstrap.sh` 撒种逻辑已通用,唯一障碍是 `API_BASE`/`PGPORT` 硬编码(line 28/64),无 `:-` 默认。
5. GA 脚本被广泛依赖:~6 个脚本(`p1-verify-in-docker`、`docker-ga-showcase-e2e`、`docker-cleanup-batch-up`、`check-oss-boundary`、`check-reset-init-contracts`、`ga-showcase-e2e`)+ CI `reset-init-contracts.yml` + 多份文档 → 不能直接删,必须保接口。

---

## Decision

**完整收敛到一套并发隔离底座 `start-isolated`,新增可选 `--e2e` 撒种;GA 三脚本降级为固定端口的 thin wrapper(保兼容)。任何 docker E2E 不再触碰 host `~/.m2`。host 本地快速路径(dev:full / 单测 / reset-and-init)原样保留。**

- 决策人:owner(yaoyi);时间:2026-05-26。
- 备注:assistant 评估完整收敛涉及 5-6 子改动 + 6 脚本回归,建议分阶段单独立项;owner 决定本会话全实施。

---

## 设计

### 1. `start-isolated --e2e`
起栈 + 健康后,对该 slug 的 `BE_PORT`/`PG_PORT` 跑撒种:`/api/bootstrap/setup`(admin@auraboot.com/Test2026x)+ `import-plugins --profile=e2e --edition=oss`(容器内,挂载的 `./plugins`)。**默认不撒种**(dev / 后端 IT 保持快内循环)。

### 2. 撒种逻辑端口参数化
`docker-ga-e2e-bootstrap.sh` 的 `API_BASE`/`PGPORT` 改 `${VAR:-default}` env 可覆盖,抽成可复用撒种入口;`start-isolated --e2e` 传该 slug 端口调用它。保留独立可调用(向后兼容)。

### 3. 企业 jar 移出 OSS E2E 路径
OSS E2E 走 `--edition=oss`,不需 host `buildAllPluginJars`。企业 E2E 用已有 optional 容器挂载(`ENTERPRISE_PLUGINS_DIR`/`ENTERPRISE_PLUGIN_JARS_DIR`)。GA wrapper 默认 OSS 路径。

### 4. GA 三脚本 → thin wrapper(固定 offset)
- `docker-ga-e2e-up.sh` → `start-isolated --slug=ga-e2e --offset=<专属高位固定值> --e2e`(保持 5174/6444/3501 端口语义)。
- `docker-ga-e2e-bootstrap.sh` → 保留为参数化撒种脚本(被 `--e2e` 复用 + 可独立调,指向 ga-e2e 端口)。
- `docker-ga-e2e-down.sh` → `stop-isolated --slug=ga-e2e`。
- slug=ga-e2e 复用同 `COMPOSE_PROJECT_NAME` → 现有 named volumes 兼容。

### 5. 回归
6 依赖脚本 + `reset-init-contracts` CI 逐个验证行为不变(端口、admin、插件 profile)。

### 6. 文档 + 防呆
- `start-isolated.sh` 头部 NOTE + 启动 echo:本栈默认不撒 E2E 种;Web E2E 加 `--e2e` 或用 GA wrapper。
- `auraboot-enterprise/AGENTS.md` 文档导航加 `ga-e2e-docker-stack.md` 入口。
- `testing-docker-e2e.md` 加红线:Web E2E 用收敛栈(`--e2e`),禁止用裸 `start-isolated`(不撒种 → 401);加"栈选择"小节。

---

## 验证策略(纳入 owner /goal 要求)

1. **撒种通路 + G4 闭环**:`start-isolated --slug=flow-sdk-g4 --e2e` 起栈(挂本 worktree 前端,含 G4 改动)→ 跑 `automation-validation-gate.spec.ts` golden E2E 绿(VG-01 blocked+field-error / VG-02 valid-passes)。
2. **GA wrapper 等价**:`docker-ga-e2e-up.sh`(wrapper)→ 端口/admin/插件与收敛前一致;`bootstrap` 幂等。
3. **6 依赖脚本回归**:逐个 dry-run / 实跑确认不 break。
4. **host 路径未被破坏(owner 要求)**:收敛改完 + 验证后,在本地 host 用 `reset-and-init`(host 模式)重置一份干净环境 + `git pull`,确认 host 快速开发/单测路径照常可用。

每个子改动**独立 commit + 当场验证**,防上下文压缩丢失。

---

## 风险 / 边界
- GA wrapper 固定 offset 可能与某并发 slug 撞端口 → 用专属高位 offset(如 GA 历史端口对应的偏移)规避。
- 收敛与 T4/G4 前端改动在同一 worktree,用独立 commit 分开,最终拆两个 PR(收敛 PR + G4 PR)。
- 本会话已很长,全实施风险:上下文压缩 + 大量 docker 起停耗时 → 缓解:分阶段 + 频繁 commit + 先做能跑通 G4 E2E 的阶段①。

## 实施蓝图 — 核验更新(2026-05-26)

实地核验后,基础设施大量已预埋,复杂度低于初估:

1. **`start-isolated` 已内置 ga-e2e slug 特判**:`compute_initial_offset()` 对 `SLUG=ga-e2e` 返回 `0` → 端口=BASE=GA 历史端口(PG 5433 / BE 6444 / VITE 5174 / BFF 3501)。`COMPOSE_PROJECT_NAME=auraboot-ga-e2e` 一致。
2. **`isolated.yml` 已有 `playwright-runner` service**(profile `playwright-runner`;`PLAYWRIGHT_BASE_URL=http://isolated-frontend:5173`,`BACKEND_URL=http://backend:6443`)。
3. `start-isolated` 容器内 build backend + 独立 `$AURA_CONTAINER_CACHE_ROOT/m2` → 消除 GA 的 host build/`~/.m2` 冲突。
4. **隐藏依赖**:`docker-ga-showcase-e2e.sh` 直接用 `ga-e2e.override.yml` + `ga-e2e-runner` profile,需迁到 `isolated.yml` + `playwright-runner`。其余 5 脚本走 up/down/bootstrap 接口或 `bash -n`,wrapper 化安全。

### 改动清单
- `docker-ga-e2e-up.sh` → `exec scripts/dev/start-isolated.sh --slug=ga-e2e --wait "$@"`(容器 build;不带 `--e2e`,撒种由 bootstrap.sh 做避免重复)。丢弃 host wrapper-jar guard / host-jar build / host 企业 jar build(企业 E2E 改用 `ENTERPRISE_PLUGIN_JARS_DIR` 容器挂载)。
- `docker-ga-e2e-down.sh` → `exec scripts/dev/stop-isolated.sh --slug=ga-e2e "$@"`(`--purge` 透传)。
- `docker-ga-e2e-bootstrap.sh` → 不变(端口 6444/5433 = offset0 栈端口)。
- `docker-ga-showcase-e2e.sh` → `compose_args` 改 `-f docker-compose.isolated.yml --profile isolated --profile cache --profile playwright-runner`;runner service `ga-e2e-runner`→`playwright-runner`;frontend `ga-e2e-frontend`→`isolated-frontend`(容器名仍 `auraboot-ga-e2e-frontend`)。
- `ga-e2e.override.yml` 保留文件(减回归面;`check-oss-boundary` 白名单不动),仅脚本切栈。

### 验证(收尾)
停 canonical GA 栈 → GA up(wrapper) + bootstrap → admin 200 → showcase smoke 1 spec → 回归 p1-verify-in-docker / docker-ga-showcase-e2e / check-oss-boundary / check-reset-init-contracts / ga-showcase-e2e(cleanup-batch 独立) + CI `reset-init-contracts.yml`。

## 反向触发(何时重评)
- 若 GA wrapper 固定端口与并发隔离语义冲突无法调和 → 退回"GA 独立薄栈 + start-isolated 各自演进"。
- 若企业 E2E 在容器挂载 jar 路径出现 PF4J 加载问题 → 单独处理企业 jar 容器化 track。
