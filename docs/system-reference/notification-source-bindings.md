---
type: system-reference
status: active
created: 2026-10-23
updated: 2026-10-27
---

# Notification source bindings

`aura.notification.source-bindings` is deployment configuration, not a plugin setting or caller approval. It defaults to disabled with no bindings. `aura.notification.plugin-delivery.enabled` independently controls delivery admission. The registered-source polling worker also defaults to disabled and requires explicit positive bounded `poll-interval-ms`, `max-sources-per-binding`, and `max-bindings` values. It discovers only registered scopes; it does not establish a product recipient policy.

## Descriptor and configuration binding

Each binding identifies an exact tenant, code, rule ID, template code, approved metadata graphs, source, owner, principal and verification models. Conditions retain explicit STRING or BOOLEAN scalar types. Targets can require an unexpired deadline or a bounded complete child scan with remaining quantity. Rule, template, source facts and unique active account/contact ownership are revalidated before enqueue and delayed consumption.

Spring configuration uses the canonical constructors of `Exact` and `Target`; Java convenience overloads do not select an alternate deployment format. Omitted optional `native-field-pids` and `variable-to-source-field` collections become empty collections, as the descriptor contract permits. Required identities, approvals, scalar types and fields remain mandatory. Unknown fields and approval flags are rejected. Empty optional collections do not approve metadata or grant authority. Native field identifiers are an unordered set and have a sorted immutable serialization; JVM-specific set iteration must not change a delivery configuration version. Ordered owner/target lists retain their declared order.

Properties use kebab case and indexed lists, for example `bindings[0].source.entity.equals[0].field-code`, `.value.type=STRING` and `.value.value=pending`. Metadata approval must use reviewed actual imported model/field/binding/plugin identities and a canonical shape digest. An uploaded package or self-signed caller digest is not approval. An imported security-graph change invalidates the old approval until an explicit deployment transition. The shape excludes the existing audit timestamp fields, the tracking row’s `import_id` and the plugin row’s `enabled_at`. The latter two identify an import execution and activation time, not a security definition. Model/field/binding facts, tracking owner/resource identity, plugin enabled state/version and unknown security extensions remain covered. This normalization does not approve a package generation; existing older digests must be explicitly regenerated, never silently repaired.

## Execution and evidence boundaries

Polling starts after application readiness. Planning, enqueue and delayed consumption retain host transaction and tenant boundaries; validation and delivery errors stay visible. Replaying an unchanged accepted source is deduplicated by the existing delivery ledger. An outbox receipt means an intent was persisted, not that a phone received a message. Poll failures expose only bounded codes for invalid bindings, envelope conflicts, blocked delivery, transaction conflicts or other processing failures; arbitrary exception text is not copied into diagnostics. A changed or legacy noncanonical configuration must not overwrite an accepted event or manufacture a new key for the same business occurrence; operational test scope transitions retain completed events and use fresh scheduled source occurrences.

Products own schedules, recipient policy, templates, subscription grants and device acceptance. An explicit-account, exact-slot test registration proves only that scope; it does not establish production recipients or authorize WeChat sending. Readiness requires actual Spring property binding and a real-stack happy path; constructing descriptors directly in unit tests is insufficient.

## WeChat field sources

Deployment-owned reservation routes can map template fields to `TITLE`, `CONTENT`, `SOURCE_ID` or `OCCURRED_AT`. `TITLE` and `CONTENT` use the rendered, authorized source plan. `SOURCE_ID` remains an internal source record identifier; it must not be presented as a purchase order or inquiry number. `OCCURRED_AT` uses the plan's immutable source occurrence instant, formatted as `yyyy-MM-dd HH:mm:ss` in `Asia/Shanghai`. It is notification occurrence time, not order creation, shipment, worker execution or provider delivery time. A missing occurrence blocks the reservation before consuming quota. A changed occurrence cannot rewrite an existing reserved field snapshot.

Named template identifiers are case-sensitive. Use bracketed Spring map keys, such as `templates[Owned-Template_A].field-max-lengths[date4]` and `reservations.routes[order].field-sources[date4]=OCCURRED_AT`, to preserve the provider identifier and exact field names. Limits remain explicit per field; field mappings do not truncate values. The deployment must select provider fields whose business meanings match the source facts. A template title alone does not prove compatibility.

`wechat-subscription.preferences.enabled` can expose an explicit self-scoped catalog independently of dispatch. Configuring a catalog, adding a provider template or recording a client preference does not create provider consent, register source bindings, approve recipients or enable sending. Those admission boundaries remain independent and default off.

## Bounded worker progress

Successful nonempty polling emits an identity-free summary at most once per monotonic minute per adapter instance: attempted bindings, selected/matched sources and processed plans. Empty, disabled, busy or failed ticks do not emit a successful progress summary; failure diagnostics remain separate. This proves an actual selection/processing attempt, not downstream delivery or phone receipt. Combine it with immutable source facts, outbox/consumer receipts and recipient persistence for replay qualification. The summary contains no tenant, supplier, source identity, message content or arbitrary exception text.

Subscription preferences resolve the current active account and its unique mini-program identity from the host context. Identity PIDs are opaque identifiers generated by the identity service; a numeric IdWorker PID must not be rejected for failing a fixed ULID length. Missing or blank identity PIDs, foreign user/application/provider records and missing openids remain invalid.
