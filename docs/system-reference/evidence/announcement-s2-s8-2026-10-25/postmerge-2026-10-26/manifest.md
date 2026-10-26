---
type: system-reference
status: active
created: 2026-10-26
updated: 2026-10-26
---

# 合并后公告截图验收

本轮先登记 32 场景，再执行并逐张查看原图。来源为同一公告需求矩阵，不新增独立覆盖分母。每图的原始 SHA-256、尺寸、locale、producer、九维观察与四类裁决见 [matrix](./acceptance-matrix.json)。仅选中帧具有视觉结论；同轮其他图片不自动视为复核通过。

功能：Core 17/17、BPM 4/4，均单次且 fail/skip/retry 为 0，认证各 1 另计。互动：原生浏览器旅程断言，包括取消、授权拒绝、冻结附件与审批恢复；不从像素推导后台事实。视觉：32/32 已查看，1 项非阻断 R-UI-01（催办预览垂直留白较大），其余所捕获状态通过。证据信任：Core `7c5c1a4951b9d44a5282a0273b939762cd999d3a`，BPM `1b4b702285dc26afb416fc600174a2efc0244542`；实际 Java classpath、新组合锁及产品 verify 在浏览器前核对。无旧图重标。

BPM 正式组合 97 Node 契约及 1657 后端项通过（224 JUnit，fail/error/skip=0），新空库按序完成 161 Flyway。Core 启动使用 schema 快照，不把文件数写成逐条 Flyway。BPM Workspace 仍有 RUNTIME_MANIFEST_MISSING warning，产品 verify 与实际进程身份独立通过。

失败/排除：首轮 Core 依赖不完整，未执行 browser；首轮 BPM browser 使用旧路径，被排除；第二次启动在 Web 依赖身份变化处拒绝，通过公开 update-web 后才执行当前轮。适配器生成时未识别 evidence symlink 的物理路径，导致一次失败启动的生命周期日志覆盖了首轮同名生命周期日志；首轮 browser/results/images 仍保留，未冒称完整保留原生命周期日志。最终 BPM business_exit=0，但首次 stop_exit=2（进程归属探针异常、原因未定位），随后调用同一所有权校验器核对已退出进程，再由公开 runtime suspend 成功。不能将首次退出改写为 0/0。Core 最终 0/0。

环境与构件、DB、备份均保留，服务暂停；Core 5101/6401、BPM 5401/6501。未验证读屏器、小程序真实发送、远程 BPM、50000 人、独立 340 corpus、OCI/Linux/Jenkins 或生产。对应私有轮次：`.workspace/evidence/announcement-management/merge-ui-2026-10-26`。

