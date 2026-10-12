# HANDOVER 2026-10-13 — OSS Playwright 门禁收官 + 后续任务

面向下一个执行会话/代理,自包含。战役总账与全部配方在
`~/.zcode/cli/memories/projects/auraboot-eeec6835d628fe95/memory/oss-full-playwright-gate-20260928.md`
(五轮完账),本文只列**终态、态势定标、剩余任务的入口与验收标准**。

## 1. 战役终态(2026-09-28 → 10-13)

- 散单红 393 → **确定性残留 0**(连续五次全量验收的失败集均为轮转家族,逐例单跑复绿):
  864/14 → 883/17 → 870/18 → 854/~20(fresh-DB),组成随机、总量稳定。
- 三项"外部跟进"全部落地为本会话资产:
  1. **workflow app 入 gate** = BPM 插件以 application-module 形态入宿主 JVM
     (`platform/plugins/bpm-plugin-1.0.0.jar` + 3 个 `ab_bpm_*` 迁移 +
     `MenuPermissionDriftRepairRunner` 同款 boot runner 生态);automation
     enable/deploy/run 与 N-START-PROCESS / N-TRIGGER-BPM-EVENT 全链路绿。
  2. **CRM re-vehicle** = `web-admin/tests/e2e/crm/pipeline-journey-golden.spec.ts`
     骑 `e2et_crm_opp` 载体;顺带修复 v4 列表壳丢 DSL toolbar `actions` 与
     e2et_crm_opp_form 缺 form-buttons 两个产品缺陷。
  3. **[L] 态势解锁** = 平台内建 stub provider
     (`aura.llm.stub-mode=${AGENT_LLM_STUB_MODE:false}`);gate env 加
     `AGENT_LLM_STUB_MODE=true` 后 i18n-admin 全家 27 绿,唯一 skip 为
     posture 门控(见 §3.3)。真实付费 LLM 生成按设计不进 gate。
- 15 个 v4 迁移文件:余量已清(workbench×2/layout-tabs/version-diff 已 v4;
  inspector/deep-ops 刻意 v3 且绿);**dashboard-kind v4 序列化已产品化**
  (widget 块按 kind 分支 verbatim 存储,W1-C1 29 绿)。

## 2. 态势定标(写死,改动需过全量验收)

- **gate env 五件套**:`BACKEND_URL/BE_PORT(6448)/BFF_PORT(6248)/PG_DB(auraboot_oss_v18)`
  + `AGENT_LLM_STUB_MODE=true`(BE 启动 env + 测试 env 双侧)。
- **单租户 posture 是硬约束**:`system.mode=multi` 会打断 setup 链
  (01-multi-role-users member 链接解析空)并打挂 cross-field ×6——已回退;
  需要多租户的测试(i18n :139)用 403 探针 posture 门控 skip,不要翻转全局。
- **运行时形态**:aura-bpm = 宿主平台 JVM 内的 application-module 插件
  (BpmApplicationModule → BpmModuleConfiguration 子上下文),非独立进程;
  独立的是仓库/app.yaml/web 包(web 包未组合进 OSS,`/bpmn-designer` 因此 skip)。
- **boot 自愈/守卫**:`MenuPermissionDriftRepairRunner`(@Order(6),按
  `ab_plugin_resource.import_snapshot` 只收紧不放松地修 permission_code 漂移)
  + `OrphanMenuCheckRunner`(@Order(5),孤儿 fail-fast)。

## 3. 剩余任务(按优先级)

### 3.1 bpm-plugin jar CI 化(需 CI 准入,构建基建)

- 现状:jar 为本地 artifact-mode 构建:
  `cd aura-bpm/plugin/backend && ./gradlew jar
   -PplatformPluginApiJar=<platform-plugin-api jar>
   -PplatformRuntimeJar=<AuraBoot boot jar>`
  → 产物落 `platform/plugins/bpm-plugin-1.0.0.jar`(RESTART BE 后生效)。
- 待做:一条流水线任务串"构建平台 boot jar → 以其为输入构建 bpm jar → 双产物
  版本化发布",保证 ABI 匹配可复现。**阻塞点:Jenkins/CI 侧准入**(与 hifi
  通道同类的外部依赖)。配方已固化在 aura-bpm 仓库 `plugin/backend/build.gradle`
  与 memory 五轮完账。

