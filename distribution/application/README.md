# AuraBoot application composition contract

This directory defines the versioned contract used to assemble an AuraBoot-based application from immutable artifacts.

An application manifest describes the intended runtime, platform contracts, backend plugins, migration sets, frontend contributions, and config import order. The resolver matches every requirement against an explicit artifact catalog and produces an `application.lock` with exact versions, SHA-256 digests, and 40-character source commits.

```bash
pnpm application validate \
  --manifest distribution/application/examples/core-only/app.yaml

pnpm application resolve \
  --manifest path/to/app.yaml \
  --catalog path/to/artifact-catalog.json \
  --artifact-root path/to/staged-distribution \
  --output path/to/application.lock

pnpm application verify-lock \
  --lock path/to/application.lock \
  --manifest path/to/app.yaml \
  --expected-identity sha256:<externally-locked-digest>

pnpm application verify-artifacts \
  --lock path/to/application.lock \
  --artifact-root path/to/staged-distribution

pnpm application retain-definitions \
  --lock path/to/application.lock \
  --artifact-root path/to/staged-distribution \
  --definition-store /var/lib/auraboot/application-definitions

pnpm application graph \
  --manifest path/to/app.yaml \
  --lock path/to/application.lock \
  --artifact-root path/to/staged-distribution \
  --mode artifact \
  --target client \
  --output path/to/artifact-graph.json

pnpm application graph \
  --manifest path/to/app.yaml \
  --source-map path/to/source-map.json \
  --mode source \
  --target client \
  --output path/to/source-graph.json

pnpm application compare-graphs \
  --source-graph path/to/source-graph.json \
  --artifact-graph path/to/artifact-graph.json
```

Release manifests and catalogs must not use `SNAPSHOT`, `latest`, workspace links, branch references, sibling source paths, or mutable artifact identities. `verify-artifacts` requires every lock entry to name a staged `localPath` and fails if any bytes do not match the locked digest.

`retain-definitions` first verifies the complete staged release, then atomically retains only `application.lock` and
its checksum-verified `config` artifacts under `<definition-store>/<lock-identity-without-prefix>/`. Configure the
Runtime with `aura.application.definition-store` pointing at that parent directory. Repeating the command verifies
the existing immutable entry; it never overwrites a different or damaged entry. Retention policy must keep every
lock identity still referenced by a tenant binding.

Publish a product-generated `release-registration.json` through the controlled API sequence:

```bash
export AURA_RELEASE_TOKEN='<platform-admin JWT>'
pnpm application publish-release \
  --base-url https://platform.example.com \
  --application-code aura-edu \
  --application-name 'Aura EDU' \
  --registration path/to/release-registration.json \
  --receipt path/to/application-release-receipt.json
```

For an existing stable channel, add `--expected-stable-version <current-version>`. The command writes a mode-0600
receipt before the first request and reuses its publication and stable operation IDs after an interrupted run. A
completed receipt is create-once for the exact API URL, application, registration bytes, and expected channel
version. Use `--token-file` instead of `AURA_RELEASE_TOKEN` when the credential is mounted as a protected file;
tokens are never accepted as command-line values or written to the receipt.

`verify-lock` 检查外层与组成图摘要、连续节点顺序、节点唯一性、前端owner及图/制品清单的一一对应。
`--manifest` 将lock绑定到声明内容和完整依赖分母；默认要求镜像，开发期明确省略镜像时使用
`--skip-image`。`--expected-identity` 必须取自可信Freeze/发布记录，不能临时从待验证lock抄取。
未提供这两个参数时只验证lock自身一致性；通过不代表发布准入、制品字节可信或租户可以绑定。

Every product npm package must contain `package/auraboot.contribution.json` conforming to
`web-contribution.schema.json`. Routes declare whether they are rendered pages or resource/BFF handlers and whether
they use the application shell or a standalone shell. Static assets declare an exact package-relative source,
public mount, and cache policy. Route and registry entries name public package export paths; source-tree-relative
imports and Web Shell internals are not part of the contract. Artifact mode reads this manifest from the locked,
checksum-verified tarball. Source mode reads the same file from roots declared in a local-only source map:

```json
{
  "packages": {
    "@auraboot/aura-crm-web": "../aura-crm/packages/web"
  }
}
```