| 场景 | 预期 | 原图 | 视觉裁决 | 观察与限度 |
| --- | --- | --- | --- | --- |
| lifecycle:create-form-guidance | Empty native form has title/body guidance, grouped fields and a visible fixed save/cancel footer. | [lifecycle-create-form-guidance.png](./lifecycle-create-form-guidance.png) | accepted | 标题示例、草稿保存后的通知设置指引和富文本工具栏清楚；底部保存/取消固定可达。本帧为表单上半屏，不外推未入镜的附件区域。 |
| lifecycle:edit-form-values | Important priority, pin toggle, expiry and edited rich-text value remain visible with the save footer. | [lifecycle-edit-form-values.png](./lifecycle-edit-form-values.png) | accepted | 已编辑标题和正文、重要优先级、置顶开关及到期时间均清楚回显；保存/取消仍可达。原生日期格式属于浏览器控件表现。 |
| lifecycle:draft-detail | Saved draft values and status are readable; management actions are separate from content. | [lifecycle-draft-detail.png](./lifecycle-draft-detail.png) | accepted | 草稿和重要状态有文字标识；标题、正文、置顶、到期时间分组可读，右上通知设置/免审发布/统计入口可辨。未发布占位不伪装为已发布。 |
| lifecycle:permission-denied | Read-only manager has view actions and no create/edit menu; persisted API denial is asserted by the test. | [lifecycle-permission-denied.png](./lifecycle-permission-denied.png) | accepted | 只读身份可见实际列表与查看入口，新建和编辑动作缺席；长标题在列内省略，未破坏列对齐。此图只裁决只读列表状态。 |
| lifecycle:legacy-revision | The revised draft has draft-specific menu actions; previous publication time remains history. | [lifecycle-legacy-revision.png](./lifecycle-legacy-revision.png) | accepted | 历史公告修订后显示草稿，行内更多菜单清楚提供通知设置、编辑和免审发布；没有出现旧冻结版本被改写的视觉信号。 |
| lifecycle:notification-settings-audience | This scroll position shows unavailable approval reason, scheduled local time, exemption reason and acknowledgement default; it does not show the lower audience picker. | [lifecycle-notification-settings-audience.png](./lifecycle-notification-settings-audience.png) | accepted | 免审方式、不可用审批的原因、预约时刻、独立免审原因和默认知悉要求清晰；固定保存/取消可见。本滚动位置未展示受众控件，不外推该区域视觉结论。 |
| lifecycle:scheduled-publication | The newly scheduled row has a human scheduled status, without a publication time. | [lifecycle-scheduled-publication.png](./lifecycle-scheduled-publication.png) | accepted | 受控公告行清楚显示重要与定时待发布，实际发布时间保持空值；查看入口仍可达。 |
| lifecycle:canceled-publication | The same top row now shows withdrawn; no false published timestamp is displayed. | [lifecycle-canceled-publication.png](./lifecycle-canceled-publication.png) | accepted | 同一受控公告行转为已撤回，未显示虚假实际发布时间；状态变化具有文字而非仅颜色。 |
| lifecycle:published-and-projected | The newly published row has a publication time and the separate bell/inbox badges are visible. | [lifecycle-published-and-projected.png](./lifecycle-published-and-projected.png) | accepted | 受控公告显示已发布，发布时间和重要状态清晰，顶部铃铛与收件箱均显示真实的 1；未与已撤回行混淆。 |
| lifecycle:confirm-announcement-delete_announcement | Destructive confirmation is open with a visible cancel and focused confirm action. | [lifecycle-confirm-announcement-delete_announcement.png](./lifecycle-confirm-announcement-delete_announcement.png) | accepted | 删除确认框在打开状态捕获，不可恢复提示和红色确认、普通取消按钮明确，键盘焦点可见；背景为已筛出的单条草稿。 |
| lifecycle:deleted-draft | The filtered empty result explains clearing filters or creating a draft; it is not a loading skeleton. | [lifecycle-deleted-draft.png](./lifecycle-deleted-draft.png) | accepted | 删除后的筛选结果为空，页面说明可新建或清除筛选，不是空白或错误页；原查询条件仍可见。 |
| bpm:any-submitted | Native submission returns to the filtered announcement list with pending approval and no publication timestamp. | [bpm-any-submitted.png](./bpm-any-submitted.png) | accepted | 仅显示当前夹具公告为审批中，实际发布时间为空；查看入口与筛选行清楚，无提前发布信号。 |
| bpm:any-frozen-readonly-attachment | Readonly body and retained attachment name/size are readable; no decision actions appear in view mode. | [bpm-any-frozen-readonly-attachment.png](./bpm-any-frozen-readonly-attachment.png) | accepted | 冻结正文、预计接收 3 人、知悉 7 天与 46 B 附件可读；只读抽屉仅有取消，无编辑或决策按钮。英文任务标题与正文为流程夹具内容。 |
| bpm:any-decision | Explicit decision mode shows frozen body, attachment, opinion controls and fixed approve/reject actions; the scroll area keeps the footer visible. | [bpm-any-decision.png](./bpm-any-decision.png) | accepted | 同一冻结送审块上方信息保留，审批决定为通过且填写意见；底部取消、驳回、通过可达，意见区可继续滚动。 |
| bpm:any-completed-frozen-history | Completed task is readonly, with original submitted body/file and a cancel-only footer. | [bpm-any-completed-frozen-history.png](./bpm-any-completed-frozen-history.png) | accepted | 已办任务计数 1、待办 0，仍显示原送审正文及 46 B 附件，仅有取消；本帧不单独证明附件字节，字节断言来自旅程。 |
| bpm:all-submitted | Named ALL request is pending; no premature published status appears. | [bpm-all-submitted.png](./bpm-all-submitted.png) | accepted | ALL 原申请在列表显示审批中，发布时间为空；此图来自拒绝旅程，未使用恢复旅程同名截图。 |
| bpm:all-first-decision | First named approver sees the original snapshot and explicit decision controls; backend assertions keep the ALL request non-terminal. | [bpm-all-first-decision.png](./bpm-all-first-decision.png) | accepted | 第一位审批人看到原送审正文、知悉规则和附件，已选通过且填写意见；尚未终结由旅程断言证明，未把这一帧当作最终批准。 |
| bpm:all-reject-decision | Reject mode and nonempty reason are visible together; the footer remains reachable. | [bpm-all-reject-decision.png](./bpm-all-reject-decision.png) | accepted | 另一位审批人的决定明确为驳回，填写非空意见；原送审正文及附件仍可读，红色驳回动作可辨。 |
| bpm:all-rejected-history-after-revision | Completed original task still shows its submitted body and file after a later revision; no edit/approval action is exposed. | [bpm-all-rejected-history-after-revision.png](./bpm-all-rejected-history-after-revision.png) | accepted | 后续修订后在已办页仍能查看原正文、送审时刻和附件，仅显示取消；没有重新决策入口。 |
| bpm:all-blocked-node-resolution | Member picker is open with two selected reviewer chips and a checked searched option. The normal popup partly covers the reason field; this is a picker-open state, not a fully visible final confirmation. | [bpm-all-blocked-node-resolution.png](./bpm-all-blocked-node-resolution.png) | accepted | 人员选择器已打开，两个审批人标签与已选结果可见；弹出层正常覆盖原因区，不能据此认定最终确认内容全部入镜。 |
| bpm:all-blocked-original-resume | Original-task recovery dialog clearly requires a reason and exposes cancel/record-and-execute actions. | [bpm-all-blocked-original-resume.png](./bpm-all-blocked-original-resume.png) | accepted | 已阻断背景上显示恢复原任务的必填原因与记录并执行；取消、确认与焦点均清楚，无默认以管理员代办的提示。 |
| bpm:all-recovery-replacement-confirm | Pending reviewer, selected replacement, nonempty reason and confirmation actions fit the modal. | [bpm-all-recovery-replacement-confirm.png](./bpm-all-recovery-replacement-confirm.png) | accepted | 待替换审批人、选中的新审批人和非空原因分组清楚，弹窗上下文与取消/记录并执行均完整入镜。 |
| bpm:recovery-final-decision | The new non-recipient approver sees the same frozen body/file and can submit a qualified decision. | [bpm-recovery-final-decision.png](./bpm-recovery-final-decision.png) | accepted | 新审批人看到同一原送审时刻、正文、3 人预计接收与 46 B 附件；审批意见和底部决策可达，实际替换身份由旅程核验。 |
| bpm:all-recovery-preserved-quorum | Reloaded recovery page shows approved, one-line ALL label, the two retained/current approvers and no replacement action. | [bpm-all-recovery-preserved-quorum.png](./bpm-all-recovery-preserved-quorum.png) | accepted | 恢复页重载后显示已通过，所有人通过标签保持单行，原已投票人与替换人均列明且不再显示替换入口；底层票数事实由旅程核验。 |
| bpm:any-default-definition-selected | Native policy modal shows a human definition name selected, readiness guidance, daily 09:00, quota 3 and shared 60-minute spacing; fixed save/cancel footer remains visible. | [bpm-any-default-definition-selected.png](./bpm-any-default-definition-selected.png) | accepted | 租户默认流程选中当前 ANY 定义，自动催办 09:00、最多 3 次及 60 分钟间隔清楚；表单可滚动且底部保存/取消固定，未外推未入镜字段。 |
| bpm:all-withdrawal-reason | Reasoned withdrawal dialog is open with required reason, cancel/submit actions and an explicit notice that acceptance still requires actual process reconciliation. | [bpm-all-withdrawal-reason.png](./bpm-all-withdrawal-reason.png) | accepted | 撤销审批申请弹窗要求原因且已填写，说明受理后核对实际流程结果，取消与提交撤销请求清楚；截图捕获提交前。 |
| reader:frozen-attachment | Frozen recipient body, named retained file and distinct acknowledgement below content. | [reader-frozen-attachment.png](./reader-frozen-attachment.png) | accepted | 正文与图片、附件名及 56 B 大小清晰，知悉状态与首次时间独立呈现；确认知悉位于内容下方，返回我的公告可见。 |
| reader:pending-inbox | Narrow inbox exposes the pending acknowledgement and an accessible all-todos action. | [reader-pending-inbox.png](./reader-pending-inbox.png) | accepted | 390px 收件箱浮层完整入镜，待知悉事项、公告待知悉标签及查看全部待办均可读；未把通知阅读当作已知悉。本帧不单独证明 Header 徽标刷新时序。 |
| reader:ack-confirm | Keyboard-opened acknowledgement confirmation fits 390px and cancellation preserves the unread acknowledgement fact. | [reader-ack-confirm.png](./reader-ack-confirm.png) | accepted | 390px 知悉确认框完整显示首次确认说明，取消与确认未被遮挡且焦点可见；背景加载态属于等待该确认的命令交互，取消结果由原旅程断言。 |
| statistics:overview | Captured real recipient counts and snapshot-backed statistics render with an authorized export entry. | [statistics-overview.png](./statistics-overview.png) | accepted | 固定版本和统计时刻、授权范围与知悉截止分组清楚；接收 1、已读 1、知悉 0 与 100%/0% 分离，导出主动作可辨。下方明细未全部在本帧入镜。 |
| reminders:preview | Actual recipient preview, cooldown and confirmation are clear; preview alone consumes no quota. | [reminders-preview.png](./reminders-preview.png) | accepted-with-note | 实际可催办人数 1、当前限流 0、预览与有效时间以及确认/重新预览可读。摘要与计数表之间留白较大，记录为非阻断版式意见 R-UI-01，不在本次验证中重设计。 |
| reminders:resume-original | Original plan resumes after member authority recovery without a new publication or reset quota. | [reminders-resume-original.png](./reminders-resume-original.png) | accepted | 恢复后计划显示运行中、已用额度 0/上限 3、每日 09:00 和去重 60 分钟；无历史批次有解释。该普通公告未要求知悉，截止为空符合夹具。 |
