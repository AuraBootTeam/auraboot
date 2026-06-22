---
created: 2026-06-22
type: backlog
status: active
area: ci/backend
relates_to:
  - docs/handover/HANDOVER-2026-06-22-agent-run-declared-tools.md
---

# Backend CI cannot resolve SmartEngine 4.0.2 in a clean checkout

## Symptom

`Backend CI / Build & Quality Gate (Java 21)` fails during `compileJava` in a clean GitHub Actions
runner:

```text
Execution failed for task ':compileJava'.
> Could not resolve all files for configuration ':compileClasspath'.
   > Could not find com.auraboot.smart.framework:smart-engine-extension-storage-mysql:4.0.2.
   > Could not find com.auraboot.smart.framework:smart-engine-extension-storage-custom:4.0.2.
```

Observed on PR #1021 and on recent `main` backend workflow runs, including:

- `27940177148` / PR #1021 / head `6fe463d77384028e126356113b6044d5d3715c44`
- `27939137637` / `main` / head `14a6f08ba6b57cf7c3eedb993e21b2e427b7e0f1`
- `27909989126` / `main` / head `dcd53ddc7d3006b240bc8d8e216befe59ebba733`

## Root cause

`platform/build.gradle` declares:

```gradle
implementation ('com.auraboot.smart.framework:smart-engine-extension-storage-mysql:4.0.2')
implementation ('com.auraboot.smart.framework:smart-engine-extension-storage-custom:4.0.2')
```

The repository list starts with `mavenLocal()` and comments that SmartEngine fork jars only exist
there. In a clean GitHub Actions runner, `mavenLocal()` is empty and there is no workflow step that
publishes or downloads these 4.0.2 artifacts before Gradle resolves `compileClasspath`.

Local builds can pass when the developer machine already has the jars in `~/.m2/repository`. The
local resolver marker for these artifacts has no remote repository id, confirming they are locally
installed artifacts.

## Why this is not PR #1021's agent regression

PR #1021 changes agent runtime code, tests, seed data, and docs. It does not modify
`platform/build.gradle`, SmartEngine dependency versions, or backend workflow dependency setup.
The same backend workflow failure is already present on `main`.

## Fix directions

Pick one durable path:

1. Publish the SmartEngine 4.0.2 artifacts to a repository that CI can read, then add that repository
   to Gradle with documented credentials if needed.
2. Add a CI pre-step that builds/publishes the SmartEngine fork into the job-local Maven repository
   before `compileJava`.
3. If 4.0.2 should not be required in OSS CI, split the SmartEngine fork dependency behind a profile
   or substitute a CI-available artifact without regressing the BPM countersign fixes that required
   4.0.2.

Do not rely on developer-machine `~/.m2` state as the completion criterion for backend CI.
