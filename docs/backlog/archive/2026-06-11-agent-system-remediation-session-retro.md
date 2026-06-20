---
type: retro
status: closed
created: 2026-06-11
relates_to:
  - docs/backlog/2026-06-10-agent-system-review-and-remediation.md
---

<!-- no-precipitation: agent-spike-verification lessons already codified in AGENTS.md §15 keyword table + enterprise spike-verification-discipline.md (cross-repo); record stays for grep -->

# Agent 系统 review+修复 会话复盘(2026-06-10 ~ 06-11)

会话产出:OSS #537 / #548 + ENT #381(全部 MERGED)。本文是对**过程**的复盘:哪里出了问题、为什么、
哪些是门禁问题 / 输入问题 / 提示词问题,以及固化去向。结论先行:

> **本会话最大的问题类别不是幻觉,而是「陈旧事实」(stale truth)。** 7 处被证伪的"问题"里 6 处
> 来自过期文档(75 天未更新)和未标记修复状态的历史 review 报告;真正的编造类错误极少。
> 解药是时效性机制(implementation-map、finding 状态生命周期、引用前重验证),不是更严的幻觉防控。

---

## 一、问题清单(全量,不遗漏)

### P1. Review 阶段:4 个并行 Explore subagent 报告含 7 处错误结论(已逐条证伪)

| 报告结论 | 实测真相 | 错误来源 |
|---------|---------|---------|
| `extractMemoriesViaLlm` 不校验 memory_type | 白名单已存在(代码注释自标 "See deep-review P1-1") | 引用 2026-05-28 deep-review 报告,**该 finding 已修但报告未标记** |
| `sessionHistory` 无界 ConcurrentHashMap 泄漏 | Caffeine maximumSize(1000)+2h TTL | 同上(旧报告快照) |
| OrphanScanner poison-pill 整 tick 回滚 | 逐行 try/catch + continue | 同上 |
| 幻觉计数器仅 JVM 内存 | 已持久化 `ab_agent_run.hallucination_count` | subagent 读了类头注释没读到 1141 行的 SQL |
| 协作协议 DELEGATE/BROADCAST/PIPELINE 是"纸面协议" | `AgentCollaborationService` 450 行全实现,只缺**事件**(轮询完成) | 以"事件类 grep 0 命中"过度外推到"协议未实现" |
| `StreamErrorClassifier` 未被使用 | `BroadcastResponseSink:171` 在用 | grep 范围漏 |
| 能力同步内联 `PluginImportServiceImpl`(B1 立项依据) | 早已抽出:`CapabilityViewService.syncCapabilities` + 事件触发 `CapabilitySyncListener` | **affordance.md 文档自己就是错的**(75 天未更新),subagent 转述文档 |

**没造成实际损失的原因**:主对话严格执行 §15「继承结论修复前 live 重诊」,修复每一项前都先 grep/读码,
B1 在动手前被撤销,4 个"P1 bug"一个都没白修。**§15 是本会话 ROI 最高的纪律。**

### P2. R1 阶段:ChatBI 差点重复造轮子

- A1 原方案是"给 legacy ChatBI 加 LLM"。动手前找测试文件时**偶然**发现 `chatbi/v2` 完整存在
  (lexer + TokenCompiler + LlmProviderRouter 三级降级 + 消歧 + 成本审计)。差点在平台里造第二套。
- 最终判断:v2 是语义层会话式、legacy 是模型直查无状态,**两层不重复**,A1 改为按 AiSearch 同款模式
  补 LLM,并在代码/文档里显式写清两者关系。
- 教训:**"给 X 补能力"类任务的第一动作是全仓盘点同域实现**(找 v2/next/同名包),
  §16「阶段0 盘自家底」不只适用对外对标,内部增强同样适用。这次靠运气不靠流程。

### P3. R2 阶段:批量脚本编辑事故链(本会话唯一自伤)

1. 第一次批量 Python 注解脚本把 3 个 controller **截断**(-1061 行),根因未完全定位
   (嫌疑 splitlines/写流未刷),但 24 个注解在另一文件成功 → 同脚本不同文件行为不一致;
2. **更大的错误**:第二次重跑前忘了 `git checkout` 还原,在脏(已截断)文件上跑出 `count=0`
   的"鬼数据",随后基于鬼数据做了两轮无效 forensics;
3. 第三次改为「每文件唯一匹配断言 + 显式 close + 改后 `git diff --stat` 核对纯插入」才稳定通过。

教训(可操作):**批量脚本编辑三件套**——逐文件断言匹配数==1;失败立即 revert 到干净态再重试
(永不在脏状态上迭代);提交前 `git diff --stat` 核对插入/删除行数符合预期(纯注解=纯插入)。

### P4. R2 阶段:权限拦截器集成测试三连坑(诊断走了一段弯路)

现象:新注解端点在 IT 里返 **500**(不是预期的 403)。诊断路径:
- ❌ 先猜"权限缓存污染"→ 加 `evictUserPermissions` → 无效(猜测浪费一轮);
- ✅ 停止猜测,`gradlew -i` 抓服务端日志 → 一击命中 `User not authenticated`:
  `PermissionInterceptor` 从 **SecurityContext 的 CustomUserDetails** 取用户,而该测试的
  MockMvc filter 只设了 MetaContext。
