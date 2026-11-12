---
type: system-reference
status: active
created: 2026-11-07
updated: 2026-11-07
---

# Command input form sources

This contract extends `command.execute` in ActionRegistry and the string-verb workbench action dispatcher. It preserves existing static `inputFields` behavior. Legacy `type: command`/bulk/table adapters are not consumers of this option.

A command may declare `inputFieldsSource: { endpoint, payloadBindings? }` instead of `inputFields`. The endpoint is a same-origin `/api/` GET resolved at the original click. It returns an object with a nonempty `inputFields` array and any scalar snapshot values referenced by `payloadBindings`. Standard Result envelopes are accepted. Bindings map top-level command payload keys to explicit dotted response paths, for example `{ expectedRevision: "revision" }`; they never modify command envelope identity, operation, authentication or request-key options.

The original payload, display context and page lifetime are captured before this read. Successful metadata opens the shared FormDialog and continues the original command pipeline, including `inputFieldsPayloadKey` and `waitForResult`. Metadata defaults are already business values and must not be evaluated as DSL expressions again.

Missing/failed/malformed metadata, empty or duplicate fields, missing bound values, conflicting static fields, unsafe paths and cross-origin endpoints reject before command dispatch. Leaving the original page cancels the pending read and prevents a late prompt or write. Transport diagnostics are not public UI text. Configuration validation rejects unknown source keys and prototype paths. Cancellation sends no command. Current server-side command authorization, revision checks and business validation remain authoritative.

Verification must exercise both dispatchers, static-input compatibility, metadata GET before exactly one POST, exact payload and snapshot binding, default-value non-evaluation, cancellation, route abort, failures, malformed metadata and unsafe configuration. Product browser evidence is required separately; hermetic executor tests are not an accepted product journey.
