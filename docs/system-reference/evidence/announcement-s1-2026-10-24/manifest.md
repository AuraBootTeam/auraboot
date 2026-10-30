---
type: system-reference
status: active
created: 2026-10-24
updated: 2026-10-24
---

# S1 screenshot and action evidence

Final UI run: `2026-10-24T12-13-40-542Z-79a0bbf5-3751-4ecc-9015-f762b65d89ad`; tested source `bc5a0818ec107325eb18925c0dc0a9e735ce79db`. Chromium, locale zh-CN, desktop 1280×720 (some images are element crops). Finite CSS animations were disabled for final capture. All 15 original images were opened and reviewed by agent-vision; this is not human usability testing.

The pre-capture matrix is retained in acceptance-matrix-before-capture.md. Final scenario verdicts follow; exact assertions resolve through action-manifest.json and playwright-results.json.

| ID | Action | Functional | Visual | Screenshot |
| --- | --- | --- | --- | --- |
| S1-UI-01 | 新建草稿 | pass | agent-vision: pass | [create-form-guidance.png](shots/create-form-guidance.png) |
| S1-UI-02 | 列表筛选 | pass | agent-vision: pass | [filtered-empty-recovery.png](shots/filtered-empty-recovery.png) |
| S1-UI-03 | 草稿编辑 | pass | agent-vision: pass | [draft-detail.png](shots/draft-detail.png) |
| S1-UI-04 | 发布拦截 | pass | agent-vision: pass | [publication-unavailable.png](shots/publication-unavailable.png) |
| S1-UI-05 | 删除草稿 | pass | agent-vision: pass | [confirm-announcement-delete_announcement.png](shots/confirm-announcement-delete_announcement.png) |
| S1-UI-06 | 删除结果 | pass | agent-vision: pass | [deleted-draft.png](shots/deleted-draft.png) |
| S1-UI-07 | 普通成员权限 | pass | agent-vision: pass | [permission-denied.png](shots/permission-denied.png) |
| S1-UI-08 | 历史公告撤回修订 | pass | agent-vision: pass | [legacy-revision.png](shots/legacy-revision.png) |
| S1-UI-09 | 全字段草稿编辑 | pass | agent-vision: pass | [edit-form-values.png](shots/edit-form-values.png) |

Per-image nine-dimension findings and SHA-256: [screenshot-review.json](screenshot-review.json). No open in-scope UI defects. Earlier runs contained capture timing defects; only this final set is accepted.

All six commands are covered: create/update/delete/publish-denial in API and browser assertions; archive/republish in the historical-version browser case. List, form and detail pages are menu-driven. The static collector cannot infer every dynamic command URL or menu navigation; generated static “uncovered” entries are reconciled here by exact runner cases, rather than deleted from the denominator.

Additional backend, migration, contract and scope limits: [acceptance report](../../../system-reference/2026-10-24-announcement-s1-testing-gate-acceptance-report.md). No claim of full announcement/BPM completion.
