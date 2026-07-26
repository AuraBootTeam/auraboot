# AMOS MES/WMS 功能大图与验证结论（2026-07-26）

## 一句话结论

本次授权范围已经形成可复跑的真链路：后端命令不是 mock 绿，UI 不是只看渲染，#1501 也不再只有修复态截图。L1–L4 已闭环；约 46 个储备 FR 仍明确未开发、未测试、未被“全绿”口径吞掉。

## 三层大图

### 1. 产品功能层

已交付切片覆盖生产执行、库存、质量和身份四条主链：

- 既有 11 个命令层 FR 继续由真命令 API + DB round-trip 守护；
- FR-10 从真实入库余额开始验证 FEFO/FIFO，不再测试里补写 `available_qty`；
- FR-08 从“已过期”推进到“会在剩余生产窗口内到期”；
- FR-12 从首次 SN 谱系推进到唯一、幂等、重打、换标、失效、双人恢复；
- FR-14 从测试/返工闭环推进到可追溯的 As-built 换件；
- 5 个曾经点即 400 的工作台行动点保持行动驱动验证。

约 46 个未排期 FR 仍在 AMOS crosswalk 中逐条保留。它们没有实现，也没有被这份报告宣称为绿。

### 2. 系统架构层

```text
用户动作 / 扫码
      ↓
DSL page + command binding + inputFields
      ↓
16 段命令管道 + PF4J handler
      ↓
动态模型 / 真 Postgres
      ↓
consumption · genealogy · defect/rework · inventory
```

本轮补的不是孤立字段：

- FR-08 在绑定 handler 内读取 lot 和工单窗口，并把通过的 lot 带入谱系；
- FR-12 用 5 个命令和一组显式字段表达不可变身份事件链；
- FR-14 复用同一谱系模型表达原件→替换件，避免维修系统与 As-built 两张皮；
- runner 构建并 stage 精确 16 个 fresh jar，hash 可查，禁止旧 SNAPSHOT 冒充当前源码；
- focused import 显式列出 MES/WMS 依赖闭包。全 `pcba-agent` profile 里的 `pcba-warehouse` 储备悬空是独立已知缺陷，不被静默忽略，也不阻断本切片。

### 3. UX 与行动层

| 行动面 | 以前的证据缺口 | 现在的证据 |
|---|---|---|
| 工作台 5 个命令行动 | 页面能渲染，但按钮曾发空 payload→400 | 真输入、真点击、真状态/DB 结果 |
| FR-07 互锁拒绝 | 修复态列表保留，但不能证明 #1501 断言分辨 | 隔离 mutant/fixed；同一后端 400，mutant 整页崩、fixed 列表保留 |
| 普通列表 | 只做 render/sweep | 搜索前 N>1、输入唯一名称后收窄到 1；点详情后 URL 和实体精确匹配 |
| FR-22 与控制塔 | 仅文本检查容易漏布局 | action 断言 + screenshot/vision 分工 |

## 深层旅程

### FR-08 材料扫描

1. 建工单、BOM、lot 与余额；
2. 错物料拒绝；
3. 已过期拒绝；
4. 未过期但不覆盖工单剩余生产窗口，拒绝；
5. 覆盖窗口的正确 lot 允许；
6. DB 中 consumption、lot 与 genealogy 可回查。

### FR-12 身份

1. 首扫建立 active identity root；
2. 同一绑定重扫幂等；
3. 同一组件不能绑定到另一成品；
4. relabel 让旧标签成为终态，新标签沿同一 root 接续；
5. reprint 只记审计事件；
6. invalidate 后标签不能生产使用；
7. recover_identity 必须有证据、不同第二确认人、有效置信度；
8. 全链保留 predecessor。

### FR-14 测试维修

1. 测试失败产生 defect；
2. defect 创建返工；
3. retest 关联并关闭原失败；
4. replace_component 写原件、替换件、物料、lot、前驱；
5. 从 finished SN 可还原 As-built 更换史。

## 证据成色与边界

| 层 | 当前作用 | 不声称什么 |
|---|---|---|
| unit 全模块 245/245（本轮相关 28/28） | handler 分支、边界参数 | 不声称插件/DB 已接通 |
| backend 34/34 + FR10 13/13 + baseline 22/22 + deep 34/34 | 真命令、真 DB、跨模型链路 | 不声称 UI 好用 |
| browser action suites | 用户可完成点击/输入/跳转 | 不替代后端持久化回查 |
| #1501 mutant/fixed 2/2 + 2/2 | 断言能区分缺陷与修复 | 不扩大为所有前端错误处理都已形式化验证 |
| screenshots / vision | 布局、可读性、无整页崩 | 不独立裁决业务成功 |

## 最终结论

本轮关闭的是 handover 明列的 L1–L4，而不是擅自扩张到整个 60 FR 产品终局。交付结果具备源码、配置、unit、fresh-jar 真栈、浏览器行动、mutation 和自包含 HTML 证据；剩余储备继续由 owner 决定何时开工。

## 最终执行证据

- slot 64 从当前 feature 源码 fresh build：16 个 hybrid jar、24 个 focused packages、102 个 mfg commands；
- backend 34/34、FR-10 13/13、FR-08/12/14 baseline 22/22、deep 34/34；
- 浏览器报告分组：11 绿、0 黄、0 红、0 未测；
- HTML SHA-256：
  `5b66afb4f42dd07f2e7d52effcf1566308d339da9e929c061a1f78518bcddeb5`；
- runtime artifact manifest SHA-256：
  `cfc1b88b7f34eee5652faa85a92d635e8df13604fcabf35a24ab6952a4adb388`。

这里的“闭环”只允许解释为 owner 授权的 L1–L4 定向范围。新 FR-12/14 生命周期命令由真命令管道与 DB 反查裁决，尚未声明各命令的浏览器 UI 可达；canonical OSS full E2E、非 admin RBAC 与约 46 个 reserve FR 也不在本报告的通过口径中。完整 `did_not_run` 与可信度审计见 `README-test-quality-matrix.md`。