三个独立知识点(都会再次咬人):
1. `@RequirePermission` 端点的 MockMvc IT 必须放 `CustomUserDetails` principal 进 SecurityContext
   (仿 `P1VirtualModelSmokeTest` / `AdminGuardTestSupport` 模式),MetaContext 不够;
2. 测试用户的授码走 `grantPermissionToTestRole` + **`evictUserPermissions`**(共享 Spring 上下文里
   其他测试类可能已 prime 缓存);
3. `webAppContextSetup` 的 MockMvc 无 security filter chain,deny 抛 `AccessDeniedException`
   显示为 **500**(GlobalExceptionHandler 的 "Unexpected system exception"),不是生产的 403——
   看到 500 别按业务 bug 查。

这再次验证 `feedback_diagnosis_discipline`(instrument first, patch second):猜了一次没中就应立即转抓证据。

### P5. R2 阶段:并发会话撞车(billing 权限码)

- 我在 B3 顺手注册了 main 上 billing 项目漏注册的 3 个码(让 gate 归零);**同一时段**并发会话在
  #547 里做了同样的修复 → 我的 PR `CONFLICTING`,rebase 解冲突 + 复验后才能 merge。
- 教训:**drive-by 修复"别的活跃项目"的缺口前,先做并发检测**(`git log origin/main` 近期是否有人
  在修同一处 / 该项目是否 active)。§18 的并发会话检测当时只查了自己 feature 的分支名,没查
  顺手修的域。损耗小(一次 rebase),但模式值得记。
- 次级教训:`gh pr merge` 失败输出含混("add --auto flag"),应紧跟 `--json mergeable,mergeStateStatus`
  实查,本次多等了一轮才发现 CONFLICTING。

### P6. 杂项(小但记全)

- Mockito 坑:`ApplicationEventPublisher.publishEvent` 有 `Object`/`ApplicationEvent` 两个重载,
  `ArgumentCaptor<Object>` 匹配不到实际调用的 `ApplicationEvent` 重载 → "Wanted but not invoked
  却有 1 次交互"的迷惑报错;captor 类型必须对准重载。
- `AuraEvent` 基类 `Map.copyOf(payload)` 拒绝 null 值 → 事件 payload 可空字段必须**省略 key** 而非放 null。
- Gradle 多模块 `--tests` 过滤会在无匹配子项目上报 "No tests found" → 需逐个 `-x` 排除 6 个子项目
  (重复样板,见改进 I-5)。
- test-results XML 是上轮残留+本轮混合,且我用的正则跨 testcase 贪婪匹配把"名字"和"失败体"错位配对
  → 一度把 PASSED 用例当 FAILED 分析。authoritative 是本轮 stdout,XML 仅作补充且解析要按 testcase 块切。
- zsh 里 `echo ===` 触发 `=word` 展开报错;`cat -A` 在 macOS 不存在 —— 平台细节。

---

## 二、根因分析:为什么会有这么多问题?(回答三个候选假设)

### 假设 1:门禁质量不高?——**部分成立,但不是主因**

工作正常的门禁:`validate-permission-codes`(还替我抓出 billing drift)、boundary、reset-init、
docs-governance。真正缺的是两类**没有门禁覆盖的面**:
- **文档↔代码一致性无门禁**:meta 契约文档 75 天漂移(双向:说"规划中"的已实现、说已实现机制的是轮询)
  没有任何机制报警 → 这是 P1 七处错误里至少 3 处的直接来源。已落的人工解药:`acp-implementation-map.md`
  (契约→实现类→表,改归属必须同步);可升级为脚本门禁(map 里的类名 grep 必须命中,见改进 I-1)。
- **review/finding 类文档无生命周期**:2026-05-28 deep-review 的 P1 修复后报告原文不变,后来者
  (包括 subagent)把快照当现状。finding 需要 status(open/fixed/withdrawn)+修复回链(见改进 I-2)。

### 假设 2:输入信息不够充分?——**不成立;准确说法是"输入信息陈旧"**

信息量是足够的(37 份契约文档、历史 review、362 文件代码全可读)。问题是**新鲜度无标记**:
文档没有"最后核对日期",报告没有"finding 状态"。subagent(和人)天然把读到的当现状。
所以解法不是"给更多输入",而是给输入加时效标记 + 强制引用前重验证(§15 已覆盖主对话侧,
缺的是 dispatch prompt 侧,见假设 3)。

### 假设 3:提示词不好?——**成立一半,且可精确修复**

- 我给 4 个 review subagent 的 prompt 写了「实测附 file:line / 推断标 🟡」——这有效(不少结论
  确实标了🟡,帮我分流了验证优先级),但**不够**:没有引用 §14 reviewer 的 verify-before-flag
  五字段 verify_protocol(exists_check/source_trace/count_grep/exemption_check/semantic_check),
  也没有显式要求「引用任何历史报告/文档结论前先 grep 当前代码确认仍成立」。结果:有 🟡 标记纪律
  但没有 falsification 纪律。
