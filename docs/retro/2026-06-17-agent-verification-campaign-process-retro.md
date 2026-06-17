---
type: retro
status: active
created: 2026-06-17
---

# Agent 验证 campaign 过程复盘:为什么"这么多问题",大半其实是 phantom

> owner 问:"为什么会有这么多问题?是门禁质量不高?输入信息不够?还是提示词不好?"
> 本文诚实拆解整个会话过程,逐条弯路 + 根因归类(A 门禁 / B 输入 / C 提示词 / D 验证),
> 回答这三问,并把可复用经验固化。**核心结论先说**:本会话**结果**很强(5 组真模型实测全
> 正向、gate 体系实测健全),但**过程**反复"发现 gap → 验证后证伪 → 修正文档"。这"这么多问题"
> **大半是 phantom(被过度声称的 gap),不是真缺陷**。根因不是单一某项,而是一条**单一根模式**
> 反复发作,主要落在 **B(输入信息)+ C(提示词)+ D(验证纪律)**,**A(门禁)反而是次要的**。

## 0. 会话弧线(范围)

intelligence 真模型量化 → 平台能力大图 + 8 场景 + 12 gate 测试设计 → schema/skill 架构 finding
→ 全方面验证 campaign(batch1 NL建模 / batch2 gate负向 / batch3 ChatBI)→ handover → 本复盘。
产出:8+ PR merged、5 个真模型 live IT、3 份设计文档 + 1 finding + 1 handover,均在 main。

## 1. 逐条弯路 / 返工 / 翻车(不遗漏)

