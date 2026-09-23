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
  --lock path/to/application.lock

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
