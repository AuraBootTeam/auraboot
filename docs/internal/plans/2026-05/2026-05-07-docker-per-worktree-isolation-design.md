# Docker-per-Worktree 开发环境隔离设计 v2

> Status: Decisions taken
> Date: 2026-05-07
> v1 self-review found 2 fact errors + 4 logic gaps; v2 settles decisions per long-term-evolution + best-practice principle.
> Trigger: env-layering DB 污染事件(2026-05-07 09:25,共享 `aura_boot` 库被 `feat/env-layering-poc` worktree 重建,104 次 `POST /api/pages` 失败)

## 1. 问题

### 1.1 触发事件

- `feat/env-layering-poc` worktree 跑 `scripts/reset-db.sh`,默认 `DB_NAME=aura_boot`(host 共享库)
- 该脚本用 worktree 自己的 `schema.sql`(含 `env_id NOT NULL`)重建库
- host 上仍在运行的 backend 用旧 OSS core jar(`~/.m2/.../auraboot-core-1.0.0-SNAPSHOT.jar`,Apr 29 build,无 `envId` 字段)
- 三件事凑齐 → 所有 `POST /api/pages` 死于 `null value in column "env_id"`

### 1.2 根因(5 个共享单点)

| 共享单点 | 默认配置 | 多 worktree 时风险 |
|----------|----------|-------------------|
| Postgres `localhost:5432/aura_boot` | 所有 worktree 同一库 | DDL 互相覆盖(本次事件) |
| `~/.m2/repository/com/auraboot/auraboot-core` | 全局唯一 jar 缓存 | 任一 worktree publish 即覆盖 |
| Backend `:6443` | 单实例 bootRun | 一个 PID 不能同时跑多套代码 |
| Vite `:5173` / BFF `:3500` | 单实例 dev server | 端口冲突,多 worktree 抢占 |
| Redis `localhost:6379` | 单实例 | key 互相覆盖 |

### 1.3 半 fix 为什么不够

只改 `reset-db.sh` 默认 `DB_NAME` 派生 branch slug 只解 1/5 单点,剩 4 个未动。新 hazard 会从其他维度长出来。**鼓励 host 模式凑合是治标不治本**。

## 2. 现状盘点

### 2.1 已有底子(经验证)