The graph builder rejects duplicate route IDs, route paths, registry owners, asset IDs, overlapping asset mounts,
plugin codes, activation cycles, and incompatible React/React DOM/router/Plugin SDK peer ranges. Routes, registry
entries, and asset mounts all participate in the graph digest. Materialize declared source assets without scanning
or blindly copying a package `public/` directory:

```bash
pnpm application materialize-assets \
  --manifest path/to/app.yaml \
  --source-map path/to/source-map.json \
  --target path/to/web-admin/public
```

Generate the React Router build input from the same contribution manifest instead of maintaining a second route list:

```bash
pnpm application emit-route-manifest \
  --manifest path/to/app.yaml \
  --source-map path/to/source-map.json \
  --output path/to/generated-route-manifest.mjs
```

Client and SSR runs must produce the same graph digest.
Source paths stay outside the graph identity and are never valid release inputs.

## Publishing the AuraBoot platform artifact set

Build the runtime and public plugin API, commit the exact source state, then stage a
new immutable platform release directory:

```bash
cd platform
../gradlew clean bootJar :platform-plugin-api:jar
cd ..
node scripts/application/stage-core-only-artifacts.mjs \
  --output /absolute/new/path/to/auraboot-1.0.0
```

The staging command refuses a dirty worktree and emits the runtime JAR, public Maven
API JAR, versioned npm tarballs, core migrations/config, an OCI image layout,
`artifact-catalog.json`, `application.lock`, a CycloneDX SBOM, and a release receipt.
Downstream applications consume only this directory (or the same bytes published to
immutable Maven, npm, migration/config, and OCI registries). A downstream release
must never resolve an AuraBoot sibling checkout, Maven Local, `workspace:` link,
`SNAPSHOT`, `latest`, or branch reference.

## Publishing an application release for tenant binding

Registration records immutable application release content, but it does not make a
release selectable by tenants. `ApplicationReleaseControlService` provides the small
control-plane boundary used after registration:

1. `publish` records an immutable publication fact for one exact registered release.
2. `promoteStable` moves the application's single `stable` target with a required
   compare-and-set version after the initial target.
3. `bindStable` resolves `stable` once inside the transaction and creates an exact
   active tenant binding with the authenticated actor and operation ID in its history.

An active binding is admitted only when its exact release is the current published
`stable` target. Existing tenant bindings remain pinned when `stable` advances; rollout,
automatic upgrades, approval workflows, revocation, and retirement are separate future
concerns. The channel history is an immutable audit projection, not a deployment state
machine.

Platform-admin callers publish and move `stable` through the application-release admin
API with a caller-generated ULID operation ID. A product deployment opts new tenants into
binding by setting `aura.application.default-code` to the registered application code.
Tenant creation then resolves and writes the exact stable release in the same transaction.
The legacy built-in plugin import still runs after binding until the shared Definition
Resolver has passed shadow comparison; configuring a default application does not yet mean
that per-tenant definitions have been removed.

## Deployment capability checks

`application-release-requirements.schema.json` defines requirements pinned to an exact
Application Release digest. `deployment-capabilities.schema.json` defines an observation
of one deployment generation and exact Platform Release, including supported runtime,
Plugin API and DSL contracts, application epochs, and Handler/Web/schema capabilities.
Multiple supported contracts may refer to the same deployed platform; version ordering
does not imply compatibility. Each resolved capability records all actual provider digests (`providerDigests`), including chained Handler providers.

```bash
node scripts/application/deployment-capability-verifier.mjs \
  requirements.json deployment-capabilities.json expected-identities.json
```

The expected-identities object must contain externally pinned `requirementsDigest`,
`deploymentDigest`, `applicationReleaseDigest`, `platformReleaseDigest`, `deploymentId`,
and `generation`. The first two digests hash the exact input bytes (not normalized JSON).
Do not derive the expected identities from the untrusted files being verified.
The command exits 1 when capabilities are missing or incompatible and rejects malformed,
duplicate, stale or identity-mismatched input. Its report uses `capabilitiesSatisfied`;
it does not admit a release or authorize a tenant binding change.

Current boundary: this checks declared requirements against a supplied observation.
Requirement extraction from release resources, live runtime probes, trusted attestation,
revocation, artifact availability, permission/data migration checks and atomic binding
integration are not implemented by this command. An internally consistent forged pair
of files is not trusted deployment evidence.

