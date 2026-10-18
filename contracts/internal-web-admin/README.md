# Internal Web Admin API contracts

`openapi.json` is the normalized `internal-web-admin` Springdoc catalog. It is
derived from backend controllers, DTOs and serialization annotations. It is a
producer catalog, including operations whose frontend consumers still need
reconciliation; the presence of a schema does not certify that every client has
migrated. Published machine APIs use the separate Open Platform contract.

The generated declarations are in
`web-admin/app/generated/internal-api/schema.d.ts`. Do not edit either generated
file to resolve a mismatch. Correct the producer contract or the consuming adapter
and regenerate. Pure editor state remains a frontend type; wire types reference
the generated schemas or operation inputs.

## Toolchain

The generator has a separate exact-version lockfile. It does not change the
application's HTTP runtime or add a browser dependency.

```sh
npm ci --prefix scripts/internal-api/toolchain --ignore-scripts
node --test scripts/internal-api/*.test.mjs
```

## Export and generate

Start and verify the managed OSS runtime for the exact source checkout first.
Pass its backend address explicitly. Store receipts in the runtime's registered
evidence directory, using a new filename for every attempt.

```sh
node scripts/internal-api/export.mjs \
  --url http://127.0.0.1:BACKEND_PORT/v3/api-docs/internal-web-admin \
  --output contracts/internal-web-admin/openapi.json \
  --receipt ABSOLUTE_EVIDENCE_DIRECTORY/export.json --source .
node scripts/internal-api/toolchain/generate.mjs \
  contracts/internal-web-admin/openapi.json web-admin/app/generated/internal-api/schema.d.ts
```

Export requires a clean source checkout when `--source` is provided. The export
receipt certifies export and source capture only, not business or migration
acceptance. Root/path/operation server origins become relative; schema fields,
requiredness, nullability, security requirements and array order are preserved.

## Drift and consumer checks

```sh
node scripts/internal-api/toolchain/generate.mjs \
  contracts/internal-web-admin/openapi.json web-admin/app/generated/internal-api/schema.d.ts --check
node scripts/internal-api/export.mjs \
  --url http://127.0.0.1:BACKEND_PORT/v3/api-docs/internal-web-admin \
  --check contracts/internal-web-admin/openapi.json \
  --receipt ABSOLUTE_EVIDENCE_DIRECTORY/live-drift.json --source .
node scripts/internal-api/discover.mjs . contracts/internal-web-admin/openapi.json \
  ABSOLUTE_EVIDENCE_DIRECTORY/client-inventory.json
pnpm --dir web-admin typecheck
```

Discovery is a static candidate inventory, not an endpoint-coverage percentage.
Unmatched operations, unresolved methods/base URLs, local request wrappers,
configured runtime endpoints and absolute origins require explicit reconciliation.
Do not exclude them merely to obtain a green report. A generated-operation marker
records source migration, not executed test evidence.

## Typed JSON operations

`internalRequest(method, template, input)` binds method, path, query, headers,
JSON body and response data to the generated operation. It reuses the current
HTTP client, BFF/session handling, timezone headers, authoring guard, cancellation
and normalized result. `success` and `desc` are client projections, not backend
envelope fields.

Path values are encoded once. Query arrays use OpenAPI form/explode encoding
(repeated query names); JSON filter strings remain strings. The generator rejects
unsupported query styles instead of silently applying a different serialization.
Explicit JSON bodies preserve empty objects, arrays, null and scalars. Legacy
`params` behavior remains available while consumers migrate.

This JSON-envelope client does not model a file or an event stream as JSON.
Binary, multipart, streaming and configured dynamic transports need their own
operation-specific boundary and runtime evidence. Type generation does not
replace serialization, permission, persistence or browser journey tests.

## Datetime formats

`date-time` identifies offset-bearing timestamps such as `Instant`. Default Jackson
`LocalDateTime` properties use the custom `local-date-time` format: ISO local date
and time, with optional fractional seconds and no offset. Explicit field serializers
and custom patterns retain their own contract. A local datetime does not itself
identify a timezone. UTC metadata fields converted by the platform's UTC mapper
must be displayed through the shared timezone formatter rather than interpreted as
browser-local wall time.

## CI and release checks

Frontend CI runs `foundation.mjs --evidence <fresh-directory>` after installing the
locked toolchain. It requires the exact registered unit test set, canonical snapshot,
current generated types, and standalone declaration compilation; empty, missing,
skipped, repeated or unexpected test evidence fails. Its receipt claims offline
consistency only. It does not certify domain migration or browser acceptance.

Docker Quickstart compares the actual built backend's group against the snapshot.
The existing Open Platform release-image runner additionally checks this same internal
contract from its retained release container and includes both drift and foundation
receipts in its release receipt. `--archive-source <SHA>` is restricted to git-free
archives: the caller owns the frozen archive and image provenance, while the runner
checks its scoped input bytes remain unchanged. This update does not independently
qualify a release; the complete existing image gate must execute successfully on the
admitted Linux CI host.
