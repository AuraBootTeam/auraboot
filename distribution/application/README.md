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
remain distinct and must all be satisfied.

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
