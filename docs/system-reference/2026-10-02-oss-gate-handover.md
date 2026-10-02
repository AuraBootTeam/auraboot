# OSS 全量 Playwright 门禁 — 交接文档(2026-10-02 晚)

接手前必读。本文档自包含:不需要前序会话上下文即可继续执行。
持久记忆已同步:`~/.zcode/cli/memories/projects/auraboot-eeec6835d628fe95/memory/oss-full-playwright-gate-20260928.md`(战役全账+坑账)。

## 1. 任务主线(owner 指令链收敛后)

跑全量 OSS Playwright 门禁并修复全部问题。口径:`PW_PROFILE=oss` 的 `--project=oss`(playwright.oss.config.ts,1042 tests,scope 由根 `oss-scope.json` 定义,58+ 文件带 rationale 排除)。
水位轨迹:393 → 176 → 118 → 51 → 41–57(历史"稳定带")。全部 16 个战役 PR 已合 main(main=2b11f1193)。

## 2. 接手时正在跑:v18b(第一件事=收它的结果)

- 后台任务:`exec_581a9d69-57ad-490e-ba1e-224e0c61a96d`,2026-10-02 20:30 起跑,预计 ~14 分钟。
- 日志:`/tmp/v18b-regular.log`;结束行=`REGULAR_EXIT=…` + passed/failed/did not run 汇总。
- 栈:隔离栈 6448/5248/6248,库 `auraboot_oss_v18`,16 插件已导入(250 命令注册),auth 21/21。
- 若任务已完成:直接读日志汇总;若被中断:用 §3 配方重启 regular 阶段即可(auth/storage 仍有效,无需重跑 reset)。

## 3. 隔离栈完整配方(默认栈被并发会话占用时的正规路径)

```bash
export BE_PORT=6448 VITE_PORT=5248 BFF_PORT=6248 PG_DB=auraboot_oss_v18
export AURA_RESET_ALLOW_TARGETS="auraboot_oss_v18,6448"
export PLAYWRIGHT_BASE_URL=http://localhost:5248
export BACKEND_URL=http://localhost:6448 BFF_URL=http://localhost:6248
export PW_SKIP_WEBSERVER=1 PW_PROFILE=oss
# reset(会拒绝 13-worktree 守卫,须 FORCE_HOST=1;audit 落 ~/.aura/host-override.log)
FORCE_HOST=1 bash scripts/oss-reset-and-init.sh   # 必须 exit 0,fail-fast
# ⚠️ reset 成功≠栈完整:确认 step 7.5 插件导入执行过;否则补:
BE_PORT=$BE_PORT bash scripts/import-plugins.sh --profile=e2e
# 探活四件:BE /actuator/health、vite /(200/302/304 都算活)、BFF /health、
#           psql -d $PG_DB -c "select count(*) from ab_command_definition"  # 须 >0(≈250)
# auth + regular:
cd web-admin && npx playwright test -c playwright.oss.config.ts --project=setup --project=auth --no-deps
npx playwright test -c playwright.oss.config.ts --project=oss --no-deps
```

env 契约五件套缺一即挂:`PLAYWRIGHT_BASE_URL`+`BACKEND_URL`(端口=BE_PORT)+`BE_PORT/VITE_PORT/BFF_PORT/PG_DB`+`PW_SKIP_WEBSERVER=1`+`PW_PROFILE=oss`(缺则 `Project "oss" not found`)。
**红线:默认栈 6443/5173/3500 属并发会话(tenant-registry),不碰、不杀、不 reset。**手工起的无所有权进程,下次 reset 前先杀 listener。

## 4. 2026-10-02 三个定谳(修正历史水位解读)

1. **v17 报废(538 假红)**:并发会话 16:58 占默认栈(5173 broken vite + 3500 BFF + 6443 BE 500);旧 wrapper 未检查 RESET_EXIT=1 照跑。栈死指纹=API 级测试过、UI 级全挂。
2. **`@jsep-plugin/object` 陈旧 node_modules**:worktree 落后 main 新依赖,vite 500 overlay;**v10–v16 的 41–57 稳定带部分量的是这个环境债**(v16 artifacts error-context 有同款 overlay)。已修:`pnpm install --frozen-lockfile`(worktree 根)。教训:**worktree pull main 后必跑 pnpm install**(reset 只在 node_modules 整个缺失时才装)。
3. **v18 报废(233 假红)**:第一次 reset 在 frontend wait abort,**step 7.5 插件导入未执行**→250 命令缺失→所有 DSL 种子 spec 连带红。已对活栈补导入(16 插件/250 命令,`ab_command_definition` 实证)。import-scripts 尾部 6 个 "missing" 是误报(directory-sync 不写 ab_plugin_import_history)。

因此:**v18b 是第一个环境完整(jsep 修复+插件齐全)的全量测量**,其数字可与 v15/v16 稳定核(32 文件交集,`/tmp/stable-core.txt`)做有意义的 diff。

## 5. v18b 之后的工作清单(按优先序)

1. **diff 定谳**:v18b 失败文件 ∩ stable-core → 真散单;jsep/插件损伤面(aurabot real 系、nl-modeling、showcase、admin smoke)预期大幅消退。v15/v16 日志在 `/tmp/v15-regular.log`、`/tmp/v16-regular.log`。
2. **真散单逐例**(方法论已定稿:全量红但隔离绿=run 内状态污染伪影;污染源疑似 rbac/cross-tenant/org 改 admin 角色无 afterAll 还原,静态初筛候选见 memory)。已知待诊:inspector-authoring bpm-panel status selectOption 超时(独立 per-case)、designers F4-E10/E11 负载敏感、saved-view 系 7 例无共享根因(VC-T01=422 post-action 拒绝已探明)。
3. **v4 迁移余量(~11 文件)**:配方=postFlatPageDoc/空 blocks+designerRootId+扁平断言+ensureBusinessSpace(loginViaUI 后必须走 BFF `/api/switch-space` 重铸 cookie)。inspector-authoring 需种子+断言+交互三改;widget-chart-types/widget-advanced-props 被 **dashboard kind v4 序列化缺口**阻塞(产品改动)。
4. **三产品跟进**(需独立应用侧协调):CRM/管线 re-vehicle(端点门 403)、workflow app 编入 gate 运行时([W] automation 6 文件回归)、LLM provider 门控 profile([L] 19 文件回归)。

## 6. 关键文件索引

- 战役报告:`docs/system-reference/2026-09-28-oss-full-playwright-gate-report.md`(本目录)
- scope:`oss-scope.json`(仓库根);oss 配置:`web-admin/playwright.oss.config.ts`
- helpers:`tests/helpers/environments.ts`(env 契约)、`tests/helpers/auth-fixtures.ts`(`ensureBusinessSpace`)、`tests/helpers/backend-auth.ts`(API 驱动 spec 的 business-space 登录)
- 历史水位与 PR 账:PR #2083 评论链;v10–v16 结果 JSON 见此前会话记录(memory)
- 本 worktree:detached at 2b11f1193(=main);campaign 分支 `fix/oss-full-gate-debt-20260928` 已全部合入 main

## 7. 执行纪律(本仓红线摘引)

- 全量红≠逐个真红:先隔离复跑再修,禁 skip 掩盖;产品缺陷修复必须带回归锁。
- 提交一律逐文件 add(canonical checkout 有并发会话,`add -A` 危险);长战役 worktree+早提交+早 push。
- 修 spec 前三读(binding/DSL/curl);测试数据勿嵌模型码;`rg -n` 不是 `rg -rn`。
