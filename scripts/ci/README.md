# Open Platform probe and receiver tools

Run the tool self-tests, including an invalid-signature mutation and restored validation:

```sh
node scripts/open-platform-canary-tools.test.mjs
node scripts/open-platform-release-image-contract.test.mjs
```

`open_platform_http.py` supplies the transport shared by the release-image probe and future
deployed-target canaries. Importing either module does not contact a target or provision data.
The transport rejects redirects, cross-origin paths, invalid JSON responses and credential-bearing
origins; it bounds requests and response bytes, stops on HTTP 429, and never automatically retries.
Error messages omit response bodies and credentials. The release-image probe still provisions
its existing CI-only fixtures when explicitly executed; it is not a deployed-target canary.

## Independent webhook receiver

Create a private secret file containing the exact webhook secret (no secret on the command line).
Choose a new run ID, a private evidence directory and an unused loopback port:

```sh
python3 scripts/ci/open_platform_receiver.py \
  --secret-file /private/receiver/secret \
  --store /private/receiver/deliveries.sqlite \
  --run-id canary-unique-run --port 18765 --mode accept
```

Place an approved HTTPS reverse proxy in front of `127.0.0.1:18765`. Forward only
`/webhooks/canary-unique-run` without transforming the body or signature headers. The receiver
has no remote configuration endpoint and is separate from the platform application process.
Do not expose the SQLite file, secret, or raw delivery evidence through the proxy.

It verifies HMAC-SHA256 over `timestamp + '.' + rawBody`, a five-minute timestamp window and
constant-time comparison. Accepted event IDs are persistently deduplicated; reuse of an event ID
with different raw bytes returns 409. Raw signed bodies, signature headers, timestamps and delivery/request IDs are recorded in
a private SQLite store. Invalid requests retain only diagnostic hashes and do not store their bodies.
Retain the store for review; restarting with a different run ID, secret or failure policy is rejected.
Legacy stores lacking raw signature columns are rejected without modification; choose a fresh run/store.

The fixed modes are `accept`, `fail-first --failures N` and `fail-all`. For a retry case choose a
failure count below the platform's actual attempt limit. For successful DLQ replay choose a count
equal to its actual initial attempt limit, wait for the platform's recorded dead-letter state, then
use its real replay API: the next delivery may succeed without changing receiver configuration.
`fail-all` supports permanent failure evidence. Neither a receiver 200 nor a local self-test
proves platform retry, queue drain, DLQ, replay, Automation consumption or a complete canary.

## Verification scope

The self-test entry executes 20 checks, including a real loopback HTTP receiver process, persisted
deduplication after restart, raw-byte signatures, time boundaries, conflicts, failure modes,
transport budgets and secret-safe diagnostics. It then injects a test-process-only signature
comparison defect, requires the signature/tampered-body assertions to fail, and runs the restored
checks again. No product source is modified for the mutation.

These checks validate the tools. They do not run browser E2E, OAuth against a deployed platform,
or the twelve deployed-target canary contracts. Release-image execution remains a separate
Linux release validation using its actual source and artifact identity.

## Deployed-target protocol slice

`open_platform_canary.py` executes the first nine protocol contracts against a real TLS target.
It requires a private JSON fixture file and private password files for two real accounts in
distinct tenants. Both tenants must already contain the approved Asset/Inventory models;
the runner does not install templates or use `/api/test/seed`. It creates fresh, run-named
applications, credentials and records through management APIs and retains them as evidence.
Revocation and installation disabling affect only the installation created by this run.

Fixture fields are `schemaVersion: 1`, a fresh `runId` (8–48 lowercase letters/digits/hyphens),
`origin` (HTTPS origin), `auth` and `foreign` (each has `identifier`, `passwordFile`, `tenantId`),
and `targetIdentityEvidence` (path to the controlled deployment identity evidence).
The identity file is hashed as an input; this slice does not independently attest its truth.

```sh
python3 scripts/ci/open_platform_canary.py \
  --fixture /private/canary/fixture.json --out /private/canary/protocol-receipt.json
```

The output is reserved with exclusive creation **before any target writes**. Credentials are
redacted; requests carry run-bound request IDs. HTTP budgets, no redirects, no automatic retries
and stop-on-429 are inherited from the shared transport. An assertion or transport failure exits 1.
Successful execution of this slice exits **2**, records `PARTIAL`, and retains all twelve rows:
optional chains remain untested unless explicitly selected and actually executed; complete
request-ID propagation remains unverified. No caller
may translate exit 2, nine passed rows, an input identity hash or these safety self-tests into full
canary acceptance. Browser E2E is not executed.

Set fixture `includeExternalEvent: true` to additionally drive real ingress, duplicate/conflict,
enabled Automation consumption and a fresh downstream Asset artifact. This requires an installed
workflow product providing `automation.deploy` and `automation.run`; the Core adapter alone does
not execute Automation. The runner observes the exact event/automation/tenant log and real public
artifact, then checks the ingress request in installation audit. The durable event ID is labelled
as expected, not observed. Bounded status polling is observation, not HTTP request replay. Missing
capability or failed/empty consumer execution cannot pass this row. Independent target identity is still unverified, and the result remains PARTIAL/exit2.

Set `includeWebhook: true` and provide `receivers` with exactly `accept`, `retry`, and `replay`.
Each has `url`, `secretFile` and `store`. URLs must use TLS and the exact path
`/webhooks/<runId>-<name>`. Run three independent receivers with the corresponding run IDs:
`accept`, `fail-first --failures 1`, and `fail-first --failures 3`. Stores must be locally readable
private files from those processes, not evidence downloaded from the platform. Secret files must
contain the exact UTF-8 secret; only trailing CR/LF is removed, matching the receiver CLI.

This driver creates three run-scoped subscriptions, triggers one fresh Asset command, observes
accept/retry/dead-letter queue states, then calls the real replay API. The independent evidence
reader recomputes HMAC, raw-byte checksum, timestamp window, event/subject identity and exact
attempt statuses (200; 503/200; 503/503/503/200). It also sends the original bytes to the receiver
for explicitly labelled deduplication, invalid-signature and expired-timestamp checks. The target
request budget is 250; receiver checks have a separate budget of three; neither automatically retries.

Selecting both optional chains requires the originating command request ID in the durable queue
and every independent receiver attempt, including retry and replay; event consumption must persist
its actual ingress request ID in the Automation trigger log. Audit/resource/event/delivery joins
alone cannot pass CANARY-TRACE. Older images missing the new durable request-ID field or header
fail the assertion. Subscription custom headers cannot replace the persisted platform ID.
Even twelve protocol passes do not attest deployed source/image identity: the result stays PARTIAL/exit2.

The same self-test entry additionally runs thirteen hermetic runner safety checks, injects a false
completion verdict that must fail, and verifies restoration. They test tooling safety and reporting,
not the deployed business paths; those require actual target execution and final full-scope evidence.
