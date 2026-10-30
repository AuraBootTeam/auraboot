---
type: system-reference
status: active
created: 2026-10-26
updated: 2026-10-26
---

# 催办预览就绪与 BPM 停止修复证据

裁决为本次两项缺陷的指定回归通过，不代表全产品/全部状态黄金。完整场景分母为 10：8 个视觉场景、2 个非视觉生命周期场景；8 张原图逐张经 agent-vision 查看，SHA-256、角色、viewport、来源及九维观察见 [matrix](./acceptance-matrix.json)。非视觉停止不以离线错误页截图代替进程/端口证明。

## 根因与修复

旧 R-UI-01 图抓取时仅等待统计表，说明和催办信息的懒加载未完成，空占位被误判为版式留白。稳定后相邻间距实际为 16px。本次增加真实内容、可提交状态、1/1/0 人数及不超过 24px 的间距断言，覆盖 390×844 内层纵向和横向滚动。没有改业务 DSL/样式。旧原图保留字节并移至 [capture-error](../postmerge-2026-10-26/capture-error/reminders-preview.png)，不再算就绪态成功；历史 32 张已查看中有效验收图更正为 31。

BPM 原停止器总预算 30 秒，小于一次完整 Spring 优雅停机阶段与随后清理。原包业务后实际复现超时；新包业务后从后端开始 graceful shutdown 到记录完全停止为 36.635 秒，首次公开 suspend 成功。新等待预算有界 60 秒，仍先验证全部目标的 PID generation、cwd、命令和 token，再复核发 SIGTERM；没有按端口杀进程或自动 SIGKILL。超时明确报告期限并保留现场。31.5 秒真实子进程用例在旧代码红、修复后绿，原进程边界 20 项均通过。

## 准确输入与执行

Core catalog producer `2214c7294b2e84083f82483b8657be82282cfe7f`，Core browser driver `902a5b943d373300a5e8269c250e74f02625234a`，后者仅增加 9 行滚动截图动作；生产源码相同，见 [equivalence](./source-equivalence.json)。BPM producer `a61fd24781160b6bb0bf686156f06bfab541af83`，正式消费上述 catalog，不冒称消费了另一个新 catalog。Core lock `sha256:d8a1c04fd8a571b777ea04b170c88719d8fe796edd41c2b5390a74e8d3fd1df6`，BPM lock `sha256:efb97764daaa9011464d5975e859ee5b164b05c76ff83f663d3e0ac84621af81`；[新组合 receipt](./bpm-release-receipt.json) 与 [实际 Java 身份](./actual-artifact-identity.json)独立核验。

| 执行面 | 本轮结果与边界 |
| --- | --- |
| 脚本契约/真实进程 | 98 通过，含真实进程边界 20；不把所有脚本称为 hermetic unit |
| 后端 | 1657 通过，224 JUnit，fail/error/skip=0；[summary](./bpm-junit-summary.json)，单元与真实 PG/engine/worker IT 混合 |
| 正式构件 | Core 15 件/18 entries、BPM 22 件；typecheck/client/SSR/checksum/graph/single React 通过；OCI 跳过 |
| 迁移 | 原 aura_bpm_1 备份后空库顺序执行 161 条 Flyway；Core 使用 canonical schema snapshot，不算逐条 Flyway |
| Core browser | 固定 17 collected/executed/passed，fail/skip/retry=0；[reconciliation](./core-reconciliation.json)；认证 1 另计 |
| 缺失组件反例 | 实际阻断懒加载的催办信息模块，1 条用例在 record-inspector/催办信息就绪断言按预期失败，无全局错误/重试；[proof](./capture-fault-proof.json) |
| 反例恢复 | 资源恢复后同一 native preview 用例 1 通过，覆盖取消零批次、丢失响应重试/重载仅一批次和滚动截图；与 Core17 重叠不相加 |
| BPM browser | 固定 4/4，fail/skip/retry=0；认证 1 另计；[reconciliation](./bpm-reconciliation.json) |
| 公开生命周期 | 业务后的 [stop](./after-native-workload-stop-proof.json) 成功；随后 [round1](./first-stop-proof.json) / [round2](./second-stop-proof.json) 均首次 resume/verify/retained-facts/suspend 为 0，端口 6501/5401 释放，新的受管代次有 stoppedAt；[数量事实](./retained-facts.json) 为 3 条公告/4 个版本，未在两轮之间 reset |

原外层 BPM 验收脚本在 native4 通过后，持久事实探针遗漏 runtime env，继而缺少第一轮 proof 文件，最终记录仍是 business_exit=1 / stop_exit=1；其公开 suspend 本身已成功。该轮不改标为整体通过，最终两轮干净生命周期是独立证据。Core 首次未启用通知桥接的 422 和外部反例配置的 CJS/ESM/相对 teardown 加载错误不计产品失败或有效反例；有效反例和恢复结果分别记录。原 Core2214 的早轮 17 也不累计为本轮 17 之外的额外功能。

功能：指定原生旅程与生命周期通过。视觉：8 个当前捕获场景通过，旧过早图排除。交互：确认打开/取消、丢失响应恢复、窄屏实际滚动和审批行动由原生断言核验。证据信任：源/构件/Java 路径/租户库/正常公开入口绑定，最终未使用诊断 load hook。剩余 BPM RUNTIME_MANIFEST_MISSING warning 仍在，产品独立 verify 已通过，不声称零 warning。

