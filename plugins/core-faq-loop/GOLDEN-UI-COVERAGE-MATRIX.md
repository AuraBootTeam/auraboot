# Conversation → FAQ Loop (core-faq-loop) Golden UI Coverage Matrix

Every row below is backed by a test that has actually run, not a plan. The runner is
`scripts/faq-loop-golden-run.sh --slot <n>`: it brings up a host-first stack, imports this plugin,
seeds conversations, drives a real browser against a **live LLM** (no stub), and tears the stack
down. Its exit code is the result.

Last full run: **22 + 22 + 21 passed, 0 skipped, exit 0.**

Test paths refer to `web-admin/tests/e2e/faq-loop-{conversation-queue,review-workbench,pages-and-menu}.spec.ts`.

## Pages, blocks, fields, commands

| Page | Block / Area | Field / Action | Type | Business Meaning | Test Path | Assertion | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `faq_conversation_queue` | `faq_conv_table` | name / type / message count / candidate count / last message at | table columns | Which conversations are worth mining, and which are already mined | M2-1 | Rows are real conversations; the mined count is the candidate count, not a constant | Golden PASS |
| `faq_conversation_queue` | `faq_conv_table` | `faq_conv_type` | enum → dict tag | Chat vs group vs bot, as a label | M2-1 | Renders 「群聊」via `faq_conversation_type` dict, never the raw code `group` | Golden PASS |
| `faq_conversation_queue` | `faq_conv_table` | row action `faq:extract` | command action | Distil this conversation on demand | M2-3, P0 | Button exists in the UI; clicking it (not an API call) creates candidates | Golden PASS |
| `faq_conversation_queue` | `faq_conv_transcript` | seq / sender / content | table columns | Read the conversation before trusting anything mined from it | M2-2 | Sender shows 「客户」/「客服」via dict; content and timestamps are readable | Golden PASS |
| `faq_conversation_queue` | — | chit-chat conversation | negative case | The model must not invent FAQs out of small talk | M2-4 | Distilling a chit-chat conversation yields **zero** candidates, and the assertion runs only after the extraction actually completed | Golden PASS |
| `faq_conversation_queue` | — | re-distil | idempotency | Mining twice must not pile up duplicates | M2-6 | Second `faq:extract` on the same conversation adds no second copy | Golden PASS |
| `faq_candidate_workbench` | `faq_kpi_strip` | draft / approved / published / rejected | metric-strip | How much review work is outstanding | F1 | Counts are real numbers, never `-`; they refresh after row actions | Golden PASS |
| `faq_candidate_workbench` | `faq_kpi_strip` | click a metric | cross-block filter | Jump straight to the queue that metric describes | F2 | Clicking a status card re-queries the table and narrows it | Golden PASS |
| `faq_candidate_workbench` | `faq_filters` | keyword | filters | Find a candidate by question or answer text | F2 | Search narrows the table; filter inputs carry labels | Golden PASS |
| `faq_candidate_workbench` | `faq_table` | question / answer / status / confidence | table columns | Triage the queue at a glance | F3 | Question column is present; `faq_confidence` renders as a percentage with a progress dot | Golden PASS |
| `faq_candidate_workbench` | `faq_evidence` | question / answer / status / source conversation / seq range / reviewer / reject reason / KB doc | evidence-panel | Read the **whole** answer and its provenance before approving | F3 | The full answer is on screen (no ellipsis); status is a label not a raw code; confidence carries its unit (`95%`) or an honest `-`, never a bare number | Golden PASS |
| `faq_candidate_workbench` | `faq_source_transcript` | seq / sender / content | table columns | Check the answer against the conversation it came from — at the same time | F3, M2-5 | The transcript is readable on the same screen as the answer (the old review-drawer was an overlay that covered it) | Golden PASS |
| `faq_candidate_workbench` | `faq_action_bar` / row | `faq:update_qa` | command action + form | Fix a wording problem without leaving the console | F4 | Opens a form with question/answer prefilled; the edit persists (this was a silent no-op until `inputFields` was declared on the DSL **action**) | Golden PASS |
| `faq_candidate_workbench` | `faq_action_bar` / row | `faq:reject` | command action + form | Reject with a reason that is actually recorded | F5 | Prompts for a reason; the submitted reason is what lands in `faq_reject_reason` (a mutated reason is asserted, not just "a reason exists") | Golden PASS |
| `faq_candidate_workbench` | `faq_action_bar` / row | `faq:approve` | command action | Human approval — the model never publishes by itself | F6 | Status moves `draft` → `approved`; `faq_reviewed_by` / `faq_reviewed_at` are stamped | Golden PASS |
| `faq_candidate_workbench` | `faq_action_bar` / row | `faq:publish` | command action | Put the approved FAQ into the knowledge base | F6 | Creates a KB document with `source = conversation` and the FAQ is **retrievable** through the real retrieval API | Golden PASS |
| `faq_candidate_list` | `faq_candidate_tabs` | all / draft / approved / published / rejected | tabs | Segment candidates by lifecycle status | P2 | Tabs render and the table shows real rows, not an empty stub | Golden PASS |
| `faq_candidate_list` | `faq_candidate_table` | question / answer / status / confidence | table columns | The plain list view of the same data | P2 | Columns show semantic labels; no raw record `id` is exposed | Golden PASS |
| `faq_candidate_detail` | `faq_candidate_detail_toolbar` | approve / reject / update_qa / publish | toolbar actions | The second command path — detail page, not the workbench | P3, P4 | Buttons are state-aware (`visibleWhen`): review actions only while `draft`, publish only once `approved`. Approving here persists exactly as the row action does | Golden PASS |
| `faq_candidate_detail` | `section_qa` / `section_review` / `section_trace` | all fields | form-section (read-only) | Inspect one candidate in full | P3 | Detail renders the candidate; confidence uses the `progress` renderer | Golden PASS |
| `faq_candidate_form` | `qa` / `target` / `buttons` | question / answer / target KB | form + form-buttons | Hand-write a FAQ candidate | P2 (page loads) | Required fields are declared; the form is reachable | Golden PASS (form submit not exercised — see gaps) |
| Sidebar | `faq_root` → 3 leaves | 可提炼会话 / FAQ 审核台 / FAQ 候选 | menus | Every page is reachable by clicking, not just by URL | P1 | Each menu entry opens its page **from the sidebar**; standalone DSL pages use `/p/c/{pageKey}` | Golden PASS |