### 3.2 reset 插件导入失败 21 个(疑似并发会话回归,挂账对方)

- 现象:`oss-reset-and-init.sh` 的 Step 7.5 插件导入 21 个失败
  (`admin_webhook` 权限 FK 失败 → 25P02 事务中止级联;core-dashboard/
  platform-admin/org-management/showcase/page-manager 等)。
- 疑因:#2258(fail closed on unresolvable declared role permissions)与
  bootstrap 种子顺序的交互——种子先建菜单/角色、后建权限,现在 fail-closed。
- **不阻塞 gate**:gate 自己的 setup(03-import-test-fixtures /
  04-import-oss-plugins,OVERWRITE)在 fresh DB 上全过,所需插件自建。
- 处置建议:转给 #2258 作者/owner 修种子顺序或导入容错;验收 = reset 后
  "Import failed" 计数归零(或仅余与 gate 无关的插件)。

### 3.3 多租户 posture 的 i18n 租户隔离测试(可选增强)

- `i18n-admin-workflow.real.spec.ts:139` 现为 posture 门控 skip(403 探针)。
- 若要在 gate 常跑:需要独立的多租户栈(mode=multi + self_service)——
  **不要**翻转共享 gate 栈(见 §2 反例);建议独立 DB + 独立 BE 端口。

### 3.4 轮转层逐例加固(可选,收益递减)

- 常客(全部单跑绿):EAL-004、H1、E5-E03(已修双向 toggle)、TL-003、
  PAR-B-004/005、MEMBER-DETAIL-08、canvas-box-select happy、open-platform、
  UDW-047、saved-view 系列。方法 = 固化套路:单跑复跑 → 探针/readback →
  修复或挂账。每轮全量失败集组成随机,不必追零。

### 3.5 真实 LLM 凭证(可选增强)

- stub 已覆盖 gate 面;真实凭证到位后在 provider profile 注入,再评估
  生成级用例是否入 gate(属增强,非欠账)。

## 4. 验证入口

```bash
# 栈自举(BE 已含 AGENT_LLM_STUB_MODE=true;jar 在 platform/build/libs)
cd platform && SERVER_PORT=6448 \
  DATABASE_URL='jdbc:postgresql://localhost:5432/auraboot_oss_v18?charSet=UTF8' \
  AURA_SSRF_ALLOWED_PRIVATE_HOSTS=127.0.0.1,localhost AGENT_LLM_STUB_MODE=true \
  nohup $JDK21/bin/java -jar build/libs/AuraBoot-1.0.0-boot.jar &

# gate(先 setup/auth,后全量;BFF 6248 / vite 5248 需在位)
cd web-admin && npx playwright test -c playwright.oss.config.ts \
  --project=setup --project=auth --no-deps --workers=1
npx playwright test -c playwright.oss.config.ts --project=oss --no-deps --workers=2
# 或直接用守望者(SERIAL 18 文件 + 全量,已带 stub env):
bash .quiet-watch/watch-final.sh   # load<8 触发,结果落 .quiet-watch/watch.log
```

- DB 重置:`FORCE_HOST=1 AURA_RESET_ALLOW_TARGETS="@any" bash scripts/oss-reset-and-init.sh`
  (会杀占用端口;reset 后 gate setup 自建插件,reset 自身导入失败见 §3.2)。

## 5. 高频坑账(本轮新增,全量在 memory)

- fixture 页 key 带 `_list` 后缀时,`/p/` 路由会再拼后缀 → 菜单 path 写 `/p/<model>`。
- radix combobox 不能 `selectOption`,用 click + `getByRole('option')`。
- 页面 re-mount 竞态用 `await expect(() => ...).toPass()` 包裹。
- Playwright `response` 事件里 `r.text()` 是 Promise,不能直接链 `.slice`。
- `import-directory-sync` 用 `path`(绝对路径)+ `conflictStrategy`,不是 `directory`。
- 重启 BE 前先清 `e2e.ios-handover.%` 孤儿(旧版 spec 泄漏的存量,新代码已自清)。
- i18n seed 硬化后必须对 owning 插件重导入,否则权限绑定不落地(自愈 runner
  只能修到"最后一次声明",声明本身未导入时无能为力)。