## 场景与原图

| 场景 | 原图/证明 | 功能 | 视觉 | 观察与限度 |
| --- | --- | --- | --- | --- |
| reminders:preview | [reminders-preview.png](./reminders-preview.png) | pass for the registered native reminder journey | accepted | 冻结公告、说明、知悉截止与范围、可提交状态及 1/1/0 资格事实全部入镜；分组间距正常，原 240px 空加载占位已不再被误当作就绪态。 |
| reminders:preview-narrow | [reminders-preview-narrow.png](./reminders-preview-narrow.png) | pass for the registered native reminder journey | accepted | 390×844 顶部上下文图：长标题自然换行，确认/重新预览/返回均可见，说明可读；下半部内层滚动内容由同一记录的左右区域图补充，不宣称此帧展示全部统计。 |
| reminders:confirm-cancel | [reminders-confirm-cancel.png](./reminders-confirm-cancel.png) | pass for the registered native reminder journey | accepted | 确认框打开态完整入镜，提交时重算与不等于已读/知悉的说明、取消和确认清楚，确认焦点可见；取消后零批次由原生旅程数据库断言证明。 |
| reminders:confirm-retry | [reminders-confirm-retry.png](./reminders-confirm-retry.png) | pass for the registered native reminder journey | accepted | 丢失响应重试并重载后显示手动、已受理、实际 1、生成 1、等待/跳过/失败均 0；说明与元信息已加载。重试始终同一批次由原生断言证明，不靠截图推导。 |
| bpm:stop-owned-stack | [first-stop-proof.json](./first-stop-proof.json)（非视觉） | pass | not-applicable | Resume, product/workspace verify, retained 3 announcements/4 versions, first public suspend all exit 0; ports free and private stoppedAt present. |
| bpm:resume-and-stop-again | [second-stop-proof.json](./second-stop-proof.json)（非视觉） | pass | not-applicable | Same retained runtime resumes with new owned generations; second round verify/data/first suspend all exit 0, no diagnostic hook or database reset. |
| bpm:any-decision | [bpm-any-decision.png](./bpm-any-decision.png) | pass; current four-case native profile reconciled | accepted | ANY 当前审批人在任务抽屉看到冻结正文、知悉 7 天与 46 B 送审附件；通过决定、非空意见及底部取消/驳回/通过可达。英文任务名和正文为业务测试夹具。 |
| bpm:all-recovery-preserved-quorum | [bpm-all-recovery-preserved-quorum.png](./bpm-all-recovery-preserved-quorum.png) | pass; current four-case native profile reconciled | accepted | ALL 恢复完成页显示已通过，所有人通过保持单行；原已接受意见人与替换人均列明，未再展示替换动作。投票与历史事实由原生旅程后端断言核验。 |
| reminders:preview-narrow-facts | [reminders-preview-narrow-facts.png](./reminders-preview-narrow-facts.png) | pass for the registered native reminder journey | accepted | 同一就绪预览内层纵向滚动后，完整催办信息和资格表左侧 1/1/0 可读；横向未入镜列由右侧图与宽屏补充，未扩大 viewport 或裁剪图片。 |
| reminders:preview-narrow-facts-right | [reminders-preview-narrow-facts-right.png](./reminders-preview-narrow-facts-right.png) | pass for the registered native reminder journey | accepted | 同一记录横向滚动到右端，有效期列和值完整可读；左侧预览时间被滚动裁切属于当前视窗，宽屏图保留完整时间与人数。 |

## 范围与保留

原 Core 5101/6401、BPM 5401/6501 均 suspended；DB、构件、备份、证据和绑定源码保留，原任务登记的 source/历史 Workspace 凭证阻塞未强制绕过。本轮没有生产发布、OCI/Linux/Jenkins、小程序真实 sender/真机、远程 BPM、50000 人或独立 340 条 UI 验收。行覆盖率未测量，不用通过数外推完整覆盖。

```text
acceptance_report: original announcement acceptance record + this evidence manifest
claim_level: targeted-tested for the two reported defects and configured regressions
current_sot: announcement-management.md section 18.30; existing joint manifest
business_scope: reminder preview capture readiness and owner-verified BPM stop
integration_tests: 98 script contracts (including actual processes), 1657 backend tests
integration_coverage: coverage_not_measured; no percentage claim
e2e_specs: existing Core announcement 17; existing BPM composition 4
e2e_collection: exact identity sets reconciled independently
e2e_execution: Core17 pass; BPM4 pass; restored preview1 overlaps; fault1 expected fail
feature_action_matrix: acceptance-matrix.json, 8 visual + 2 nonvisual scenarios
browser_evidence: eight unmodified originals, per-frame nine-dimension agent review
backend_evidence: backend JUnit summary; native SQL/API outcome assertions; lifecycle proofs
artifact_evidence: Core/BPM receipts, explicit locks, actual Java path identity
permission_negative: existing configured read-only/strict-approver negative journeys; process guards retained
visual_feedback: ready, confirmation open/cancel, accepted-after-replay, narrow scrolling, approval states
skip_fixme_threshold_retry_audit: configured native profiles have zero skip/retry; no threshold relaxation
did_not_run: production, OCI/Linux/Jenkins, physical mobile, screen reader, 50000 users, standalone340
remaining_blockers: none for the two defects; retained source/legacy registry and existing warning separate
allowed_claim: the two fixes and named regressions validated; no full-product golden/production claim
```
