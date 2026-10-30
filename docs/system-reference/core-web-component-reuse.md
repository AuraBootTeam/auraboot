---
type: system-reference
status: active
---

# Core Web component inputs and explicit reuse

The original Core stager can consume Web assets and npm packages from a selected
native platform archive while compiling the current backend. It preserves the
original Web/npm producer source in the catalog and lock. A new Core receipt
records the selected archive, dataset receipt, original lock and component digest.
It does not relabel old Java bytes or grant application qualification.

```sh
AURA_CI_DATASET_INPUTS=/absolute/frozen/dataset-inputs.json \
node scripts/application/stage-core-only-artifacts.mjs \
  --repo-root /absolute/current/core --output /absolute/fresh/output \
  --web-platform-archive /absolute/selected/platform-artifacts.tar \
  --web-platform-selection /absolute/selected/core-web-selection.json
```

The existing Linux product image runner forwards `AURA_PRODUCT_WEB_PLATFORM_ARCHIVE`
and `AURA_PRODUCT_WEB_PLATFORM_SELECTION` to this operation. Whole-platform selection
and Web-only selection are mutually exclusive. These are builder inputs, not a new
Jenkins job, deployment operation or authorization.

The managed selection is an immutable second dataset emitted from a completed
original native producer. It binds that attempt, qualification, job, original
source and lock, archive bytes, effective inputs and component digest. Both files
must occur exactly once in the original frozen dataset receipt. Canonical selection
bytes and all bindings are checked before backend compilation; both hashes are
checked again before output completion. The new receipt retains the selection
and its original byte hash for native qualification. Arbitrary ambient source
overrides are rejected by the managed runner. The lower-level stager retains
`--web-platform-source` for direct inspection, but this alone does not satisfy
managed Web dependency qualification.

New native Core builds record a `webComponent` in their original release receipt.
Its input identity includes tracked file contents and modes for the actual shell
packing entries, packages, lock, versions, build recipes and schemas; Node/pnpm
tool identity and the declared build environment are also bound. Shell packing
and input capture share the same entry list and source filter. The initial recipe
projection conservatively includes application tooling and distribution schemas;
changes there invalidate reuse even if a narrower future projection could prove
them irrelevant. Backend Java changes alone do not change this Web input identity.

Web subprocesses receive a declared environment. Ambient public configuration,
credentials, preload options and user/global npm configuration are not forwarded.
An optional exact `AURA_COMPOSITION_KEY` and a bounded
`NODE_OPTIONS=--max-old-space-size=N` are recorded inputs. Custom pnpm hooks need
a separate explicit input contract. Unexpected ignored/untracked publishable
source files are rejected rather than silently copied.

Selected archives still pass the original full platform catalog, lock, auxiliary,
OCI and frozen dataset checks. Web and all npm bytes must match their original
component record, and current effective inputs must be identical. Input drift,
changed bytes, unsafe paths, incomplete provenance or an existing destination
block the operation; explicit selection never falls back to a new Web build.
The current backend is still built by the original clean `bootJar` path.

Historical exports remain readable under their original contract. Exports without
the native Web input record cannot acquire Web reuse eligibility by backfilling a
receipt. Library publication, product Web/BFF reuse, unchanged-target deployment,
combination tests and Linux producer/consumer qualification remain separate.
