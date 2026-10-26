---
type: retro
status: active
created: 2026-10-27
---

# Maritime shared UI integration verification

Allowed claim: targeted hermetic frontend verification of the integrated native
detail/prompt/workspace contracts. Browser E2E did_not_run (executed=0); this report
does not accept product visuals, persistence or field integration.

Contribution commits `e791d51bd0ab87f4830664b675f27d13b8b63dcf` and
`f1aec1629d40e9d8a1da74ae9d4dc23af800d4b9` were applied in order as
`abfd0763d` and `029057966cd21b44f35d9c181d36508705308d61`. All 78 task files
match the contribution's final file hashes. Its 8,924-case full result was inspected
as contributor evidence, not relabeled as an independent integration run.

The integration owner ran 24 files and 277 cases on fixed `029057966`, using its
existing `maritime-parallel-core` runtime (slot 26). All 277 passed, with zero
failed, pending or todo cases. The 21 behavior suites cover modal source identity
and focus, retained prompts, reset/table state, timelines, responsive shell/grid,
permissions and schemas. Three existing environment-contract suites were included;
runtime endpoint and PG variables were unset only in that hermetic test process.
No test assertion, retry, skip or threshold was changed.

The managed evidence directory is
`/Users/ghj/work/auraboot/.workspace/evidence/maritime-parallel-core/ui-integration-20261027`:
`command.json`, `targeted.log`, `targeted-results.json` and `exit.json` retain the
actual command, test names/status and exit code. Anchorage's
`reports/maritime-runtime-integration/ui-integration/source-and-tests.json` records
their digests and all 78 source hashes. Initial offline dependency installation
failed because the isolated store lacked packages; normal frozen-lock installation
then succeeded without a lock change. Coverage was not measured.

Anchorage independently admits the original producer's eight Web artifacts while
retaining its thirteen other backend/API/BPM/configuration artifacts. This does
not reattribute the existing Kafka runtime bytes to the UI integration commit.
Native product construction, browser journeys, visual review and business effects
require their own exact-source receipts. No main merge or production deployment.
