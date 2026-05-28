# Docker 多 Worktree 开发环境重构分析

## 背景

原始目标不是把 Docker 占用压到最低,而是支持多套 worktree 并行开发:

- 每个分支有相对独立的运行环境
- 能独立 reset / seed / E2E / 合并前验证
- 不污染 canonical host 环境
- Docker 和依赖缓存不会随 worktree 数量线性爆炸

推荐原则是:

```text
隔离运行态,共享构建缓存。
隔离数据,不隔离依赖。
日常半 Docker,合并前全 Docker。
```

## 当前实现状态

当前 OSS 仓库已经具备一套 per-worktree isolated stack:

- `docker-compose.isolated.yml` 通过 `COMPOSE_PROJECT_NAME` 隔离容器、网络和 project-scoped volumes。
- `scripts/dev/start-isolated.sh` 按 branch slug 自动分配 Postgres / backend / Vite / BFF / Redis host 端口。
- `.aura-stack/<slug>.env` 记录每个 worktree 的端口和 compose project。
- 共享依赖缓存已改为 host-backed bind mount,默认位于 `~/.cache/auraboot`。
- `scripts/dev/stop-isolated.sh --purge` 可以清理某个 slug 的 project volumes。

这说明当前方向已经接近正确架构,不是从零开始。

## 当前主要问题

### 1. 规则表达过粗

企业侧规则目前倾向于:

```text
>=2 worktree 时触碰 Postgres / Redis / backend / vite / BFF / E2E 必须走 isolated Docker stack。
```

这个规则能防止污染,但长期会把日常开发也推向 full Docker,成本偏高。更准确的边界应该是:

- 会写共享运行态或共享发布态的操作必须隔离。
- 日常 host backend / Vite / BFF 可以存在,但必须使用 worktree 专属端口并连接 worktree 专属 DB / Redis。
- 完整 E2E、合并前验证、并行 subagent 验证必须 full Docker。

### 2. 前端依赖缓存会重复占盘

`docker-compose.isolated.yml` 当前为每个 compose project 保留:

```yaml
isolated_node_modules:
isolated_web_admin_node_modules:
```

这是正确的隔离边界,因为 Linux `node_modules` 不应该跨 project 共享。但 isolated frontend 没有统一共享 pnpm store,会导致每个 stack 重复下载和保存 package tarballs / store 数据。

应保留 per-stack `node_modules`,同时使用 host-backed 共享 pnpm store。

### 3. Playwright 浏览器缓存没有统一边界

当前 isolated stack 使用官方 Playwright image,大部分浏览器依赖来自 image layer。若后续在 isolated stack 内执行 browser install 或 runner 逻辑,应统一使用 host-backed `ms-playwright` cache,避免每个 stack 生成一份 `/ms-playwright`。

### 4. backend Dockerfile 语义不一致

`docker-compose.isolated.yml` 默认:

```yaml
dockerfile: ${ISOLATED_BACKEND_DOCKERFILE:-Dockerfile}
```

但 `scripts/dev/start-isolated.sh` 的帮助文本多处说 `Dockerfile.dev`。这会误导使用者:

- 以为默认是 dev bootRun stack
- 实际默认是 production-style `Dockerfile` 构建出来的 bootJar image
- 默认 `--no-build` 时容易复用旧 image,需要使用者知道何时 `--rebuild`

应明确:默认 isolated full stack 是合并前/验证语义,使用 `Dockerfile`;若要 Docker dev backend,显式传 `ISOLATED_BACKEND_DOCKERFILE=Dockerfile.dev`。

### 5. `publishToMavenLocal` 不是普通缓存

Gradle / Maven dependency cache 可以共享,但 `publishToMavenLocal` 是共享发布态写入。多 worktree 同时写默认 `~/.m2/repository` 会让 enterprise 读到错误 SNAPSHOT。

多 worktree 下应优先使用 per-worktree Maven repo:

```bash
export AURA_MAVEN_REPO="$PWD/.m2/repository"
./gradlew publishToMavenLocal -Dmaven.repo.local="$AURA_MAVEN_REPO"
```

enterprise 侧构建也必须读同一个:

```bash
GRADLE_OPTS="-Dmaven.repo.local=$AURA_MAVEN_REPO" ./gradlew bootJar
```

### 6. guard 以 worktree 数量判定,偏保守

`scripts/lib/multi-worktree-guard.sh` 当前只看 `git worktree list` 行数。只要存在第二个 worktree,即使它休眠,也会拒绝 host 操作。

短期这是安全默认值;长期应升级为 active worktree 判断:

- 是否存在运行中的 `auraboot-<slug>` compose project
- 是否存在该 worktree 的 host backend / Vite / BFF 进程
- 是否存在 `.aura-stack/<slug>.env` 且容器仍在运行
- 是否处于 subagent 并发验证上下文

## 是否会浪费大量磁盘空间

会,如果长期多 worktree full Docker 并行并保留 volumes。主要风险不在 Postgres 本身,而在依赖和 build artifacts。

高风险:

- 每个 stack 一份 `/repo/node_modules`
- 每个 stack 一份 `/repo/web-admin/node_modules`
- isolated stack 未共享 pnpm store 时的重复 package store
- 多个 backend image / build layer
- 长期保留的 Postgres / Redis / backend data volumes

中风险:

- Playwright runner 的 node_modules
- Playwright trace / video / test-results
- worktree 自身源码、Gradle build、前端 build 目录

低风险:

- Docker base image layer
- pgvector / redis / Playwright 官方 image
- Gradle / Maven dependency cache,前提是作为共享 cache 管理,不要混入 publish 输出

粗略估算:

```text
1 个 full isolated stack:
  node_modules volumes: 2-5G
  backend image/build layers: 1-3G
  DB/data: 0.5-2G

5 个长期保留 stack:
  20-50G+ 很容易出现
```

## 推荐目标架构

### Mode A: 日常开发

```text
Docker:
  Postgres / Redis / MinIO

Host:
  Spring Boot
  Vite
  BFF

约束:
  每个 worktree 使用独立端口
  每个 worktree 连接独立 DB / Redis
  不跑完整 E2E
```

### Mode B: 合并前验证

```text
Docker:
  Postgres
  Redis
  backend
  frontend
  Playwright runner

约束:
  full Docker isolated stack
  独立 compose project
  独立 DB volume
  独立 Playwright output
  跑完默认 down,必要时 purge
```

### Mode C: CI / nightly / 长跑

```text
Docker:
  fresh stack
  fresh DB
  固定 artifact 目录
  严格清理策略
```

## 隔离与共享边界

必须 per-worktree:

- `COMPOSE_PROJECT_NAME`
- Postgres volume 或 DB schema
- Redis 实例或 namespace
- backend / Vite / BFF host ports
- Playwright storageState
- Playwright output / traces
- E2E seed `testRunId`
- Maven publish 输出

可以共享:

- Docker base layers
- Docker BuildKit cache
- Gradle dependency cache
- Maven dependency cache
- pnpm store
- Playwright browser cache 或官方 Playwright image layer

禁止共享写入:

- 默认 host `aura_boot` DB
- 默认 `localhost:5432` / `6379` / `6443` / `5173` / `3500`
- 默认 `~/.m2/repository` 的 `publishToMavenLocal`

## 建议落地优先级

P0:

- 将规则从“一律 full Docker”改为“共享运行态/发布态必须隔离,日常 host app 可连接 isolated infra”。
- isolated stack 增加共享 `aura_pnpm_store` 和 `aura_playwright_browsers`。
- 修正 `Dockerfile` / `Dockerfile.dev` 说明不一致。
- 文档中单独强调 `publishToMavenLocal` 是发布态写入,多 worktree 应使用 per-worktree m2。

P1:

