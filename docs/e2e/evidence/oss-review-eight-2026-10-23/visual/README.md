---
type: system-reference
status: active
---

# Attachment screenshot review

Identity: commit `1d79315dbababeefaae5f5478d7836dd2f3f367a`, runtime `oss-review-eight`, browser Chromium, viewport 1280 × 720, locale `zh-CN`, admin role, owned page and ZIP fixtures. The [matrix](../acceptance-matrix.json) was written in managed evidence before capture; this copy contains the final results. Each original image was opened individually and reviewed using agent vision.

| ID | Image | Functional | Interaction | Scoped visual | Evidence trust |
|---|---|---|---|---|---|
| F1 | [Rejected upload](../screenshots/upload-rejection.png) | pass | pass | pass | pass |
| F2 | [Preview and download](../screenshots/upload-preview-before-download.png) | pass | pass | pass | pass |
| F3 | [Removed attachment](../screenshots/upload-removed.png) | pass | pass | pass | pass |

| Dimension | F1 observation | F2 observation | F3 observation |
|---|---|---|---|
| Hierarchy | Field, upload area and inline error have a clear reading order. | Filename leads the modal; Download is its primary action. | Upload affordance and empty count are visible. |
| Layout/density | Error stays directly below the drop zone. | Centered modal has no clipping or overlap. | Field recovers the original upload area after removal. |
| Typography/alignment | Filename and 1MB limit are readable. | Filename and button align within the modal. | Hint and 0/1 count remain aligned and readable. |
| Action expression | Error names the rejected file and size limit. | Download and close controls are clearly distinct. | Upload affordance returns for another attempt. |
| Content/data | Rejected filename matches the oversized fixture. | Preview filename matches the downloaded 32-byte fixture. | Removed filename is absent. |
| State quality | Rejection is inline and count remains zero. | Unsupported ZIP preview explains availability and offers download. | Previous rejection clears after the successful upload/removal flow. |
| Fit/accessibility | No important field content is clipped at the recorded viewport. | Close control is visible; Escape closure was asserted. | Upload area is visible; other device sizes were not tested. |
| Consistency | Existing SmartUpload component styling is preserved. | Existing file-preview modal styling is preserved. | Restored empty state matches the initial component. |
| Evidence | No upload request was made for this file. | Persisted metadata/list readback preceded capture; byte equality followed download. | DELETE success and subsequent GET 404 preceded capture. |

Judgement is limited to the attachment component states in this backend regression. The deliberately named fixture page and existing English preview labels are visible in context; this is not a redesign or a whole-page localization/UX acceptance. There is no screenshot baseline promotion or human usability review. No blocking defect was observed within the scoped attachment states.
