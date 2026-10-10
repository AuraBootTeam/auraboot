# WeChat mini-program infrastructure

Dependency-free CommonJS runtime for native WeChat projects, with separate Node-only verification tools. Product adapters own business endpoints, envelopes, user messages and cache invalidation. This package does not deploy products or provide a business page renderer.

## Consume a pinned copy

```sh
node packages/wechat-mini-runtime/tools/materialize.cjs /absolute/product-checkout
node --test packages/wechat-mini-runtime/tests/*.test.cjs
```

The generator writes `mini-program/vendor/wechat-mini-runtime` and `scripts/vendor/wechat-mini-runtime/{runtime,tools}` with version and SHA-256 receipts. Never edit generated files. Runtime code does not load Node tooling or depend on another checkout. The tools copy excludes the materializer itself.

## Runtime API

```js
const runtime = require('../vendor/wechat-mini-runtime/index');
const logger = runtime.createLogger({ wxApi: wx, namespace: 'product' });
const session = runtime.createSession({ wxApi: wx, tokenKey: 'product_token', invalidate: clearProductCaches });
const request = runtime.createHttp({
  wxApi: wx, session, logger,
  baseUrl: () => runtime.target(config),
  error: code => productError(code),
  decode: decodeProductEnvelope,
});
App({ ...runtime.globalHandlers(logger) });
```

`createHttp` sends W3C `traceparent`. All request lifecycle events carry one traceId, plus the HTTP spanId. No independent requestId is generated. A different response `X-Trace-Id` emits `trace:mismatch`; a missing response header is not proof of server correlation. Each HTTP attempt gets a fresh trace. Trace identifiers use portable random generation for correlation only; they are not credentials, cryptographic nonces or business idempotency keys. An injected random source is available for deterministic testing.

Request options: `anonymous` omits the token; `authRequired` rejects a missing token; `route` supplies a static route template for endpoints with opaque path identifiers; `commit(result)` performs identity rotation atomically before response resolution. Product login/tenant-switch adapters should use commit, rather than rotating tokens in a later Promise continuation.

Session replacement/clear increments a generation and invalidates product caches. Old successful responses, stale 401s and ABA token rotations are rejected with SESSION_CHANGED. Current 401 expires only its own session. There is no automatic retry. Products must retain/reconcile unknown business writes using their own idempotency records.

Logger defaults to metadata and summaries. Both sinks use the same redacted record; raw bodies, credentials, names, contact details and exception messages are excluded. Global handlers keep category and a source filename/line when available. Realtime manager acquisition is lazy and may recover after an earlier unavailable/throwing acquisition. Optional sink failures never crash the application; no remote delivery guarantee is implied. The bounded snapshot is in-memory debug evidence.

`target(config)` requires an explicit HTTP(S) origin and simulator/device target. Device loopback is rejected; deviceHealthVerified must be boolean true following an actual connectivity check. `badge(config, miniRuntime, labels)` displays build/environment/target and uses WeChat's version only for official releases, not trial builds. Localized labels belong to the product.

## Node verification API

Load `scripts/vendor/wechat-mini-runtime/tools/index.cjs` outside the mini-program runtime:

- `prepareProject({source,out,config,mode,identity,purpose})`: separate owned output, excludes node_modules/tests/golden, fingerprints source, records fixture/real-stack identity. Test purpose rejects production; explicit release-build permits offline release preparation.
- `preflight({cli,project,port})`: checks project file, bridge port shape and CLI login. It does not terminate occupied ports or establish process ownership.
- `verifyBridge(mp,expectedConfig)`: checks the connected app's miniRuntimeIdentity against origin/runtime/target/version/data mode/source digest. The app must expose that config on launch. A reachable bridge alone is insufficient.
- `monitorConsole(mp,{allow})`: retains redacted records and rejects unallowlisted runtime errors through assertClean; expected platform refusals require a narrow explicit allow predicate.
- `captureEvidence(mp,{directory,scenario,identity,mode,monitor})`: captures a screenshot and SHA-256/console/mode manifest. Its verdict is captured and visualReview is pending, not accepted. It calls assertClean after writing failure evidence.

Screenshots may contain real product data and require appropriate evidence access and retention. The console redactor does not modify screenshot pixels. Products retain their existing journey catalog and real-tap assertions.

## Verification boundary

Package tests are hermetic behavior tests using independent wx callbacks and filesystem fixtures. Product fixture-native checks are simulator UI checks. Neither proves real WeChat phone authorization, realtime-log ingestion or BFF/backend trace propagation. Verify those boundaries against an isolated, owned runtime and record them separately.