## Field coverage

`faq_candidate` (11 fields, `mt_faq_candidate`): `faq_question`, `faq_answer`, `faq_confidence`,
`faq_status`, `faq_source_conversation_pid`, `faq_source_seq_range`, `faq_target_kb_id`,
`faq_kb_document_pid`, `faq_reviewed_by`, `faq_reviewed_at`, `faq_reject_reason` — all 11 are
rendered and asserted across F3 (evidence panel), F4/F5 (mutation), F6 (publish → `faq_kb_document_pid`).

`faq_source_conversation` (5 fields, metadata shell over `ab_im_conversation`, `skipTableCreation`):
`faq_conv_name`, `faq_conv_type`, `faq_conv_message_count`, `faq_conv_last_message_at`,
`faq_conv_candidate_count` — all 5 are columns in the queue and asserted in M2-1.

## Command coverage

| Command | Input fields | Driven from UI | Backend evidence |
| --- | --- | --- | --- |
| `faq:extract` | `faq_target_kb_id` | ✅ queue row action (M2-3, P0) | Live DeepSeek call; `ConversationFaqExtractionLiveIT` |
| `faq:update_qa` | `faq_question`, `faq_answer` | ✅ workbench + detail (F4) | `DynamicDataJsonbUpdateIT` |
| `faq:approve` | — | ✅ workbench row + detail toolbar (F6, P4) | Status + reviewer stamped in `mt_faq_candidate` |
| `faq:reject` | `faq_reject_reason` | ✅ workbench (F5) | Reason persisted, asserted by value |
| `faq:publish` | — | ✅ workbench (F6) | KB document created with `source = conversation`; retrievable via retrieval API |

## Status coverage

`draft` → `approved` → `published`, plus `draft` → `rejected`. Every transition is driven from the
browser and asserted on the resulting record: F6 covers draft→approved→published, F5 covers
draft→rejected. Toolbar `visibleWhen` is asserted state-by-state in P3 (review actions hidden once
the candidate leaves `draft`; publish hidden until `approved`).

## Known gaps

- **`faq_candidate_form` submit is not exercised.** The page loads (P2) but hand-writing a candidate
  end-to-end is untested. The form is a secondary path — every candidate in the product flow is
  created by `faq:extract` — but this row is honestly "loads, not driven".
- **Auto-trigger on conversation close is not implemented.** It is blocked on S1: `ab_im_conversation`
  has no `status` column yet, so there is no close event to hook. Extraction is manual (queue row
  action) until then. This is a scope boundary, not a defect.
- **Confidence is advisory and may be absent.** A model that reports no confidence shows `-`, not
  `0%`. Nothing gates on it; a human always approves.
