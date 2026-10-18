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

The inventory includes JavaScript/TypeScript in the application and workspace
packages. Its source graph follows the shell, router, SSR/CSR and BFF entries,
configured source aliases, router resource files and package exports. Type-only
imports remain type candidates. Dynamic imports, virtual contributions, JSON/DSL
resources and unknown export conditions remain explicit boundaries. A file not
statically linked to an entry is not automatically excluded. Local collections
such as a `Map` cache retain candidate rows with declaration evidence instead of
being mistaken for HTTP calls or silently removed.

Route alternatives retain a separate result for every finite conditional branch.
A missing, external or unresolved branch prevents the whole call from being
classified as linked; enumeration overflow stays unresolved. Calls through local
aliases, bound fetch functions and typed injected fetch parameters remain in the
inventory. Mutable aliases and replaceable injected operation defaults do not
prove generated-operation migration. These static facts do not establish wire
type propagation or runtime branch coverage. Replaceable route defaults, mutable
values and instance base fields retain candidate matches with declaration evidence;
they require explicit reconciliation before being counted as proven source links.

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

The shared product release-image gate uses `drift.mjs --core-operations` after
the product's own initialization, publication and artifact identity checks. It
compares every snapshot operation and its full referenced component/security
closure, allowing unrelated product operations and components. Core path,
parameter, DTO, nullability and security changes still fail. This mode certifies
Core compatibility; it does not certify the additional product API contract.
OSS Quickstart and the standalone Core image gate retain full-document comparison.

`scripts/ci/capture-internal-document.py` authenticates only to the admitted
loopback image using explicit environment credentials. It captures documentation,
revokes its issued session and requires a subsequent identity request to return
401 before writing a success receipt. It never initializes an existing product
database, follows login redirects, or persists credentials. The image receipt
binds the capture/comparison hashes to the Core commit and loaded image identity.
Host capture checks and hermetic tests do not substitute for Linux image execution.

## JSON response structure evidence

`response-shape.mjs` checks an observed JSON response against its declared
operation, HTTP status, media type and local-reference schema using the locked
JSON Schema 2020-12 validator. It rejects missing required fields, incorrect
scalar/array types and nulls that the producer did not declare. Error responses
are checked against their own status declaration. Its claim is
`json-response-structure-only`: format validation and business invariants need
separate assertions. A permissive or optional producer schema remains a producer
contract gap; a passing structural check cannot prove a complete API contract.

## Dictionary lifecycle queries

The administrative dictionary list includes non-current versions so that an
unpublished dictionary remains available for management and deletion. Disabled
versions remain excluded from the normal management list. Version, status and
PID identify the selected record; unpublish changes status to `deprecated` and
clears its current-version flag. A batch deletion response is the actual number
deleted and may be zero for protected published records.

## Registered browser evidence

The offline foundation independently enumerates `tests/e2e/**/internal-api-*.spec.ts`
and `tests/api/internal-api/*.spec.ts`. It parses their Playwright test declarations,
literal scene captures and mandatory JSON attachments, then reconciles that source
registry with `scripts/internal-api/catalog.json`. Missing tests, empty evidence
bodies, duplicate scenes, dynamic titles and skip/only declarations fail. The
registry is the denominator for these registered journeys, not for all Web Admin
APIs. Archive-mode foundation also includes the spec input bytes in its digest.

After the exact committed runtime is fully started and verified, load its managed
environment and run a fresh attempt outside the source checkout:

```sh
eval "$(scripts/oss-golden-stack.sh env RUNTIME_NAME)"
node scripts/internal-api/browser.mjs run \
  --evidence ABSOLUTE_FRESH_ATTEMPT_DIRECTORY \
  --manifest ABSOLUTE_RUNTIME_PRODUCT_MANIFEST \
  --runtime-command ABSOLUTE_WORKSPACE_AURA_COMMAND --runtime RUNTIME_NAME
```

The runner requires a clean checkout, matching Web/BFF/backend/database bindings,
and successful managed runtime verification before and after execution. It saves
source/tree, contract/type/toolchain/catalog/registry digests and the exact runtime
manifest before running all registered tests without dependencies, retries or skips.
The attempt directory and credentials-bearing traces remain private. Its explicit
owned-fixture mode disables the broad shared-data teardown only for the contract
profile; each journey remains responsible for its owned records and fresh sessions.

`functional-catalog-receipt.json` requires every registered test exactly once,
mandatory nonempty observations, passing response structures, no recorded journey
or cleanup errors, and each scene's annotation, attachment and original PNG bytes.
It retains `visualVerdict: pending`. It is not a release-qualification receipt.

Individually open every original, then supply a review with `sourceCommit`,
`reportSha256`, `catalogSha256`, and one `rows` entry per scene containing `id`,
`screenshotSha256`, `verdict: pass`, `reviewMethod: individual-original-image-opened`
and a concrete `finding`. Collect it separately:

```sh
node scripts/internal-api/browser.mjs collect \
  --evidence ABSOLUTE_ATTEMPT_DIRECTORY --review ABSOLUTE_ORIGINAL_REVIEW_JSON
```

A missing, duplicate, stale, mismatched or unreviewed image prevents a reviewed
receipt. Evidence paths must remain inside the attempt, including symlink targets.
Functional and visual receipts preserve the actual executed commit. They do not
certify unregistered domain branches, global absence of session residue, JSON
formats, or the separate admitted Linux release-image gate.
