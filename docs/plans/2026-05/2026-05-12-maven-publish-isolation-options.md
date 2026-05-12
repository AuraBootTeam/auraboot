# Maven Publish Isolation Options

Date: 2026-05-12

## Problem

`publishToMavenLocal` is not a dependency cache write. It publishes mutable
SNAPSHOT artifacts that enterprise worktrees may consume. In multi-worktree
development, writing those artifacts to the default `~/.m2/repository` can make
one worktree read another worktree's OSS build.

The current helper:

```bash
source scripts/dev/maven-local-export.sh
./gradlew publishToMavenLocal -Dmaven.repo.local="$AURA_MAVEN_REPO"
```

is correct for isolation, but `maven.repo.local` also participates in Maven
dependency resolution. That means strict publish isolation may duplicate some
third-party dependencies inside each worktree.

## Options

### Option A: per-worktree `maven.repo.local`

Status: implemented baseline.

Pros:

- Simple and reliable.
- Prevents cross-worktree SNAPSHOT pollution.
- Works with existing Gradle publish tasks and enterprise consumption paths.

Cons:

- Can duplicate Maven dependencies per worktree.
- Treats dependency cache and publish output as one directory because Maven
  exposes them that way.

Use this as the default until enterprise wiring moves to a better integration
path.

### Option B: per-worktree file repository for internal artifacts

Status: recommended P1.

Publish internal OSS artifacts to a worktree-local file repository, for
example:

```text
$WORKTREE/.aura-published-m2
```

Enterprise would resolve Aura internal SNAPSHOTs from that repository while
continuing to use normal shared dependency caches for third-party artifacts.

Pros:

- Separates publish output from dependency cache.
- Keeps third-party dependency reuse high.
- Keeps the isolation contract explicit.

Cons:

- Requires coordinated Gradle repository configuration on the consumer side.
- Needs a small helper/contract so enterprise resolves the intended repo.

### Option C: Gradle composite build / included build

Status: recommended P2 for long-term developer experience.

Enterprise includes the OSS worktree directly instead of consuming a published
SNAPSHOT.

Pros:

- Removes most local Maven publish churn.
- Best source-level correctness for concurrent worktrees.
- Avoids stale SNAPSHOT ambiguity.

Cons:

- Larger build graph changes.
- Needs careful enterprise/OSS boundary review.
- May be harder for plugin/package scenarios that intentionally validate
  published artifacts.

## Decision

Keep Option A as the enforced baseline for this Docker refactor. It is safer
than shared `~/.m2` and already covered by the Gradle guard.

Track Option B as the next practical improvement: a per-worktree file
repository for Aura internal artifacts gives most of the disk savings without
weakening isolation. Treat Option C as a longer-term build-system improvement,
not a blocker for the current environment refactor.