### Requirements from a registered application release

Use the exact `manifest_text` bytes returned by the registry and a digest pinned by
an independent trusted caller. Every component must explicitly contain
`compatibilityContract: { "schemaVersion": 1, "requiredCapabilities": [...] }`.
An empty array declares no capability dependency; missing or unknown contracts fail.
The release's `platformCompatibility` must contain the exact `runtime`, `pluginApi`
and `dslSchema` requirements. Existing records without these declarations cannot
use this mode; create a new release with explicit declarations instead of editing history.

```bash
node scripts/application/application-release-requirements.mjs \
  registered-manifest.json sha256:<externally-pinned-release-digest> > requirements.json
node scripts/application/deployment-capability-verifier.mjs --registered \
  registered-manifest.json registered-platform-manifest.json deployment-capabilities.json expected-identities.json
```

Registered mode requires both registered manifests and derives all component requirements before verification.
The platform manifest bytes must match the independently pinned `platformReleaseDigest`;
its release ID must match the deployment observation. Every observed platform contract must
be declared by that exact registered platform. Missing platform bytes fail closed. It rejects
an expected requirements digest made from a reduced requirement set. The extraction
command emits deterministic two-space JSON with a final newline; `requirementsDigest`
must pin these bytes. Repeated requirements across components are unified; duplicate
entries within one component are rejected. Contract versions for the same capability
remain distinct and must all be satisfied. Every provider digest in every observed capability,
including capabilities not required by this application, must appear in the pinned platform
artifact or application component inventory. An unregistered chain member produces
`provider-artifact-not-registered` and prevents a successful decision. Inventory membership
does not prove provider loading, artifact type suitability, or publisher trust.

This verifies consistency with registered declarations. It does not inspect component
resources to prove that declarations are complete, or establish trust in the publisher.
The lower-level requirements-file mode remains available for diagnostics and is not
proof of correspondence to a registered release. Neither mode authorizes binding.

### Registered schema contract observation

`RegisteredSchemaContractInspector` consumes a config artifact explicitly selected
by ID from an exact registered Platform Release. Supply the release ID, independently
pinned release digest, artifact ID, and original contract bytes. The inspector checks
the stored manifest bytes and the artifact digest before decoding or querying catalogs.
Contract documents are limited to 1 MiB and use this shape:

```json
{
  "schemaVersion": 1,
  "key": "orders-schema",
  "contract": "v1",
  "tables": {
    "orders": {
      "schema": "public",
      "table": "orders",
      "columns": [
        { "name": "amount", "type": "integer", "nullable": false,
          "defaultExpression": null, "identity": "", "generated": "" }
      ],
      "constraints": []
    }
  }
}
```

All record fields are explicit, including nullable `defaultExpression`. Unknown
fields, duplicate JSON keys, missing fields and scalar coercion are rejected.
The table set is observed in one read-only catalog snapshot. The result retains
both the registered contract artifact digest and the catalog snapshot identity.
It does not attest which migrator ran, convert a contract digest into an installed
provider digest, or grant deployment admission or tenant binding authority.


### Authenticated deployment observations

`deployment-observation-attestation.mjs` verifies an Ed25519 detached envelope before
calling the registered capability verifier. Its CLI accepts application, platform,
deployment, attestation, trust-policy and expected-identity JSON files in that order.
`expected` must additionally include `trustPolicyDigest`. The CLI reads its own system
clock at verification time and ignores any persisted `now` value. The library API requires
a trusted Unix time `now` in seconds from its caller; never copy it from the sender.
Obtain the policy pin and current deployment generation from the control plane. Pinning an obsolete policy does not prove current trust.

The policy is `{schemaVersion: 1, keys: [...]}`. Each key has `keyId`, PEM `publicKey`,
explicit `deploymentIds`, explicit `platformReleaseDigests`, boolean `revoked`, and
positive `maxAgeSeconds`. No wildcard scopes or embedded envelope keys are accepted.
The envelope signs a domain-separated, fixed-order encoding of schema version, key ID,
raw deployment-byte digest, deployment ID, generation, issuedAt and expiresAt.
Validity is half-open: issuedAt <= now < expiresAt; lifetime must fit the key policy.

