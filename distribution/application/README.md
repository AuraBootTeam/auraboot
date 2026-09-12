# AuraBoot application composition contract

This directory defines the versioned contract used to assemble an AuraBoot-based application from immutable artifacts.

An application manifest describes the intended runtime, platform contracts, backend plugins, migration sets, frontend contributions, and config import order. The resolver matches every requirement against an explicit artifact catalog and produces an `application.lock` with exact versions, SHA-256 digests, and 40-character source commits.

```bash
pnpm application validate \
  --manifest distribution/application/examples/core-only/app.yaml

pnpm application resolve \
  --manifest path/to/app.yaml \
  --catalog path/to/artifact-catalog.json \
  --output path/to/application.lock

pnpm application verify-lock \
  --lock path/to/application.lock

pnpm application verify-artifacts \
  --lock path/to/application.lock \
  --artifact-root path/to/staged-distribution

pnpm application graph \
  --manifest path/to/app.yaml \
  --mode artifact \
  --output path/to/artifact-graph.json
```

Release manifests and catalogs must not use `SNAPSHOT`, `latest`, workspace links, branch references, sibling source paths, or mutable artifact identities. `verify-artifacts` requires every lock entry to name a staged `localPath` and fails if any bytes do not match the locked digest.

Local source composition is a separate developer adapter. It must eventually emit the same resolved contribution graph as artifact mode, but source paths are never valid release inputs.
The `graph` command intentionally keeps source paths out of the logical graph, so source and artifact adapters can be compared by `graphDigest`.