- `auraboot/docker-compose.yml`(基础)+ `auraboot/docker-compose.ga-e2e.override.yml`(GA-E2E PR #29):postgres + backend + frontend(vite + BFF)三服务,host 端口 5433/6444/5174/3501
- `COMPOSE_PROJECT_NAME=auraboot-ga-e2e` 已在 override 文件头注释明确,docker compose project name 机制可派生 per-worktree namespace
- backend 用 `build: ./platform/Dockerfile` **在容器内 build**,m2 自动是容器自己的,host `~/.m2` 完全无关 → docker 模式 m2 隔离已自动满足
- frontend 用 bind-mount `./:/repo:cached` + 容器内 `pnpm install` + `pnpm dev:full`,与 host pnpm 无关
- `auraboot/docs/operations/ga-e2e-docker-stack.md`:5 个 first-boot trap 已文档化

### 2.2 缺什么

1. **Stack 名 + 端口未参数化**:GA-E2E stack 是固定单例(5433/6444/5174/3501),起两份会撞自己
2. **per-worktree stack 工作流缺失**:无 CLI 入口、无脚本起栈、无 slug 派生逻辑
3. **host 模式无 pre-flight 防护**:`reset-db.sh`/`publishToMavenLocal`/`bootRun` 不检测当前是否多 worktree,失误即污染
4. **enterprise 端 dev 路径不在 docker 内**:enterprise bootRun 在 host 跑,通过 `~/.m2` 消费 OSS core,**多 enterprise worktree 仍 m2 碰撞**

## 3. 决策

### 3.1 两模式定义(决策版)

| 模式 | 适用 | 实现 | 启动命令(目标) |
|------|------|------|--------------|
| **Host(默认)** | 单 worktree 专注开发 | 现状:host Postgres / m2 / bootRun :6443 / pnpm dev :5174 | `./gradlew :platform:bootRun` + `pnpm dev:full` |
| **Isolated(强制 ≥ 2 worktree)** | 多 worktree 并行 / agent 并发跑动主路径 | 每 worktree 一份 docker stack,完整自包含(postgres + backend + vite + BFF + redis 全容器内) | `aura dev start --isolated` |

红线:**两种模式不混用**。Isolated 模式下不允许任一 worktree 还连 host 的任何端口。

### 3.2 多 worktree 操作分级(host vs isolated 强制矩阵)

判断标准:**操作是否触动共享单点**?

| 操作 | 是否触动共享单点 | 1 worktree | ≥ 2 worktree 同时活跃 |
|------|----------------|-----------|---------------------|
| 编辑文件 / git commit / push | 否(worktree 文件系统隔离) | host OK | **host OK** |
| `gradle compileJava` / `tsc --noEmit` | 否(纯本地编译) | host OK | **host OK** |
| `gradle test --tests='*IntegrationTest*'`(带 UniqueIdGenerator 前缀) | 是(共享 Postgres),但数据级隔离已够 | host OK | **host OK** |
| `reset-db.sh` / `oss-reset-and-init.sh` | 是(DDL,共享单点) | host OK | **强制 isolated** |
| `publishToMavenLocal` | 是(共享 ~/.m2) | host OK | **强制 isolated** |
| `bootRun`(OSS 或 enterprise) | 是(:6443 单实例 + ~/.m2 jar 来源) | host OK | **强制 isolated** |
| `pnpm dev:full`(vite + BFF) | 是(:5174/:3501 单实例) | host OK | **强制 isolated** |
| Playwright `tests/e2e/showcase/`(完整) | 是(依赖 backend + vite + BFF + DB) | host OK | **强制 isolated** |

**≥ 2 worktree 时进入"强制 isolated"格的操作,host 模式 pre-flight 必须主动拒绝执行**(详见 §3.6)。

### 3.3 m2 命运分支(替代 v1 的错误 §3.3)

不再纠结"per-stack m2 volume":

- **Host 模式**:m2 共享是 OSS / enterprise 仓库拆分的代价,**只在 1 worktree 场景下安全**。多 worktree → 强制 isolated → 该问题在 docker 内自动消失
- **Docker 模式**:每个 stack 的 backend / enterprise 都在容器内 build,m2 是容器自己的,**与 host 完全无关**

不做"host 端 per-stack m2 volume" — 那是治标不治本,且会鼓励多 worktree 在 host 上凑合。

### 3.4 端口分配规则(决策版)

GA-E2E 改名为特例:`slug=ga-e2e` 占用 base offset 0(端口 5433/6444/5174/3501)。其他 worktree 的 slug 通过 hash 派生 offset(1-89):

```
PG_PORT     = 5433 + offset
BE_PORT     = 6444 + offset
VITE_PORT   = 5174 + offset
BFF_PORT    = 3501 + offset
REDIS_PORT  = 6479 + offset   # 6379 已占,从 6479 起
```

slug 派生(决策版):

1. `git rev-parse --abbrev-ref HEAD`
2. detached HEAD 时回退到 worktree path basename
3. 规范化:`tr '[:upper:]/_' '[:lower:]--'` + 截断 ≤ 24 字符
4. 用户可显式 `--slug=<name>` 覆盖

offset 派生(短期 hash,长期探测):
- 短期:`offset = (sha1(slug)[0:8] hex 解析为 int) % 89 + 1`,启动时检查 `docker compose ls --filter name=auraboot-${slug}` 不重名 + 端口可用
- 长期(可选 P2):启动时探测 5433-5532 第一个可用端口,记到 `.aura-stack/${slug}.env`,后续启停以此为锚

### 3.5 安全网:host 模式 pre-flight 检测(新增)

**`reset-db.sh` / `oss-reset-and-init.sh` / `publishToMavenLocal` 入口的 wrapper 必须检测**:

```bash
ACTIVE_WORKTREES=$(git worktree list | wc -l)
if [ "$ACTIVE_WORKTREES" -ge 2 ]; then
    echo "ERROR: detected $ACTIVE_WORKTREES active worktrees."
    echo "  Multi-worktree mode requires isolated docker stack."
    echo "  Run: aura dev start --isolated"
    echo "  Or:  set FORCE_HOST=1 to override (only if other worktrees are dormant)"
    [ "${FORCE_HOST:-}" != "1" ] && exit 1
fi
```

**FORCE_HOST=1 是 escape hatch**:开发者明确知道其他 worktree 没在跑这些操作时可绕过。每次绕过会记录到 `~/.aura/host-override.log`,owner 可定期审计哪些场景在依赖 escape hatch。

### 3.6 Agent 并行 dispatch 规则

主对话派发 subagent 时:

- **单 subagent 或纯 filesystem-only 多 subagent**:host 默认 OK
- **≥ 2 subagent 涉及 §3.2 强制 isolated 格的操作**:每个 subagent 在 prompt 里明示其 stack slug + 自动启 isolated stack;或者**分批串行**(每批 1 个跑 host,跑完下一个)
- **混合(filesystem 多 + 主路径 1)**:filesystem 多 host 跑,主路径那个独占 host

prompt 模板要求(后续 agent dispatch skill 更新):

```
- 你是 N 个并行 agent 中的第 X 个
- 你的 worktree:<path>
- 主路径操作(reset-db / publish / bootRun / E2E)只许在 isolated stack 跑
- 起 stack:<command>
- 你的 slug:<derived>
```

## 4. 交付分解

### 4.1 P0(unblock multi-worktree 主路径)

1. `auraboot/docker-compose.isolated.yml`:把 ga-e2e.override.yml 的固定端口改成 `${PG_PORT:-5433}`/`${BE_PORT:-6444}` 等环境变量
2. `auraboot/scripts/dev/start-isolated.sh`:派生 slug + offset、生成 `.aura-stack/${slug}.env`、`COMPOSE_PROJECT_NAME=auraboot-${slug} docker compose -f docker-compose.yml -f docker-compose.isolated.yml up -d`
3. `auraboot/scripts/dev/stop-isolated.sh <slug>`:对应清理(volume 默认保留 + `--purge` 删除)
4. `auraboot/scripts/dev/list-isolated.sh`:列出当前所有 `auraboot-*` stack(slug / 端口 / uptime)
5. `reset-db.sh` + `publishToMavenLocal` wrapper(或 gradle task)加 §3.5 pre-flight 检测

### 4.2 P1(降摩擦)

6. `aura dev start --isolated [--slug=<x>] [--offset=N]` CLI(`auraboot/plugins/cli/`)封装上面的 shell
7. `aura dev list-stacks` / `aura dev stop --slug=<x>` 对称命令
8. enterprise 端 docker compose override(把 enterprise platform bootRun 也封装进 stack;OSS 模块通过 stack 内多模块 build 消费,绕开 ~/.m2)
9. `docs/agent-rules/local-dev-quick-reference.md` 加 host vs isolated 决策树
10. agent dispatch skill (`superpowers:dispatching-parallel-agents`) prompt 模板更新,带 stack slug 派生

### 4.3 P2(完善)

11. 探测式端口分配(§3.4 长期方案)
12. pre-commit hook:worktree 不是 main 时若 schema.sql 有 diff,提示走 isolated
13. agent dispatch 自动检测 N≥2 + 主路径需求 → 自动并发起 isolated stack

### 4.4 不交付(明确排除)

- prod / staging / CI 环境改造(本设计仅限 dev workflow)
- Kubernetes / Tilt / Skaffold 大改造(GA-E2E 已有 docker-compose,不再加新工具链)
- host 端 m2 per-stack volume(决策 §3.3:这是治标不治本)
- enterprise 维持 host bootRun 的"杂交方案"(决策 §3.3:m2 仍碰撞)

## 5. 验证标准(具体场景)

**场景 A — 5 个 worktree 同时活跃,各跑 IT**:
```bash
for i in 1 2 3 4 5; do (cd worktree-$i && ./gradlew test --tests='*IntegrationTest*') & done
wait
```
**通过条件**:5 个都绿,共享 Postgres 数据无错乱(UniqueIdGenerator 前缀生效)。host 模式 OK(§3.2 IT 在 host 安全)。

**场景 B — 2 个 worktree 同时跑 reset-db.sh**:
```bash
cd worktree-1 && bash scripts/reset-db.sh   # 期望:成功(单 worktree)
# 启 worktree-2
cd worktree-2 && bash scripts/reset-db.sh   # 期望:被 §3.5 pre-flight 拒绝,提示 --isolated
```
**通过条件**:第 2 次主动拒绝 + 错误信息指向 isolated 用法。

**场景 C — 2 个 worktree 各起 isolated stack 跑 E2E**:
```bash
cd worktree-1 && aura dev start --isolated && aura test e2e tests/e2e/showcase/
cd worktree-2 && aura dev start --isolated && aura test e2e tests/e2e/showcase/
```
**通过条件**:两个 stack 端口不撞、容器名不撞、E2E 各自绿、关停后 docker volume 互不污染。

**场景 D — Agent 5 并行混合任务**(模拟本次会话):
- 4 个 filesystem-only(commit + tsc + compileJava)
- 1 个主路径(IT + 可能 publish)

**通过条件**:filesystem 4 个 host OK;主路径那 1 个独占 host(其他 4 个不撞)或自动起 isolated。

## 6. Open Questions(降到 3 个,owner 决策)

1. **escape hatch FORCE_HOST=1 是否要?** 我倾向是(开发者灵活性 > 强制),`~/.aura/host-override.log` 作 audit trail。反方:任何 escape hatch 长期都被滥用。
2. **enterprise 端 docker(P1 #8)优先级是否提到 P0?** enterprise bootRun 是当前 dev 主路径,m2 碰撞在 enterprise 端比 OSS 端更高频。提 P0 → 一开始就砍掉杂交模式;留 P1 → 短期 OSS 先 unblock,enterprise 后续。我倾向 P1,理由:这次 OSS 已先暴露,先解 OSS 是低风险增量,enterprise docker 改造工作量更大需独立 PR 链。
3. **CLI 命名:`aura dev start --isolated` vs `aura stack up`?** 前者强调"启动开发"动作,后者强调"操作 stack"对象。我倾向前者(更接近用户意图),反方:容易和现有 `aura dev` 同名命令撞。

## 7. 反方意见(steel-man)

1. **"host 默认 + pre-flight 还是会被人 force=1 绕过"** — 同意。但 escape hatch 比强制 docker 更尊重开发者判断;audit log 给 owner 看哪里被滥用,后续可针对性收紧。
2. **"docker 启动慢(分钟级首次拉镜像 / 容器启动 + pnpm install + gradle build),拖慢迭代"** — 仅在多 worktree 才付,且 first-boot 后 second-boot 秒级。单 worktree 仍走 host 不付这个税。
3. **"slug hash 5 worktree 撞概率 ~10%"** — 确实。短期 hash 是简单方案,启动时已 check 端口可用 + project name 不重名,撞了重派生 slug 就行。长期方案探测式分配根治。
4. **"enterprise docker 改造工作量大"** — 是。所以 P0 不做,P1 推。短期 enterprise dev 多 worktree 时 owner 自己注意 / 或等 P1。
5. **"为什么不用 Tilt / Skaffold / devcontainer?"** — 这些工具替换或包装现有 docker-compose,会引入新依赖 + 学习曲线。GA-E2E docker-compose 已经 work,我们只参数化 + 加 wrapper 即可。

## 8. 决策对应记录(给 v3 / 后续 PR 用)

| 决策点 | v1 | v2 | 理由 |
|--------|-----|-----|------|
| m2 隔离 | 短期 per-stack volume + 长期容器内 build 二选一 | docker 模式自动隔离,host 模式 1 worktree 才安全,**不做 per-stack volume** | 半 fix 鼓励凑合,不做就是逼用户走 docker |
| enterprise 范围 | 排除 | P1 同栈,短期内多 enterprise worktree 走 §3.5 pre-flight 拒绝 | 杂交模式 m2 仍碰撞 |
| default 模式 | host | host + pre-flight 强制(检测 ≥ 2 worktree 拒绝主路径) | 软约束已被本次事件证明不够 |
| Agent dispatch | host | filesystem-only 多 host;主路径多 → isolated 或串行 | 少加摩擦,多防风险 |
| 端口起点 | 5174 + N(N=0 撞 GA-E2E) | GA-E2E = slug=ga-e2e 特例 + 其他 hash 派生 | 自洽 |
| Slug | 派生 branch 名 | branch 名 → 规范化(lowercase、`/`→`-`、≤ 24)+ detached fallback | 实操可用 |

---

**owner review 关注点**(只剩 §6 三个):
- §6.1 escape hatch 是否要
- §6.2 enterprise docker 是否提到 P0
- §6.3 CLI 命名