### W1 — 架构 finding 写了未测的 quantifier「自由生成 ~70% 弱」,被自己下一批证伪
- **发生**:写 dev-capability finding(#746)时,凭早先盘点 subagent 的定性印象,断言 NlModeling
  自由生成"~70% 弱、靠 validator 兜底"。下一批 `NlModelingLiveQualityIT` 真测:clean 5/5 + hard
  类型推断 9/9 + 0 非法 + 0 校验错——**直接证伪**。
- **代价**:整份 finding §1/§2.3/§3/§4/§5/§9 全文返工修正(#750)。
- **本可如何更早避免**:finding 里任何 quantifier(X%/强/弱/优于)落笔前先跑一个最小真测,
  没测的显式标「🟡 推断未验」而非当结论。
- **根因**:`D 验证纪律`(主因:把未测推断当结论)+ `B 输入信息`(信了盘点 subagent 的定性)。

### W2 — 能力大图 §D/§E 编入未验证的 gate gap 清单,batch2 发现大半已测
- **发生**:能力大图(#741)§D 12-gate 矩阵 + §E gap 清单照搬 gate-盘点 subagent 的"现状",
  列了一堆"E2E 缺"(plan_hash 篡改 / ACL / timeout / Context)。batch2 按 §15 拿真实测试树逐条核对:
  plan_hash 篡改有 `ApprovalGateP0FixIntegrationTest` P0c、ACL 有 `ToolAclIntegrationTest`(全面)、
  timeout 有 `...ConcurrencyTest`、fail-secure 有 P0FixIT——**只有 Context 门真缺测**。
- **代价**:§D/§E 返工修正;但好处是真补了 Context 门测试(`ToolContextPolicyTest` 10/0/0)。
- **本可如何更早避免**:把 subagent 的"gap/缺 E2E"声称写进权威设计文档**前**,先 grep 测试树证实,
  或标「🟡 盘点声称未验」。
- **根因**:`B 输入信息`(subagent 盘点过度声称)+ `D 验证纪律`(我编进文档未先验)+ `C 提示词`
  (我的 Explore prompt 问"现状/gap",诱导 subagent 声称缺口而没要求它 grep 测试树取证)。

### W3 — 盘点 subagent 反复过度声称"无测/弱/缺",每批都要先证伪
- **发生**:这是 W1/W2 的共同模式,贯穿全程。ChatBI 盘点说"无 e2e 仅前端单测",实际有
  `ChatBiLlmParserTest`/`ChatBIServiceLlmTest`;NlModeling 盘点说"弱";gate 盘点说一堆 E2E 缺。
  **每一批验证的第一步都变成"先证伪盘点声称"**。
- **代价**:三批各花若干轮先做 §15 核对(但这正是 campaign 的真价值——所以不全是浪费)。
- **本可如何更早避免**:见改进 1/2。
- **根因**:`B 输入信息`(subagent 报告对 gap-claim 不可靠)+ `C 提示词`(dispatch prompt 未强制
  "声称无测前必 grep 测试树 + 标 verified/inferred")。

### W4 — batch2:governance 没绿就 commit(没 gate 在退出码上)
- **发生**:batch2 用 `node check-docs-governance.mjs && git add && commit` 的 `&&` 链,但实际把
  governance 跑完**没看退出码**就 commit+push(经典「`cmd|tail && commit` 吞退出码」反模式,
  AGENTS.md 红线明列)。push 了一个 governance FAILED 的分支,只能重建 worktree 修复。
- **代价**:重建 worktree + 补 frontmatter commit + re-merge,约 3-4 轮。
- **本可如何更早避免**:commit **前** governance 必须显式 gate 在退出码绿(batch3 已纠正:
  `GOV=$?; if [ $GOV -ne 0 ]; then 停止; fi`)。
- **根因**:`A 门禁纪律`(红线存在但我没遵守——这是执行问题,不是门禁缺失)。

### W5 — /handover 模板缺 frontmatter → governance 报错(我自己 #752 的缺陷)
- **发生**:W4 暴露的 governance error 其实是我早先 #752 的 handover 文档**缺 `type/status` frontmatter**
  ——`/handover` skill 模板生成的文档不含 frontmatter,但 docs-governance 门禁要求。
- **代价**:并入 W4 一起修(补 frontmatter)。
- **本可如何更早避免**:`/handover` skill 模板本身应带 frontmatter。
- **根因**:`A 门禁质量`(skill 模板与 docs-governance 要求不一致 → 系统性产出不合规文档)。

### W6 — 每轮 live IT 真 key 泄进 MyBatis DEBUG SQL 日志,手动 redact
- **发生**:每个 live IT seed `ab_cloud_config`(apiKey 在 INSERT),integration-test profile SQL DEBUG
  把参数明文记进 HTML 报告。每轮跑完手动 `sed` redact 四处。
- **代价**:每轮多一步 + 有遗漏风险(但每轮都复核残留=0)。
- **本可如何更早避免**:CloudConfig 日志层对 apiKey/secret 脱敏,从源头消除。
- **根因**:`A 门禁/基建`(日志脱敏缺失)。

### W7 — 早期把 enterprise worktree 误建在 OSS 仓(忘 cd)
- **发生**:第一次建双仓 worktree 时,第二条 `git worktree add` 没 cd 到 enterprise,建成了 OSS 的。
- **代价**:立即发现并删除重建,<1 轮,低。
- **根因**:minor(cd 纪律);已即时自纠。

### W8 — gh pr merge 反复 graphql EOF(外部瞬时,非缺陷)
- **发生**:多次 `Post graphql: EOF`,有时 merge 已成、有时(#757)需 re-merge。
- **处理**:每次 merge 后核对 `state` + origin/main 真有 commit;EOF 即 re-merge。
- **根因**:外部网络瞬时,非我的过程缺陷。记录是为了让接手者别被 EOF 吓到——核对 state 即可。

### 无弯路的部分(不为凑数编问题)
真模型 live IT 模板一次建好后复制顺滑;env 访客模式(共享 aura_boot 非破坏性 seed+cleanup)全程稳定;
worktree 收口零残留;每个真测一次过(无产品 bug,Context 门也是对的)。

## 2. 根因归类小结 + 直接回答 owner 三问

**单一根模式(贯穿 W1/W2/W3)**:**未验证的 subagent 盘点声称 → 被当 finding 写进权威文档 →
后续验证再纠正**。其余(W4/W5/W6)是边缘的执行/基建问题。

直接回答「是门禁质量?输入信息?还是提示词?」——**是 B+C+D 三者叠加,A 反而最轻**:

| 问 | 判定 | 依据 |
|---|---|---|
| **B 输入信息不够充分?** | **是,主因之一** | 盘点 subagent 的"gap/弱/无测"声称不可靠(W1/W2/W3 反复证伪);我拿它当输入直接编文档 |
| **C 提示词不好?** | **是,主因之一(但不是 AGENTS.md 红线缺失)** | 我给 Explore 的 dispatch prompt 问"现状/gap/成熟度",**诱导 subagent 声称缺口**,且**没强制它声称"无测"前 grep 测试树取证 + 标 verified/inferred**。AGENTS.md §15/§16 红线本身是有的、清楚的——是**我没把 §15 用在"盘点声称"上**(只惯性用在 spike) |
| **D 验证纪律** | **是,最直接的那一刀** | 主对话把未验声称编进权威文档(finding/设计大图),没先验证/没标推断。§15「继承结论必须重新实测」**同样适用于盘点声称**,我应用得太晚(逐批纠正而非落笔前) |
| **A 门禁质量不高?** | **次要** | 门禁大多 WORKED:docs-governance 抓出 frontmatter 缺失、memory size guard 抓出超限、测试树存在可供 §15 核对。少数真 A 项(handover 模板缺 frontmatter / CloudConfig 日志不脱敏 / commit 没 gate 退出码)真但边缘 |

**一句话**:"这么多问题"≠ 平台/我有这么多缺陷,而是**我把未取证的盘点当结论写进了权威文档,
制造了大量 phantom gap,验证再逐个戳破**。改对一处(落笔前验证盘点声称)能消掉 W1/W2/W3 一整类。

## 3. 应该有哪些改进(具体、可执行)

1. **(C)dispatch 盘点/Explore subagent 的 prompt 必含**:"声称'无测/缺/弱'前必须 grep 实际测试树
   + 对每条 gap 标 verified(读了测试)/ inferred(只看了文件名/架构);quantifier(X%/强弱)必须附实测或标推断。"
2. **(D)主对话纪律**:subagent 的 gap/weak/quantifier 声称,**编进任何权威文档(finding/设计/roadmap)前**
   先验证或标「🟡 盘点声称未验」。§15「继承结论必须重新实测」**显式扩到"盘点声称"**,不只 spike API shape。
3. **(A)`/handover` skill 模板加 frontmatter**(type: handover / status: active / created),从源头不再产出
   docs-governance 不合规的 handover。
4. **(A 纪律)commit 前 governance / check-*.sh 必须 gate 在退出码绿**:`X=$?; [ $X -ne 0 ] && stop`,
   禁 `cmd && git commit` 不看退出码(红线已有,本会话 batch3 已纠正示范)。
5. **(A 基建)CloudConfig 日志脱敏**:`ab_cloud_config` 写入/读取的 SQL 参数日志对 apiKey/secret 字段
   脱敏,消除真 key 泄 DEBUG 日志的复发(与"加密存储"gap 相邻)。

## 4. 已固化 / 待固化(执行 Step 5)

- [x] 本 retro 文档(过程归因 + 改进 + 答 owner 三问)。
- [x] 固化改进 1+2 到 canonical §15(**enterprise PR #529 MERGED**):
      `spike-verification-discipline.md` 加"盘点 / Explore subagent 的 gap/weak/missing/X% 声称 = 继承结论,
      编进权威文档前必 grep 测试树验证或标🟡未验 + dispatch prompt 必含'声称无测前先 grep 测试树 + 标 verified/inferred'"
      + enterprise AGENTS.md §15 关键字表加一行指针。
- [ ] 待 owner 决策固化:`/handover` skill 模板加 frontmatter(改 `.claude/skills/handover/` skill 文件,
      high-impact,留 owner 确认)。
- [ ] 待 owner 决策固化:`engineering-gotchas/test-infra.md` 加"真 key live IT seed ab_cloud_config 泄
      apiKey 进 MyBatis DEBUG 日志 → 跑后 redact / 根治 CloudConfig 日志脱敏"。
- [ ] 待 owner 决策:把 T3 live-eval 模板(native tool-use/自包含 schema/真 DeepSeek/诚实报告)升 canonical 测试方法。

## 5. 给下一会话的一句话

验证 campaign 最大的收获不是"找到很多 bug",是"证明很多'gap/弱'是没取证的过度声称"。
**继续往下做(业务金标 / workbench golden / dashboard build)时,先验证再相信——尤其别把盘点
subagent 的结论当事实写进文档。**
