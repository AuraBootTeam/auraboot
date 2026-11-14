# Anchoring service types to the generated OpenAPI contract

`app/generated/internal-api/schema.d.ts` (~84k lines, tracked with a
`Contract SHA-256` header) is generated from the backend's OpenAPI contract.
Historically almost no service consumed it — request/response types were
hand-copied, so backend changes surfaced at runtime instead of compile time.

## The pattern (2026-11-14 pilot)

Keep the handwritten view interfaces as the module's public surface (consumers
are unaffected), but wire the contract in as the compile-time check:

```ts
import type { components as InternalApi } from '~/generated/internal-api/schema';

type ContractInboxItem = InternalApi['schemas']['InboxItemResponse'];

// Fails the build when a field the view promises disappears from the contract.
type AssertContractKeys<T, K extends keyof T & string> = K;
type _InboxItemFieldsExist = AssertContractKeys<ContractInboxItem,
  'id' | 'itemType' | 'title' /* ... every field the view exposes */>;

// Fetch generics use the contract type; cast to the view at the boundary
// (the contract types everything optional; the backend materializes it).
const result = await fetchResult<ContractInboxPage>('/api/inbox', { method: 'get', params });
return requireData(result) as InboxPage;
```

Reference implementations: `app/shared/services/inboxService.ts`,
`app/plugins/core-aurabot/services/shadowRunsApi.ts`, and the older
`app/shared/services/dict-adapter.ts` (full conversion style).

## When you add or touch a service

1. Check whether the endpoint exists in the generated contract
   (`grep '"/api/your-endpoint"' app/generated/internal-api/schema.d.ts`).
2. If yes: use the pattern above — never hand-copy the type again.
3. If no: the controller is outside the OpenAPI scan scope; note it in the
   service header so the gap is visible, and consider adding the controller
   to the scan (backend-side) rather than extending the hand-copy.

## Coverage note

The contract covers 1,392 endpoints, but many `app/shared/services/*` call
endpoints outside the scan scope (stores/feedback/menu/dashboards/... were
absent at pilot time). Closing that scan gap is backend follow-up work; the
anchor pattern applies to whatever the contract does cover.
