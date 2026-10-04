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
with different raw bytes returns 409. Raw signed bodies and delivery/request IDs are recorded in
a private SQLite store. Invalid requests retain only diagnostic hashes and do not store their bodies.
Retain the store for review; restarting with a different run ID, secret or failure policy is rejected.

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
