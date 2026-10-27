---
type: system-reference
status: active
created: 2026-10-27
updated: 2026-10-27
---

# Structured plugin command rejections

Plugin command handlers and invocation authorizers can reject a business request
with `PluginCommandRejectionException`, a transport-neutral plugin API type.
Plugins provide a stable `Code`, a translation key, and optional substitution
arguments. The plugin API does not depend on host HTTP exceptions.

Both `INVALID_ARGUMENT` and `BUSINESS_RULE_BLOCKED` currently map to the host
`ResponseCode.BadParam`. The host preserves the translation key as
`$i18n:<key>`, the arguments, and the original cause. The request exception
boundary resolves the localized message. Plugins must register their message
catalogs; a diagnostic exception message is not a localization contract.

`HandlerPhase` translates rejections raised by the handler. Invocation
authorization can reject before that phase, so `CommandExecutorImpl` also
translates the same structured type at its failure boundary. These early
rejections must not acquire an English diagnostic prefix that prevents the
request boundary from resolving the translation key. Classification uses the
exception type and code, not a message substring.

The normal failure metrics and audit run before this translation. The original
phase and failure key remain available for diagnosis. Dry-run audit suppression
and existing access-denied, conflict, validation, and persistence exception
handling retain their separate contracts. A structured rejection does not
grant authorization, start another command, or retry an external operation.

For asynchronous handlers, task completion and business completion are distinct.
Callers should present the handler's domain outcome and refresh the authoritative
business record. A completed command task alone does not establish an external
payment, delivery, or other business terminal state.

The synchronous pre-handler translation is exercised through
`CommandExecutorFailureAuditTest`, including both codes, substitution arguments,
cause preservation, response classification, and failure audit phase. Async
translation is covered by `CommandHandlerAsyncTaskExecutorTest`.