The exported `signDeploymentObservation` takes observation bytes, validity/key metadata
and an Ed25519 private PEM. It performs no observation and issues no admission: the caller
must collect runtime evidence first. Keep operational keys outside application source.
The current tests use ephemeral keys; operational signer provisioning, policy distribution,
trusted clock and binding-time generation/revocation checks remain integration work.


Use `--target-context` when invoked inside a target-owned execution scope. This mode
requires `AURA_DEPLOYMENT_ID` and canonical positive `AURA_DEPLOYMENT_GENERATION` from
the protected executor and replaces the identity-file ID/generation with those values.
Missing or invalid context fails without fallback. The Quote executor derives these
values from the verified reservation owner while holding its shared guard through child
execution. Environment variables alone are not authorization: direct CLI invocation does
not prove a reservation lock, policy freshness, or actual observation. The release
controller still needs to invoke this mode with trusted observation and policy inputs.


The cross-repository process test is `scripts/application/deployment-target-attestation.it.mjs`.
Run it with explicit absolute `AURA_QUOTE_SOURCE_ROOT` and `AURA_TEST_PYTHON` values:
`node --test scripts/application/deployment-target-attestation.it.mjs`.
It copies the current Quote executor into an owned temporary target, reserves generation 1,
invokes the real signed verifier through that executor, finishes the fixture attempt, then
proves generation 2 rejects the old signature and accepts a fresh one. Revocation and wrong
reservation owners are negative cases. This uses synthetic capability declarations and
in-memory test keys, not a production observation collector or SSH deployment.


`RegisteredDefinitionHandlerInspector.inspectRelease` exposes a release-level
`capabilities` collection. Consumers must use it instead of flattening diagnostic
per-component observations. The collection is deterministic, deduplicated and immutable;
any release finding (including unobserved components, Web/schema requirements, registry
generation changes or conflicting provider provenance) leaves it empty. Successful
per-component observations remain diagnostic evidence, not authorization to publish a
partial aggregate as a fully inspected release.


### Migration execution evidence

The migrator entrypoint now requires `AURA_DEPLOYMENT_ID`, canonical positive
`AURA_DEPLOYMENT_GENERATION`, and an absolute `AURA_MIGRATION_EVIDENCE_DIR` whose parent
exists and is writable by the container user. Supply a fresh directory for each action;
existing directories are rejected before Flyway starts. Mount evidence outside ephemeral
container storage when retention is required. The target executor must supply the current
reserved deployment context; environment values alone do not authenticate it.

`started.json` records the action, verified payload SHA-256, target host/port/database,
deployment identity and generation. `result.json` binds its exact bytes by digest and
records success or failure, exit code and completion time. Both files are create-only and
exclude user/password/exception text. A missing result means incomplete execution, never
success. Signals and launch failures produce failure evidence. These receipts do not
prove the runtime image digest, schema compatibility, publisher trust or release admission.
The Linux migrator gate now checks the evidence on executed success/failure cases; that
gate must run against the exact image before claiming this execution path verified.


### Reading migration execution receipts

`node scripts/application/migration-execution-verifier.mjs <started.json> <result.json> <expected-context.json>`
validates bounded, versioned receipts and their exact byte digests. Expected context must supply
`deploymentId`, `generation`, `action`, `payloadSha256`, `database` (`host`, `port`, `name`),
`startedDigest`, and `resultDigest` from the controlling execution context. The result must link
back to the pinned start bytes. Context mismatches and contradictory outcomes fail closed.
The CLI exits 0 for a successful execution, 1 for a valid failure receipt, and nonzero for invalid
input. Library consumers must inspect `executionSucceeded`; merely parsing a receipt is not success.

These checks do not authenticate the evidence producer. Hashing arbitrary incoming receipts to
populate their own expected digests provides no provenance. The image gate pins bytes read from
its own isolated execution mount; formal publication still requires a trusted collector. Producer
wall-clock timestamps are validated as timestamps, not used as trusted freshness or duration claims.
A successful `info` or `validate` receipt does not prove that `migrate` ran. This module does not
establish image digest, schema compatibility, release admission, or permission to bind a tenant.


### Shadow binding database policy