- 新增 infra-only 启动脚本,只启动 Postgres / Redis / MinIO 并写 `.aura-stack/<slug>.env`。
- guard 放行已指向 isolated PG / r2 env 的 reset/init,并放行显式 per-worktree `maven.repo.local` 的 publish。
- 增加磁盘诊断脚本,统计 Aura 相关 Docker volumes / images / worktrees / artifacts。
- stop/list 脚本兼容 full 与 infra-only 两种 stack,并展示 stack mode / MinIO 端口。

P2:

- stack lease pool,复用 warm stack。
- 自动清理 10 天以上未使用的 stack volume。
- E2E artifact 按日期清理。

## 2026-05-12 full isolated stack 验证结论

已执行真实 full isolated stack smoke:

```bash
scripts/dev/start-isolated.sh --slug=full-smoke --wait --skip-pull
```

结论:

- full stack 能启动并完成服务级验证: Postgres、Redis、backend、Vite、BFF 均 healthy。
- backend `/actuator/health` 最终返回 `200 {"status":"UP"}`。
- BFF `/health` 返回 200,且 `springBoot.status=healthy`。
- 验证后已 purge `full-smoke` 容器、项目卷、env 文件和验证镜像。

验证中发现并修正:

- Spring Boot `diskspace` health 在 Docker Desktop overlay filesystem 下可能把 `/app/.` 识别为 0 bytes free,导致 actuator `DOWN`。Docker backend 环境已关闭 `MANAGEMENT_HEALTH_DISKSPACE_ENABLED`,磁盘容量由 `scripts/dev/doctor-disk.sh` 负责。
- shared cache 早期改为 external named volumes 以避免 `down -v` 误删;后续已进一步改为 host-backed bind mount,从 Docker VM 移出依赖缓存。
- `cleanup-stack.sh` 修复了只传 `--images` 时空数组触发 `nounset` 的问题。

磁盘结论:

- 当前 Docker VM 只有 `31.4G`,full stack 验证期间到达 100%。
- 单个 full stack 的主要增量约为: `node_modules` 724MB、Postgres 94MB、backend image unique 409MB;另有共享 Playwright cache 约 1.54GB。
- 这证明 full isolated stack 不适合长期常驻多套。日常应优先 infra-only,full stack 用于合并前/E2E 后立刻 purge。
- `start-isolated.sh` 已增加 Docker VM free-space preflight,默认要求 `AURA_MIN_DOCKER_FREE_MB=2048`;低于阈值时在写 stack env 或启动 Compose 前退出。
- `doctor-disk.sh` 已增加 Docker VM filesystem section,用于直接观察 Docker Desktop VM 内部剩余空间。
- 已用 targeted cleanup 清理 stale candidates: `auraboot-agent-hardening-verify`、`auraboot-agent-hardening-host`。清理后 `doctor-disk.sh` 显示 stale candidates 为 `none`,Docker VM free space 从约 `852M` 提升到约 `3.0G`。
- 已进一步删除未运行的旧 Aura/test 验证镜像并 prune BuildKit cache。最终 Docker VM free space 提升到约 `9.4G`,使用率约 `69%`。
- 在新方案下,`32G` Docker VM 可以支撑日常 infra-only + 短时 full stack 验证;但仍不适合多套 full stack 长期常驻。完整 E2E 或多套并行前仍应先跑 `scripts/dev/doctor-disk.sh`。

## 2026-05-12 cache 位置修正

前一版使用 Docker external named volumes:

```text
aura_gradle_cache
aura_m2_cache
aura_pnpm_store
aura_playwright_browsers
```

它解决了跨 stack 共享和 `down -v` 误删问题,但缓存仍然位于 Docker VM,不符合“最省 Docker VM 磁盘”的目标。

已修正为 host bind mount,默认根目录:

```text
~/.cache/auraboot
  container-linux/
    gradle/
    m2/
    pnpm-store/
    ms-playwright/
```

Compose 映射:

```text
~/.cache/auraboot/container-linux/gradle        -> /gradle-cache
~/.cache/auraboot/container-linux/m2            -> /m2-cache
~/.cache/auraboot/container-linux/pnpm-store    -> /pnpm-store
~/.cache/auraboot/container-linux/ms-playwright -> /ms-playwright
```

