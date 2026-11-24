---
type: system-reference
status: active
created: 2026-11-24
---

# Open Platform HTTP audit and navigation

The authenticated backend menu tree includes a system navigation group for Platform Management. Open Platform links select applications, API calls or Webhook deliveries on the existing settings route. Platform Console is visible only to users with the platform operator role in a platform space; its navigation action posts to the existing space-switch action before redirecting to `/platform/plugins`. Navigation visibility does not grant application access. Account menus contain identity actions, About and Sign out.

## Capture contract

`OpenApiRequestIdFilter` wraps `/api/open/v1/` traffic with bounded request caching and a streaming response tee. The existing authenticator creates the audit row and attaches its trusted row/tenant/installation identity. After the response finishes, the outer filter stores `http_details` against all three identifiers. Unattributable authentication failures retain their existing metadata-only behavior. OAuth token exchange bodies are never captured.

Each body has a separate `captured`, `empty`, `not_captured` or `truncated` state. At most 64 KiB of each raw body is held in request-local memory. Complete JSON is parsed and recursively redacted before persistence. Oversize bodies are marked truncated and have no persisted partial body because partial JSON cannot be reliably redacted. Unsupported content types, malformed JSON, unread rejected request bodies and incomplete responses explain why content is unavailable. Binary and streaming content is not represented as invented JSON.

Sensitive key names, authorization/cookie headers, known incoming secrets, email addresses and phone numbers are redacted. Only allowlisted HTTP headers are retained. Query parameters use the same redaction policy and an independent bound. Raw cached bytes and credentials are never written to logs or audit storage. Arbitrary opaque business fields are not a substitute for a domain-specific data classification policy; integrations must avoid sending sensitive values under misleading field names.

Flyway adds a nullable JSONB `http_details` column. NULL means the historical record has no capture; migration does not manufacture past payloads. Existing audit retention removes the row and its HTTP details together. Capture does not replay business actions and the response tee forwards original response bytes.

## Query and authorization

`GET /api/open-platform/installations/{installationPid}/audits` retains the list response contract and adds `auditPid` per row. Optional parameters: `requestId` (exact), `status` (exact), `keyword` (literal substring of request ID/path), `method`, `statusClass=all|success|error`, `windowHours=1..720`, `limit=1..200`, and `offset=0..10000`. The UI requests 26 rows for 25-row pages, using the extra row only as a next-page indicator. It never labels a subset count as the total. List queries omit HTTP payloads.

`GET /api/open-platform/installations/{installationPid}/audits/{auditPid}` returns one authorized detail. Summary access retains `canReadOperations`. HTTP detail additionally requires `canManageRuntime`: application owners, maintainers or the existing platform administrator authority. Viewers can inspect summaries but cannot fetch or download bodies. Nonmembers and cross-tenant/cross-installation records cannot resolve through the endpoint. Missing details return 404.

## Viewer behavior

The existing core-settings page hosts a reusable `HttpAuditInspector`. It exposes request/response headers, query parameters and JSON bodies, with independent availability states. A wider reading mode places request and response side by side; narrow screens use a single column and allow closing back to the list. The display uses account locale/timezone. HTTP 202 is accepted for processing, not final business success. No inferred phase timings are shown.

Copies and JSON downloads use the same already-redacted server detail. The cURL action generates a template using `api.example.com` and `<YOUR_ACCESS_TOKEN>`; it never executes a request or restores credentials. A missing capture cannot enable a payload download. Application/environment changes clear the previous selected record; aborted or outdated requests cannot repopulate another installation's detail.

The existing Webhook operations panel remains available in a separate tab, including event catalog, health and replay controls. The audit improvement does not expand its authority or change replay semantics.

Related implementation checks are in `OpenApiHttpCaptureTest`, `OpenPlatformAuditAuthorizationTest`, `PlatformManagementNavigationTest`, and the real-stack browser spec `web-admin/tests/e2e/settings/open-platform-call-logs.golden.spec.ts`. Test presence does not imply execution; exact run evidence is reported separately.