`node scripts/application/shadow-binding-role-policy.mjs <schema> <runtime-role> <shadow-role> <owner-role>`
emits transactional SQL for privileged review and application with `psql ON_ERROR_STOP=1`.
The three existing roles must be distinct. Runtime and shadow accounts cannot own the tables,
inherit or assume other roles, create schema objects, or hold administrative/bypass-RLS attributes.
The policy resets table and column grants on the release directory and binding/history tables.
Runtime is read-only. Shadow writes have restricted insert/update columns and row policies that
exclude active bindings even when raw SQL omits a status predicate. History inserts remain limited
by the migration-owned projection trigger; history update/delete/truncate privileges are withheld.
Unknown binding row policies fail closed and require explicit reconciliation.

The owner retains administrative access. This is a control-plane database account boundary, not
per-request tenant authorization. Apply only after the binding migration. Account creation,
credential delivery, private-pool verification, authenticated operators, and activation admission
remain separate integration requirements. The policy does not grant activation authority.


`TenantApplicationShadowBindingService` is disabled unless `aura.binding.shadow.enabled=true`.
When enabled, `aura.binding.shadow.jdbc-url`, `.username`, and `.password-file` configure a private
pool; `spring.datasource.username` identifies the distinct runtime account. Startup checks exact
required table/column privileges, non-administrative role identity, no memberships/schema creation,
and enabled binding RLS with the approved three policy definitions. Invalid configuration closes
the new pool and fails startup. It never contributes a primary DataSource or falls back to the
business connection. Shutdown closes only this owned pool. This does not expose a public binding
API or grant activation. Privilege checks occur at pool initialization; operational policy changes
remain controlled by the database owner, and credential rollout requires separate verification.

Live page-definition comparison is separately disabled by default. Set
`aura.application.definition-shadow.page-enabled=true` together with
`aura.application.default-code=<application-code>` only while sampling tenants whose binding status
is `shadow`. A page-key lookup then compares the unmaterialized legacy page with the same globally
unique page key in the tenant's exact bound Release. The request still returns the legacy page.
Outcomes are counted in `auraboot.application.definition.shadow.read.total` with `resource_type=page`
and an `exact_match`, `drifted`, `missing`, `error`, or `skipped` verdict; drift, missing data, and
errors also emit structured warnings. Active bindings and pages outside the application Release are
skipped. Disable the switch after the bounded migration observation window.

Application Release page primary reads are also disabled by default. After shadow comparison has
proved an existing tenant exact, set `aura.application.definition-read.page-primary-enabled=true`.
For an `active` binding, application-owned page keys then resolve from the tenant's exact immutable
Release and carry `APPLICATION_RELEASE` runtime identity; no tenant-local page PID is synthesized.
`shadow` bindings, platform pages, and pages owned by unrelated plugins keep their existing read
path. If a legacy page belongs to a plugin contained in the active Release but that Release omits
the page key, the request fails explicitly instead of falling back to the tenant copy. Saved-view
lineage uses the stable page key for Release pages while retaining legacy PID compatibility.

The broader zero-import runtime path is separately gated by
`aura.application.definition-read.runtime-primary-enabled=true`. For an active binding, models,
menus, roles, commands, command binding rules, and their permission codes then resolve from the
tenant's exact immutable Release without tenant-local definition rows. Command execution first
honors a legacy tenant-local command when present, then executes the released command when the
local cache has no match. Release roles receive only their declared permissions. A member with the
built-in `tenant_admin` role receives the Release's explicit permissions, permissions referenced by
roles or commands, and the standard `read`, `create`, `update`, `delete`, `export`, and `import`
permissions generated for each released model. This preserves the authority that legacy config
import assigned to `tenant_admin` while removing the import requirement.


Shadow writes require `AuditContext(actor, operationId)`. The trusted caller supplies an actor in
`user:`, `ci:`, `system:`, or `test:` form and a ULID operation identity. The store installs this
context with transaction-local PostgreSQL settings and restores caller settings after success;
failures roll back the transaction. Migration `V20260926040000` records these values in the immutable
history projection and rejects new transitions without them. An operation ID is unique within a
tenant/application history; reusing it for another transition rolls back the pointer and history.
Existing initial-binding retries preserve the first history entry. This is not general CAS retry
idempotency: stale expected versions still conflict. Legacy unknown operator metadata stays null.

Database settings carry an assertion by the control account, not authentication proof. Public
controllers must derive the actor from authenticated context and must not accept a client-selected
actor. That HTTP boundary is not yet exposed. Pool startup requires the operator audit migration;
there is no unaudited write overload or fallback.
