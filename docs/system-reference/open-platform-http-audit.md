---
type: system-reference
status: active
created: 2026-11-24
---

# Open Platform HTTP audit and navigation

The authenticated backend menu tree includes a system navigation group for Platform Management. Open Platform links select applications or Webhook deliveries on the existing settings route. Platform Console is visible only to users with the platform operator role in a platform space; its navigation action posts to the existing space-switch action before redirecting to `/platform/plugins`. The platform route reuses the existing read-only kernel plugin inventory (backend plugin lifecycle management retains its own permissions) instead of falling through to an unconfigured dynamic page. Navigation visibility does not grant application access. Account menus contain identity actions, About and Sign out.

## Capture contract

`OpenApiRequestIdFilter` wraps `/api/open/v1/` traffic with bounded request caching and a streaming response tee. The existing authenticator creates the audit row and attaches its trusted row/tenant/installation identity. After the response finishes, the outer filter stores `http_details` against all three identifiers. Unattributable authentication failures retain their existing metadata-only behavior. OAuth token exchange bodies are never captured.

Each body has a separate `captured`, `empty`, `not_captured` or `truncated` state. At most 64 KiB of each raw body is held in request-local memory. Complete JSON is parsed and recursively redacted before persistence. Oversize bodies are marked truncated and have no persisted partial body because partial JSON cannot be reliably redacted. Only JSON objects and arrays are stored; scalar JSON is unstructured and omitted. When a consumed request cannot be safely redacted (malformed, unstructured, unsupported or oversized), an otherwise captured response body is also omitted to prevent exception echo leaks. Unsupported content types, malformed JSON, unread rejected request bodies and incomplete responses explain why content is unavailable. Binary and streaming content is not represented as invented JSON.

Sensitive key names, authorization/cookie headers, known incoming secrets, email addresses and phone numbers are redacted. Only allowlisted HTTP headers are retained. Query parameters use the same redaction policy and an independent bound. Raw cached bytes and credentials are never written to logs or audit storage. Arbitrary opaque business fields are not a substitute for a domain-specific data classification policy; integrations must avoid sending sensitive values under misleading field names.

Flyway adds a nullable JSONB `http_details` column. NULL means the historical record has no capture; migration does not manufacture past payloads. Existing audit retention removes the row and its HTTP details together. Capture does not replay business actions and the response tee forwards original response bytes.

## Query and authorization

`GET /api/open-platform/installations/{installationPid}/audits` retains the list response contract and adds `auditPid` per row. Optional parameters: `requestId` (exact), `status` (exact), `keyword` (literal substring of request ID/path), `method`, `statusClass=all|success|error`, `windowHours=1..720`, `limit=1..200`, and `offset=0..10000`. Consumers can use an extra row as a next-page indicator; a subset count is not a total. List queries omit HTTP payloads.

`GET /api/open-platform/installations/{installationPid}/audits/{auditPid}` returns one authorized detail. Summary access retains `canReadOperations`. HTTP detail additionally requires `canManageRuntime`: application owners, maintainers or the existing platform administrator authority. Viewers can inspect summaries but cannot fetch or download bodies. Nonmembers and cross-tenant/cross-installation records cannot resolve through the endpoint. Missing details return 404.

## Viewer behavior

The shared `HttpAuditInspector` remains available for contextual integration diagnostics, exposing independently captured request/response content, redacted clipboard/download and credential-free cURL templates. The backend capture and detail APIs remain active.

The duplicate standalone call-log workspace, sidebar entry and top tab have been removed. Legacy `/settings/api-docs?view=calls` bookmarks redirect to `view=applications`. The existing integration/outbound module owns the user-facing entry; this change does not claim that a new integration-module consumer has been implemented or verified here. Account locale/timezone and the shared inspector's availability states remain unchanged.

The existing Webhook operations panel remains available in a separate tab, including event catalog, health and replay controls. The audit improvement does not expand its authority or change replay semantics.

Related implementation checks are in `OpenApiHttpCaptureTest`, `OpenPlatformAuditAuthorizationTest`, `PlatformManagementNavigationTest`, and `web-admin/tests/e2e/settings/open-platform-navigation.golden.spec.ts`. Earlier standalone-workspace browser evidence is historical, not current UI coverage. Test presence does not imply execution; exact run evidence is reported separately.
