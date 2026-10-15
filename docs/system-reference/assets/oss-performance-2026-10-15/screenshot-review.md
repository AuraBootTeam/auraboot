---
type: system-reference
status: active
---

# Screenshot review: 2026-10-15 OSS performance slice

Original images were opened individually at 1440×1000. Functional verdicts come from Playwright; visual verdicts are separate agent review.

Nine dimensions: hierarchy, spacing/alignment, typography, color/contrast, state accuracy, feedback, overlay anchoring, information density and raw-code exposure.

| ID | Screenshot | Visual result | Findings |
| --- | --- | --- | --- |
| PERF-01 | [home-ready.png](home-ready.png) | Partial | Data-ready inbox, shortcut names and both numeric zeros verified. Existing recent-visits secondary labels expose technical paths and record IDs; partial visual result, no full UI-golden claim. |
| PERF-02 | [menu-list.png](menu-list.png) | Pass | Sidebar selection and list title agree; readable fixture names, aligned columns, actions remain visible. Scrollable list extends below viewport. |
| PERF-03 | [list-empty.png](list-empty.png) | Partial | Search value and empty rows agree. Existing copy says there are no records and suggests first creation, although this is a filtered result; partial visual result. |
| PERF-04 | [list-page2.png](list-page2.png) | Pass | Second-page fixture identities differ; consistent column widths, sidebar and search state retained. |
| PERF-05 | [form-required.png](form-required.png) | Pass | Required name marked red with inline guidance, save/cancel distinct, no overlapping errors or raw identifiers. |
| PERF-06 | [create-saved.png](create-saved.png) | Pass | One named record and quantity 37 visible, success toast consistent with persisted save; date cell truncates within its column. |
| PERF-07 | [record-detail.png](record-detail.png) | Pass | Named record, generated business number and quantity 37 readable; section and tab hierarchy intact. Prior save toast remains briefly visible. |
| PERF-08 | [form-edit.png](form-edit.png) | Pass | Name, business number and quantity 37 prefilled; form groups aligned and save/cancel visible. Prior save toast remains briefly visible. |
| PERF-09 | [update-saved.png](update-saved.png) | Pass | Edited name visible in search and one persisted row after reload; longer row name truncates within its column. |
| PERF-10 | [delete-confirm.png](delete-confirm.png) | Pass | Centered confirmation over dimmed background; destructive red confirm and neutral cancel visually distinct, no clipping. |
| PERF-11 | [delete-removed.png](delete-removed.png) | Partial | Edited-record search retained and row removed after reload. Same existing filtered-empty wording issue as PERF-03; partial visual result. |
| PERF-12 | [anonymous-login.png](anonymous-login.png) | Pass | Anonymous private home shows coherent login form; no admin shell or retained session identity. |
| PERF-13 | [row-menu.png](row-menu.png) | Pass | Overflow anchored to the selected row; edit and red delete visible, no portal clipping or unrelated-row menu. |

The earlier `e2e-round3/home-ready.png` was rejected as a data-ready screenshot because it showed loaders. The committed home screenshot is from the final run after explicit inbox/shortcut/numeric-stat assertions. No global UI-golden verdict is issued.