这样 Docker VM 只保留运行态隔离数据、镜像层和 BuildKit cache;依赖缓存落在 host,更接近最初方案。`AURA_CACHE_ROOT` 可覆盖默认目录。
pnpm store 和 Playwright browser cache 按平台分目录,避免 macOS host 和 Linux container 共写同一缓存造成 native package 或 browser binary 污染。

## 待确认: 最终目标方案

### 目标原则

```text
运行态隔离,依赖缓存共享。
Docker VM 只放必要运行态和镜像层。
日常开发轻量,合并前验证完整。
```

### Mode A: 日常开发(默认推荐)

```text
Docker:
  Postgres
  Redis
  MinIO(按需)

Host:
  Spring Boot
  Vite
  BFF
  Playwright 调试/局部测试
```

使用:

```bash
scripts/dev/start-dev-infra.sh --slug=<topic> --with-storage
source scripts/dev/r2-env-export.sh <topic>
```

适用:

- 日常功能开发
- API / 页面局部验证
- 不需要完整容器化复现的调试

收益:

- Docker VM 只承担 stateful infra。
- 不拉 Playwright 大镜像。
- host 开发体验最好,浏览器调试和日志最直接。

### Mode B: 合并前 full stack 验证

当前已落地:

```text
Docker:
  Postgres
  Redis
  backend
  frontend(Vite+BFF)

Host cache:
  Gradle dependency cache
  Maven dependency cache
  pnpm store
  Playwright browser cache
```

已实施调整:

```text
isolated-frontend:
  已从 mcr.microsoft.com/playwright:* 改为 node:22-bookworm-slim

Playwright:
  默认在 host 运行
  按需启用独立 playwright-runner profile
```

原因:

- `isolated-frontend` 的职责是 Vite+BFF,不是 E2E runner。
- Playwright 官方镜像约 3.7G,作为 frontend 基础镜像会让每次 full stack 都承担不必要的 Docker VM 镜像成本。
- host Playwright 更便于 headed/debug/trace 调试。

保留可选 runner 的原因:

- CI-like Linux 浏览器环境验证仍有价值。
- 某些字体、浏览器依赖、容器网络问题只能在容器 runner 里复现。

### Mode C: CI / nightly

```text
Docker:
  fresh Postgres / Redis
  backend
  lightweight frontend
  playwright-runner profile(需要时)

策略:
  fresh DB
  独立 Playwright output
  跑完自动 cleanup
```

### 磁盘模型

Docker VM 保留:

```text
必要镜像层:
  pgvector
  redis
  backend image
  lightweight node frontend image
  playwright image(仅 runner profile 需要时)

运行态:
  Postgres volume
  Redis volume
  per-stack node_modules volumes
  backend_data

Build:
  BuildKit cache(可定期 prune)
```

Host 保留:

```text
~/.cache/auraboot/container-linux/gradle
~/.cache/auraboot/container-linux/m2
~/.cache/auraboot/container-linux/pnpm-store
~/.cache/auraboot/container-linux/ms-playwright
```

预期效果:

- 日常 infra-only: Docker VM 增量很小。
- full stack: 避免依赖缓存进入 Docker VM。
- 切换 lightweight frontend 后: 避免默认拉 3.7G Playwright image。
- Playwright image 只在容器 runner profile 中按需拉取。

### 是否损害开发便捷性

不会,反而更清晰:

- 日常 host Playwright 调试更方便。
- Vite/BFF 容器更轻、更快启动。
- E2E 入口通过 `PLAYWRIGHT_BASE_URL` / `BACKEND_URL` 统一,host runner 和 container runner 可以共存。

需要注意:

- host runner 和 container runner 的浏览器环境不完全一致。
- CI-like 验证仍应保留 container runner profile。
- E2E 不应硬编码 `backend:6443`,必须走 env contract。

## Review resolution

