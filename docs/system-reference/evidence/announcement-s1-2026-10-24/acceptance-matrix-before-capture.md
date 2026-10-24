---
type: system-reference
status: active
created: 2026-10-24
updated: 2026-10-24
---

| ID | 功能 | 行动点 | 状态/分支 | 验收要点 | 驱动 | 截图 ID | 状态 |
|---|---|---|---|---|---|---|---|
| S1-UI-01 | 公告 S1 | 新建草稿 | form/happy | 创建表单和引导；提交仅业务字段 | announcement-lifecycle / controlled-writes | create-form-guidance.png | new |
| S1-UI-02 | 公告 S1 | 列表筛选 | empty/recovery | 关键词空结果后重置恢复 | announcement-lifecycle / controlled-writes | filtered-empty-recovery.png | new |
| S1-UI-03 | 公告 S1 | 草稿编辑 | detail/happy | observed revision=1；编辑持久化并详情回显 | announcement-lifecycle / controlled-writes | draft-detail.png | new |
| S1-UI-04 | 公告 S1 | 发布拦截 | sad | 明确尚未启用；状态仍为草稿 | announcement-lifecycle / controlled-writes | publication-unavailable.png | new |
| S1-UI-05 | 公告 S1 | 删除草稿 | confirm | 确认后受控删除；列表消失 | announcement-lifecycle / controlled-writes | confirm-announcement-delete_announcement.png | new |
| S1-UI-06 | 公告 S1 | 删除结果 | happy | 刷新无已删除草稿 | announcement-lifecycle / controlled-writes | deleted-draft.png | new |
| S1-UI-07 | 公告 S1 | 普通成员权限 | 权限 | 无编辑权时隐藏入口；后端拒绝且无写入 | announcement-lifecycle / controlled-writes | permission-denied.png | new |
| S1-UI-08 | 公告 S1 | 历史公告撤回修订 | edge | 已发布撤回、基于历史建立草稿，版本不可变 | announcement-lifecycle / controlled-writes | legacy-revision.png | new |
| S1-UI-09 | 公告 S1 | 全字段草稿编辑 | form/happy | 重要程度/置顶显式修改与版本提交 | announcement-lifecycle | edit-form-values.png | new |
