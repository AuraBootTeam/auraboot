---
type: retro
status: active
created: 2026-10-02
---

# 管理员权限能力验收报告

`allowed_claim: targeted-tested / partial`。当前修复候选 browser E2E did_not_run（executed=0）；r6已实测，r7分配被共享aura-bpm登记缺配置阻挡。r5 编辑器14项通过属于前一候选，不能替代后续业务依赖、范围名称、成员新增移除、只读按钮和文件下载变更的验证。Goal 保持 active，未完成验收。

最新状态：owner授权额度18并确认局域网ssh dev后，r6启动及双重身份验证成功；编辑器14项实测9通过、5失败、0retry。长预览超屏和成员checkbox双重切换为真实交互缺陷，范围两项为5→8动作分母漂移。已集中修复并通过类型检查；修复后的候选仍待浏览器验收。此前capacity/VPN阻挡已解除，旧描述保留为历史记录。

补充实测：旧r6冻结候选CI完整134项终态为111 passed、20 failed、3 did_not_run（串行组前项失败导致后续未运行）；job 20261002t151751z-e0610564 状态failed，覆盖门明确拒绝。证据ui/capability-r6-ci-full-terminal.log与capability-r6-ci-terminal-state.json。失败涉及BOM只读直达表单、长预览、关联文件拒绝被包装成500，以及转换/下载/角色入口等待调查项；不能将整个失败批次归为环境问题。

当前修复候选：成员真实DOM事件2项通过且受控删除修复后2项失败、恢复后2项通过；能力+成员9文件60项通过；表单+成员2文件62项通过（与前批有重叠，不相加）。定向后端UT DataAccessAuthorizationHelperImplTest 12项、FileUploadControllerTest 9项全部通过、0skip，补验证loader拒绝原样抛出与普通故障包装不变。typecheck最终退出0。证据ui/capability-r7-form-member-final.log、capability-r7-form-typecheck-final.log、capability-r7-file-auth-unit-final.log及JUnit XML。首次Gradle选择未限定根任务导致included build无匹配测试，未作为通过；改为:test后实际执行21项。

13个BOM表单24个主要动作补齐命令已要求的bom.rule.manage；通用form在当前primary动作全部显式拒绝时呈现无权限页。关联文件GET拒绝异常保留403语义，不改授权裁决。两项修复仍须真栈浏览器复验。13表单UX before/after均120错误、0警告，本轮未新增但未达到黄金标准。r7共享aura-bpm缺配置阻挡仍在，没有删除其他任务登记或改共享dirty配置。

唯一实施账本是[已确认完整方案](../plans/2026-10/2026-10-02-permission-transparency-plan.md)，稳定契约见[范围与记录诊断 SoT](../system-reference/permission-scope-and-record-diagnostics.md)。本报告不创建另一份任务清单。

## 执行账本与证据边界

| 层与源码 | collected | executed | passed | failed | skipped | did_not_run | 证据 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| r5 OSS service/DTO UT | 15 | 15 | 15 | 0 | 0 | 0 | Workspace ui/capability-r5-oss-transaction-it.log + JUnit XML |
| r5 OSS controller/transaction IT | 8 | 8 | 8 | 0 | 0 | 0 | 同上；enterprise_157，真实交换与故障回滚各1 |
| r5 Enterprise UT | 4 | 4 | 4 | 0 | 0 | 0 | ui/capability-r5-transaction-explainer-it.log + JUnit XML |
| r5 Enterprise explanation IT | 4 | 4 | 4 | 0 | 0 | 0 | 同上；三种授权码来源正例及未知来源负例；未包含真实 recordId 正例 |
| r5 editor browser | 14 | 14 | 14 | 0 | 0 | 0 | ui/capability-r5-browser-results.json |
| r6 editor browser | 14 | 14 | 9 | 5 | 0 | 0 | ui/capability-r6-editor-results-run2.json；修复后需重验，未以重试消除失败 |
| r5 product fixed golden | 56 | 未完成 | 日志46 | 日志2 | 未得终态 | 未得终态 | ui/capability-r5-product-golden.log；第49项附近SIGKILL，缺最终JSON，不形成门禁通过 |
| r6 首批前端 UT | 58 | 58 | 58 | 0 | 0 | 0 | ui/capability-r6-unit.log；后续成员组件变更不在该次执行中 |
| r6 范围代码 typecheck | 不适用 | 1 | 1 | 0 | 0 | 0 | ui/capability-r6-typecheck.log，退出0 |
| 当前 OSS 静态能力测试 | 5 | 5 | 5 | 0 | 0 | 0 | platform-admin 4、composition gate 1；未等同浏览器 |
| 当前 Quote / BOM / CRM 配置测试 | 13 | 13 | 13 | 0 | 0 | 0 | Quote6、BOM4、CRM3，运行命令记录；当前模型读取闭包静态有效 |
| 当前 browser / product / roles | 未完成 | 0 | 0 | 0 | 0 | 全部 | r6 两次启动被 capacity gate 拒绝，不把 collection 当执行 |
| Linux CI | 未执行 | 0 | 0 | 0 | 0 | 全部 | 私网 ping/SSH 不通；隧道未启用，sudo -n 需要密码 |