本轮 review 结论:主线采用,但 contract 需要收紧。

已满足或已补强:

- `start-dev-infra.sh` 已存在,应升为 P0,因为 Mode A 是默认入口。
- cache 已从 Docker named volume 改为 host bind mount。
- container cache 已按平台分目录:默认 `~/.cache/auraboot/container-linux`。
- `publishToMavenLocal` 已通过 Gradle guard 要求 per-worktree repo;但需继续明确它会影响 dependency resolution,不是完美拆分。
- guard 方向保持保守:多 worktree 默认拒绝 canonical 写入,只有目标明确 isolated 时放行。
- `doctor-disk.sh` 保留为 full stack / E2E 前置检查。

下一轮 P0 实施状态:

1. 将 Mode B 拆清楚:
   - B1 isolated service smoke: backend container + Vite/BFF container + host Playwright。
   - B2 production-like pre-merge: backend bootJar image + frontend production build/serving + optional container runner。
   - 状态:已写入 SOP,默认本地 full stack 归类为 B1;B2 作为显式 pre-merge / CI-like contract。
2. Maven 集成中期方案:探索 per-worktree file Maven repo 或 composite build,不要长期依赖默认 `~/.m2`。
   - 状态:已明确 `maven.repo.local` 同时承载 dependency resolution 和 publish output;当前 helper 优先正确性,长期建议 file repo / composite build。
3. E2E artifact 默认按 slug/date 分目录,full stack 默认短生命周期,跑完 purge。
   - 状态:已在 stack env、`r2-env-export.sh`、Playwright config、optional runner profile 中接入 `PW_E2E_RUN_ROOT` / `PW_ARTIFACT_DIR` / `PW_REPORT_DIR` / `PW_RESULTS_JSON` / `PW_STORAGE_DIR`。

已进入实现:

- `isolated-frontend` 默认镜像为 `node:22-bookworm-slim`。
- 默认 isolated stack Compose config 不再包含 Playwright image。
- `playwright-runner` profile 使用 `mcr.microsoft.com/playwright:v1.59.1-noble`,只在显式启用 profile 时出现。
- Playwright artifacts/storageState 默认按 `test-results/runs/<slug>/<date>` 和 `tests/storage/<slug>/<date>` 隔离。

已完成真实 lightweight frontend full stack smoke:

```bash
scripts/dev/start-isolated.sh --slug=node-smoke --wait --skip-pull
```

结果:

- backend `/actuator/health`: `200`
- frontend `/`: `302`,符合未登录时跳转语义
- BFF `/health`: `200`
- Postgres: accepting connections
- Redis: `PONG`
- 默认镜像列表中无 `mcr.microsoft.com/playwright`
- Docker VM: `8.3G` used / `28.7G` available
- 验证后已执行 `stop-isolated.sh --purge` 和 `cleanup-stack.sh --images --apply`,无 `auraboot-node-smoke` 容器、volume、env 文件或 backend image 残留。
- 后续 artifact isolation 验证通过:默认 compose config 无 Playwright image;runner profile 含 Playwright image 和 `PW_*` env;`test-dev-env-scripts.sh` 为 `19 passed,0 failed`;`test-multi-worktree-guard.sh` 为 `10 passed,0 failed`;Gradle guard 通过。

### 2026-05-12 case-login 验证

为验证实际开发路径,执行了一个最小 UI case:

```bash
scripts/dev/start-isolated.sh --slug=case-login --wait --skip-pull
```

结果:

- backend `/actuator/health`: `200`
- frontend `/`: `302`
- BFF `/health`: `200`
- 浏览器打开 `http://localhost:5207/login`,确认登录表单渲染,包含 email、password、remember、submit 控件。
- stack env 正确写出:
  - `PW_E2E_RUN_ROOT=test-results/runs/case-login/20260512T041417Z`
  - `PW_STORAGE_DIR=tests/storage/case-login/20260512T041417Z`

磁盘观察:

- 启动前 Docker VM: `8.0G` used / `29.1G` available
- full stack 启动后: `9.2G` used / `27.9G` available
- 单次增量主要来自:
  - `isolated_node_modules`: `723.8MB`
  - `isolated_web_admin_node_modules`: `21.24MB`
  - Postgres volume: `95.05MB`
  - backend image: `623MB`
- 清理后 Docker VM: `8.1G` used / `28.9G` available

Optional `playwright-runner` profile 尝试:

- 首次拉 `mcr.microsoft.com/playwright:v1.59.1-noble` 多分钟仍很慢,已中止。
- 这进一步证明 Playwright image 不应作为默认 frontend 成本;它应保留为显式 B2/CI-like runner。
- 中止 runner 后发现 `runner_node_modules` / `runner_web_admin_node_modules` 0B volumes 残留。已修复 `stop-isolated.sh` 和 `cleanup-stack.sh`,让 compose down 包含 `--profile playwright-runner`。清理后 stale candidates 为 `none`。

### 2026-05-12 artifact 清理补强

已新增 `scripts/dev/cleanup-artifacts.sh`,默认 dry-run,只清理新 env contract 生成的目录:

```text
web-admin/test-results/runs/<slug>/<run-id>
web-admin/tests/storage/<slug>/<run-id>
```

`doctor-disk.sh` 已增加 E2E Artifacts section,展示 artifact/storage root、top run 目录和 `cleanup-artifacts.sh --days=14` dry-run 摘要。

## 2026-05-12 follow-up completion

已完成 6 项后续收口:

1. Mode A infra-only 真实验证:
   - `start-dev-infra.sh --slug=mode-a-check --with-storage` 成功启动。
   - Postgres `pg_isready` 正常,Redis `PONG`,MinIO health `200`。
   - `r2-env-export.sh mode-a-check` 正确导出 slug-scoped 端口和 artifact 路径。
   - `stop-isolated.sh --slug=mode-a-check --purge` 后无容器、无 project volume、env 文件已移除。
2. B2 production-like:
   - 新增 `isolated-prod-frontend` service,profile 为 `production-like`。
   - 新增 `scripts/dev/start-production-like.sh`。
   - `start-isolated.sh` 写入 `PROD_FRONTEND_PORT`。
3. Playwright runner:
   - 新增 `scripts/dev/run-playwright-runner.sh`。
   - 默认拒绝未缓存 Playwright 镜像,必须显式 `--allow-pull`。
4. 清理策略:
   - `stop-isolated.sh` / `cleanup-stack.sh` 覆盖 `playwright-runner` 和 `production-like` profile。
   - `cleanup-artifacts.sh` 覆盖 slug/date-scoped E2E artifact/storage。
5. Maven 长期方案:
   - 新增 `docs/plans/2026-05/2026-05-12-maven-publish-isolation-options.md`。
   - 当前继续采用 per-worktree `maven.repo.local`;P1 评估 per-worktree file repo;P2 评估 composite build。
6. 验证:
   - `test-dev-env-scripts.sh`: 36 passed,0 failed。
   - compose default isolated config 不包含 Playwright 镜像。
   - `production-like` profile 单独展开 `isolated-prod-frontend`。
   - `playwright-runner` profile 单独展开 Playwright runner 和 Linux browser cache mount。

验证:

- 首次发现脚本缺 executable bit,已修复。
- 首次发现 macOS bash 无 `mapfile`,已改为 `while read` 收集候选。
- `test-dev-env-scripts.sh`: `28 passed,0 failed`。

验证中额外修复:

- 首次在空 host cache 环境运行时,`prepare_host_cache_dirs` 会因为父目录 `~/.cache/auraboot` 不存在失败。`start-isolated.sh` 现在先创建 cache root,再创建 `container-linux/{gradle,m2,pnpm-store,ms-playwright}`。

不建议过早放松:

- 不应仅因为“没有 active worktree”就允许写 canonical DB / Redis / `~/.m2`。
- active worktree detection 可用于诊断提示,不应作为唯一放行条件。