- **修复**:review/审计类 dispatch 的 prompt 模板必须包含两行:① P0/P1 结论按 §14 verify_protocol
  取证;② 历史 review/backlog/契约文档里的 finding 一律视为 hypothesis,引用前 grep 现行代码验证
  并注明验证命令。(已固化,见 §四)
- 实现类 dispatch(B2 拆分 subagent)的 prompt 质量是好的(worktree 三件套+验证命令+禁顺手改进
  +回吐 oid),subagent 零事故、自查出循环依赖并合理偏离 spec——证明 dispatch 纪律有效时效果很好。

### 没被列出但真实存在的第 4 因:**批处理自动化的自我纪律**(P3)

截断事故与脏状态重跑是纯执行层错误,与门禁/输入/提示词无关。这类错误的护栏是机械的:
断言、revert-before-retry、diff-stat 核对。

### 总评

> 问题数量多的主导原因排序:**① 输入陈旧无时效标记(文档漂移+报告无状态)≈ 50%;
> ② dispatch prompt 缺 falsification 要求 ≈ 25%;③ 执行层自我纪律(批量编辑/诊断先猜)≈ 20%;
> ④ 门禁缺口(文档一致性无门禁)是 ① 的机制化解法缺位,算同一根因的另一面。**
> 值得强调:**所有错误都在造成实际损失前被既有红线(§14/§15/§20 verify-don't-trust)拦截**,
> 体系的"纵深防御"起了作用;改进目标是把拦截位置前移(从主对话验证前移到 subagent 产出时)。

---

## 三、改进清单

| # | 改进 | 类型 | 状态 |
|---|------|------|------|
| I-1 | `acp-implementation-map.md` 升级为脚本门禁:map 中的类名/方法名 grep 现行代码必须命中,push 前跑 | 门禁 | ✅ 已做(ENT #404,`scripts/check-acp-implementation-map.mjs` + 单测;首跑即抓真漂移:map 写 `ab_agent_step` 实为 `ab_agent_action`,已修)。跨仓本地门禁,OSS checkout 缺失时跳过 |
| I-2 | review/deep-review/backlog finding 文档加 status 生命周期(open/fixed/withdrawn + 修复 PR 回链);修 finding 的 PR 必须回标原报告 | 流程 | 规则已固化(见 §四);存量报告不回溯 |
| I-3 | review/审计类 dispatch prompt 模板:必含 §14 verify_protocol + 「历史结论一律 hypothesis,引用前 grep 现行代码」 | 提示词 | 已固化(见 §四) |
| I-4 | 批量脚本编辑三件套(断言/revert-before-retry/diff-stat 核对) | 执行纪律 | 已固化(见 §四) |
| I-5 | gradle 子项目排除样板:考虑加 `scripts/test-platform.sh` 包装 `--tests` 过滤,免去 6 个 `-x` | DX | 可选,低优 |
| I-6 | `@RequirePermission` IT 三件套 + MockMvc deny=500 知识 | gotcha | 已固化(见 §四) |
| I-7 | drive-by 修其他活跃项目缺口前先并发检测 | 流程 | 已固化(见 §四) |
| I-8 | "给 X 补能力"先全仓盘同域实现(内部版阶段0) | 流程 | 已固化(见 §四) |

## 四、固化去向(本次随 ENT PR 落地)

1. `docs/agent-rules/spike-verification-discipline.md` §「继承结论」增补:**历史 review 报告/契约文档
   的 finding 是快照非现状**,引用前 grep 现行代码验证;review 类 dispatch prompt 必含 §14
   verify_protocol + hypothesis 措辞要求(I-2/I-3)。
2. `docs/agent-rules/engineering-gotchas/backend-spring-db.md` 新增:`@RequirePermission` 集成测试
   三件套 + MockMvc deny=500 + 新权限码必须注册 bootstrap(tenant_admin 通配所以存量 admin 不破)(I-6)。
3. `docs/agent-rules/engineering-gotchas/main-conversation-discipline.md` 新增:批量脚本编辑三件套
   (I-4)+ drive-by 修复并发检测(I-7)+ 内部能力增强先盘自家底(I-8)。
4. enterprise `AGENTS.md` 红线关键字速查表加对应关键词行(不加正文,守体积纪律)。

## 五、本会话做对的(保持)

- §15 重验证零例外执行 → 7 处证伪、0 次白修;
- B2 拆分 subagent dispatch 纪律完整(worktree 三件套/验证命令/禁顺手改/oid 回吐)→ 零事故,
  且 subagent 自查出循环依赖并给出合理替代(`CapabilityMappingSupport`),偏离 spec 有说明;
- 测试先行/同 PR 交付:r1+r2 共 33 个新单测 + IT 全部独立复跑,不信 subagent 汇报;
- 撞上并发 merge 冲突后,按 live 状态 rebase + 全量复验(gate+compile+IT)再 merge,无侥幸;
- 安全 IT 编码的既有契约(AdminRoleInterceptor 管 /api/admin/** GET)被尊重——把 profile.admin
  从类级收窄到 /forget,而不是改测试迁就新注解。