## 功能与可信度审查

- r5 真实编辑器旅程覆盖完整授予、预览、保存、读回故障与重试、部分补齐与显式撤销、共享动作保留、默认 dept/team 继承、只读诊断。原图显示两个范围展示缺陷，已修但尚未视觉重验，因此不能称 UI golden pass。
- r5 原图索引在 Workspace ui/capability-r5-editor-screenshots。成员弹窗显示英语有效期且缺组织树组件却默认打开空入口；后续改为已注册组件驱动入口，新增成员添加/移除及 reload 回显测试，尚未执行。
- 当前独立 BOM 规则角色旅程注册在既有 role-capability-closed-loop 文件，通过主角色编辑器保存维护能力，再以新身份创建规则，撤销为查看后重新登录，读取原记录、隐藏写按钮、HTTP403并证明拒绝后无新记录。没有 API 代替核心授权/业务创建动作；仍未执行。
- 新建报价旅程从真实上传到资料页签下载刚上传的 CPL，验证同一运行目标、原文件名与逐字节内容；尚未执行。共享文件负例要求精确403，500不再算权限拒绝。
- BOM两项失败已定位到鲜活环境没有物料库前提。测试改用正式建料命令生成规范化属性和冻结投影，保留业务匹配断言；尚未执行，不能宣称问题已解决。
- 当前配置增加依赖不等于现有角色自动获得动作；部分授权仍在分母。隐藏菜单、未来模型和未启用功能保持未启用；成员基线与业务页面支持读是不同能力。
- 客户列表原通用批量删除按钮使用业务维护权限显示，却调用要求模型删除权限的通用 API。配置已关闭该重复入口，保留校验关联业务的 crm:delete_account 命令，不补授 raw model delete。配置检查通过；既有三个业务角色旅程增补独立客户创建、真实选择删除、命令目标 PID、刷新与持久化结果断言，尚未执行。
- 不使用 retry、skip、阈值降级消除失败。已有受控 recordPid mutation 曾使 UT 变红并恢复；本轮新增按钮/文件内容断言尚未完成 mutation，必须留作未执行证据。

## Final Evidence Pack

```text
acceptance_report: 本文件（active）
claim_level: targeted-tested / partial
current_sot: permission-scope-and-record-diagnostics.md；唯一 permission-transparency-plan.md
business_scope: 当前 Quote/BOM/CRM/组织/平台管理组合，日常上层授权与只读诊断
integration_tests: r5 OSS15UT+8IT、Enterprise4UT+4IT；均绑定明确 enterprise_157
integration_coverage: coverage_not_measured
e2e_specs: 既有编辑器、固定产品 golden、roles-full；新增独立角色用例仍在原角色文件
e2e_collection: 独立角色文件 collected4；当前候选待完整collection；不把注册当执行
e2e_execution: 当前候选0；r5编辑器14通过；固定产品门中断
feature_action_matrix: Workspace动作盘点及唯一AUTH行动点账本；未验条目保留
browser_evidence: r5 JSON/日志/截图；r6 startup拒绝日志
backend_evidence: r5 managed Gradle日志与逐类JUnit XML
artifact_evidence: r5已有产品Excel下载；新增上传CPL readback did_not_run
permission_negative: r5原矩阵；新增精确403和只读按钮 did_not_run
visual_feedback: r5原图具体缺陷已记录，当前候选 did_not_run
skip_fixme_threshold_retry_audit: no retries/skip to hide failures；当前新增断言mutation未执行
did_not_run: 当前候选浏览器/全产品/全角色/原图重验/LinuxCI
remaining_blockers: 验证常驻名额；CI私网隧道；上述未执行验收
allowed_claim: 已实现并提交候选改动，已有定向测试；未完成全量验收，未发布，未合并
```

Workspace证据根：`/Users/ghj/work/auraboot/.workspace/evidence/permission-transparency/`。最终必须更新本报告与唯一实施账本，再按已验证确切提交收口。

当前五仓草稿 PR：OSS #2157、Quote #534、Enterprise #1431、Plugins #612、CRM #25。PR open 不代表验收完成；新的三个 PR 聊天附件调用均因 transport closed 失败，GitHub 审查入口已存在。r6 第四次启动仍被 capacity gate 拒绝，没有运行当前浏览器测试。

r6第七次启动获owner授权的18额度后成功；第一次浏览器命令因缺少PG_DB在收集前被环境契约拦截，补明确enterprise_158后执行14项。双重身份、启动、类型检查、失败截图与暂停日志在Workspace ui/capability-r6-*；不得把收集前拒绝写成产品失败。CI批次permission-capability-r6-5d32c1db9-v1：Quote/BOM job 20261002t151751z-e0610564 running（旧候选），queued backend/composition已因候选更新公开取消；新候选尚无CI receipt。
