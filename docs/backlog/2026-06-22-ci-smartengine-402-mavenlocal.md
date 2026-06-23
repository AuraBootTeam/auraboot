---
created: 2026-06-22
type: backlog
status: closed
area: ci/backend
relates_to:
  - docs/handover/HANDOVER-2026-06-22-agent-run-declared-tools.md
distilled_to:
  - docs/handover/HANDOVER-2026-06-22-agent-run-declared-tools.md
---

# Backend CI SmartEngine 4.0.2 clean resolution

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

The first CI failure happened before the 4.0.2 fork artifacts were available from a clean remote
repository. After publication to Maven Central, a second failure mode remained: the Aliyun public
mirror still returned partial or missing SmartEngine 4.0.2 artifacts, and Gradle could stick the
`com.auraboot.smart.framework` group to that incomplete mirror because it appeared before Maven
Central in the repository list.

Local builds can pass when the developer machine already has the jars in `~/.m2/repository` or in
Gradle cache. That is not a valid CI completion signal.

## Why this is not PR #1021's agent regression

PR #1021 changes agent runtime code, tests, seed data, and docs. It does not modify
`platform/build.gradle`, SmartEngine dependency versions, or backend workflow dependency setup.
The same backend workflow failure is already present on `main`.

## Resolution

PR #1021 now adds a content-filtered Maven Central repository before the Aliyun mirrors:

- `mavenCentral { name = 'Maven Central SmartEngine'; content { includeGroup
  'com.auraboot.smart.framework' } }`
- Aliyun `public` and `spring` repositories explicitly exclude `com.auraboot.smart.framework`.
- The normal fallback `mavenCentral()` remains after the mirrors for non-SmartEngine dependencies.
- `platform/settings.gradle` also declares `pluginManagement.repositories` with `mavenCentral()`
  before `gradlePluginPortal()`, because the Spring Boot and dependency-management plugin markers
  are available from Maven Central and clean Gradle homes should not depend on plugin portal edge
  availability.

This keeps SmartEngine fork resolution on Maven Central while preserving the existing mirror order
for the rest of the build, and gives Gradle plugin marker resolution the same Maven Central-first
fallback.

Follow-up on 2026-06-23: SmartEngine 4.0.2 is now published to the remote Maven repository, so the
temporary Maven-local install fallback has been removed from CI and Docker builds. The
`install-smartengine-maven-local.sh` scripts were deleted, and contract tests now assert that
backend/codeql workflows and `platform/Dockerfile` no longer invoke the Maven-local workaround.

## Verification

Clean remote artifact checks:

- Maven Central returns HTTP 200 for `smart-engine-extension-storage-mysql:4.0.2`.
- Maven Central returns HTTP 200 for `smart-engine-extension-storage-custom:4.0.2`.
- Maven Central returns HTTP 200 for `smart-engine-extension-storage-common:4.0.2`.
- Aliyun still returned 404 for at least part of the same artifact set during verification, which
  proves repository order/content filters are still required while the mirror catches up.
- Maven Central returns HTTP 200 for the Spring Boot `3.5.14` and dependency-management `1.1.7`
  Gradle plugin markers when retried through transient TLS failures.

Local clean-cache Gradle verification before rebasing onto the latest `main`:

```bash
rm -rf /tmp/auraboot-smartengine-402-m2-fix /tmp/auraboot-smartengine-402-gradle-fix
cd platform
GRADLE_USER_HOME=/tmp/auraboot-smartengine-402-gradle-fix \
  ./gradlew --no-daemon --refresh-dependencies clean compileJava \
  -Dmaven.repo.local=/tmp/auraboot-smartengine-402-m2-fix
GRADLE_USER_HOME=/tmp/auraboot-smartengine-402-gradle-fix \
  ./gradlew --no-daemon compileTestJava \
  -Dmaven.repo.local=/tmp/auraboot-smartengine-402-m2-fix
```

After rebasing onto `main`, a fresh Gradle home with only the wrapper distribution copied no longer
failed on SmartEngine or plugin marker discovery. The run progressed into normal dependency
downloads and then failed on transient Maven Central TLS handshakes while fetching unrelated Spring
Boot/AWS artifacts. That is a local network availability limit, not the original missing
SmartEngine 4.0.2 failure.

Do not rely on developer-machine `~/.m2` state as the completion criterion for backend CI.
