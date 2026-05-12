# CodeQL Security Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the AuraBoot CodeQL security cleanup so local preflight, GitHub Action analysis, model-pack publishing, and final merge readiness are all explicit and reproducible.

**Architecture:** Keep GitHub CodeQL analysis on the official `github/codeql-action/init@v3` and `analyze@v3` path. Use a local Java model pack for preflight and a separate publish workflow for GHCR publication; only wire the published model pack into `init` after the package exists. Security findings are handled by code fixes first, then documented query filters for stable false positives.

**Tech Stack:** GitHub Actions, CodeQL CLI, CodeQL model packs, Gradle/JDK 21, pnpm/Vitest/TypeScript.

---

## File Map

- Modify: `.github/workflows/codeql.yml` - official CodeQL analysis workflow, Java build command, future `packs:` integration.
- Create/Modify: `.github/workflows/codeql-model-pack.yml` - publishes `.github/codeql/aura-java-models` to GHCR.
- Modify: `.github/codeql/codeql-config.yml` - security-extended config, path ignores, documented rule exclusions.
- Modify: `.github/codeql/aura-java-models/qlpack.yml` - Java model pack metadata.
- Modify: `.github/codeql/aura-java-models/models/aura-sanitizers.model.yml` - Java sanitizer/barrier data extension.
- Verify: `plugins/cli/**`, `web-admin/**`, `platform/**` - code already touched by security fixes.

---

### Task 1: Finalize CodeQL Action And Model Pack Publishing

**Files:**
- Modify: `.github/workflows/codeql.yml`
- Create/Modify: `.github/workflows/codeql-model-pack.yml`
- Modify: `.github/codeql/aura-java-models/qlpack.yml`

- [x] **Step 1: Keep Java CodeQL build cache disabled**

Expected Java build line in `.github/workflows/codeql.yml`:

```yaml
./gradlew clean classes -x test --no-daemon --no-build-cache
```

- [x] **Step 2: Keep model pack publish workflow**

Expected `.github/workflows/codeql-model-pack.yml` publish step:

```yaml
- name: Publish model pack
  run: echo "${GITHUB_TOKEN}" | codeql pack publish .github/codeql/aura-java-models --github-auth-stdin
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

- [x] **Step 3: Validate local packaging**

Run:

```bash
codeql pack install .github/codeql/aura-java-models
codeql pack publish --dry-run .github/codeql/aura-java-models
```

Expected: install succeeds and dry-run prints `Publish successful. (dry run)`.

### Task 2: Gate Published Model Pack Integration

**Files:**
- Modify later: `.github/workflows/codeql.yml`

- [x] **Step 1: Do not add `packs:` before GHCR package exists**

Keep this comment in `.github/workflows/codeql.yml` until `auraboot/aura-java-models@0.1.0` is published:

```yaml
# GitHub CodeQL Action only accepts published model packs via init
# `packs:`. After publishing auraboot/aura-java-models@0.1.0 to GHCR,
# add it to the init step above, for Java only.
```

- [ ] **Step 2: After the publish workflow succeeds on GitHub, add Java-only `packs:`**

Patch target:

```yaml
with:
  languages: ${{ matrix.language }}
  build-mode: ${{ matrix.language == 'java-kotlin' && 'manual' || 'none' }}
  config-file: ./.github/codeql/codeql-config.yml
  packs: ${{ matrix.language == 'java-kotlin' && 'auraboot/aura-java-models@0.1.0' || '' }}
```

Expected: only do this after the published package can be resolved by the GitHub Action.

### Task 3: Re-run Security Verification Matrix

**Files:**
- Verify only.

- [x] **Step 1: Java CodeQL local preflight**

Run:

```bash
rm -rf /tmp/auraboot-codeql-java
codeql database create /tmp/auraboot-codeql-java --language=java-kotlin --source-root "$PWD" --command='./gradlew clean classes -x test --no-daemon --no-build-cache' --working-dir="$PWD/platform"
codeql database analyze /tmp/auraboot-codeql-java --model-packs auraboot/aura-java-models --additional-packs .github/codeql codeql/java-queries:codeql-suites/java-security-extended.qls --format=sarif-latest --output=/tmp/auraboot-codeql-java.sarif --sarif-category=/language:java-kotlin --threads=0 --ram=8192
```

Expected: no active results after documented exclusions.

- [x] **Step 2: JS/TS CodeQL local preflight**

Run:

```bash
rm -rf /tmp/auraboot-codeql-js
codeql database create /tmp/auraboot-codeql-js --language=javascript-typescript --source-root "$PWD"
codeql database analyze /tmp/auraboot-codeql-js codeql/javascript-queries:codeql-suites/javascript-security-extended.qls --format=sarif-latest --output=/tmp/auraboot-codeql-js.sarif --sarif-category=/language:javascript-typescript --threads=0 --ram=8192
```

Expected: no active results after documented exclusions and `paths-ignore`.

- [x] **Step 3: Product tests**

Run:

```bash
cd platform && ./gradlew :test --tests com.auraboot.framework.common.util.PathSafetyUtilsTest --tests com.auraboot.framework.common.util.PaginationSafetyUtilsTest --tests com.auraboot.framework.common.util.LogSanitizerTest --tests com.auraboot.framework.meta.service.impl.FieldMaskServiceImplTest --no-daemon
cd ..
pnpm --dir web-admin typecheck
pnpm --dir web-admin test:unit:run app/server/utils/__tests__/security-helpers.test.ts
pnpm --dir plugins/cli test
pnpm --dir plugins/cli lint
git diff --check
```

Expected: all commands pass.

### Task 4: Cleanup And Merge Readiness

**Files:**
- Verify only unless stale generated files are present.

- [x] **Step 1: Remove local scan artifacts**

Run:

```bash
rm -rf /tmp/auraboot-codeql-java /tmp/auraboot-codeql-js /tmp/auraboot-codeql-java.sarif /tmp/auraboot-codeql-js.sarif
find "$PWD" -path '*/node_modules/*' -prune -o -name .DS_Store -print -delete
```

Expected: no tracked artifact changes from local scanning.

- [x] **Step 2: Review changed-file groups**

Run:

```bash
git status --short
git diff --stat
```

Expected: changed files are limited to security fixes, CodeQL config/workflows, dependency lock updates, and focused tests.

- [x] **Step 3: Prepare final handoff**

Include:

```text
CodeQL local Java active findings: 0
CodeQL local JS/TS active findings: 0
Tests: Java targeted, web-admin typecheck/unit, plugins/cli test/lint, diff check
Known follow-up: run codeql-model-pack workflow on GitHub, then add init packs once GHCR package resolves
```

### Task 5: Sync With Latest `origin/main`

**Files:**
- Verify only unless conflicts require edits.

- [x] **Step 1: Preserve current work before syncing**

Run:

```bash
git stash push --include-untracked -m "codex-security-codeql-critical-pre-sync"
```

Expected: current index, working tree, and untracked security files are saved.

- [x] **Step 2: Fast-forward branch to latest `origin/main`**

Run:

```bash
git merge --ff-only origin/main
```

Expected: branch moves from the older base to latest `origin/main` without a merge commit.

- [x] **Step 3: Re-apply security work**

Run:

```bash
git stash pop --index
```

Expected: security work is restored and no conflict markers remain.

- [x] **Step 4: Re-run post-sync verification**

Run:

```bash
git diff --check
cd platform && ./gradlew :test --tests com.auraboot.framework.common.util.PathSafetyUtilsTest --tests com.auraboot.framework.common.util.PaginationSafetyUtilsTest --tests com.auraboot.framework.common.util.LogSanitizerTest --tests com.auraboot.framework.meta.service.impl.FieldMaskServiceImplTest --no-daemon
cd ..
pnpm --dir web-admin typecheck
pnpm --dir web-admin test:unit:run app/server/utils/__tests__/security-helpers.test.ts
pnpm --dir plugins/cli test
pnpm --dir plugins/cli lint
```

Expected: all checks still pass on the refreshed base.

- [x] **Step 5: Re-run post-sync CodeQL local preflight**

Run:

```bash
find "$PWD" -path '*/node_modules/*' -prune -o -name .DS_Store -print -delete
rm -rf platform/build /tmp/auraboot-codeql-java /tmp/auraboot-codeql-js /tmp/auraboot-codeql-java.sarif /tmp/auraboot-codeql-js.sarif
codeql database create /tmp/auraboot-codeql-java --language=java-kotlin --source-root "$PWD" --command='./gradlew classes -x test --no-daemon --no-build-cache' --working-dir="$PWD/platform"
codeql database analyze /tmp/auraboot-codeql-java --model-packs auraboot/aura-java-models --additional-packs .github/codeql codeql/java-queries:codeql-suites/java-security-extended.qls --format=sarif-latest --output=/tmp/auraboot-codeql-java.sarif --sarif-category=/language:java-kotlin --threads=0 --ram=8192
codeql database create /tmp/auraboot-codeql-js --language=javascript-typescript --source-root "$PWD"
codeql database analyze /tmp/auraboot-codeql-js codeql/javascript-queries:codeql-suites/javascript-security-extended.qls --format=sarif-latest --output=/tmp/auraboot-codeql-js.sarif --sarif-category=/language:javascript-typescript --threads=0 --ram=8192
```

Expected: no active results after documented exclusions and `paths-ignore`.

Note: GitHub Actions can keep `./gradlew clean classes ...` because CI runs on Linux. The local macOS preflight uses explicit `rm -rf platform/build` followed by `classes` to avoid Finder-created `.DS_Store` racing Gradle `clean`.

### Task 6: Commit Merge-Ready Security Work

**Files:**
- Stage all tracked and untracked security files in this worktree.

- [x] **Step 1: Stage the verified security changes**

Run:

```bash
git add -A
```

Expected: all CodeQL config/workflow, Java, web-admin, CLI, dependency, and plan files are staged.

- [x] **Step 2: Commit the verified branch state**

Run:

```bash
git commit -m "fix: resolve CodeQL security findings"
```

Expected: one merge-ready branch commit on top of latest `origin/main`.

### Task 7: Clear PR Aggregate CodeQL Path-Injection Gate

**Files:**
- `/Users/ghj/work/auraboot/.worktrees/security-codeql-critical/platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginPackageServiceImpl.java`
- `/Users/ghj/work/auraboot/.worktrees/security-codeql-critical/platform/src/main/java/com/auraboot/framework/plugin/controller/PluginPackageController.java`

- [x] **Step 1: Inspect the failed aggregate CodeQL check**

Expected: the PR-level aggregate CodeQL check reports one high-severity `java/path-injection` alert in `PluginPackageServiceImpl`.

- [x] **Step 2: Remove request-driven server-local path parsing from REST**

Expected: REST callers can still upload plugin packages through multipart or octet-stream, but can no longer make the server parse or install arbitrary local file or directory paths from a request body. Internal trusted callers can still use `parsePackageFromPath`.

- [x] **Step 3: Verify locally before pushing**

Run:

```bash
git diff --check
cd platform && ./gradlew :test --tests com.auraboot.framework.common.util.PathSafetyUtilsTest --no-daemon
codeql database create /tmp/auraboot-codeql-java-path --language=java-kotlin --source-root "$PWD" --command='./gradlew classes -x test --no-daemon --no-build-cache' --working-dir="$PWD/platform"
codeql database analyze /tmp/auraboot-codeql-java-path /Users/ghj/.codeql/packages/codeql/java-queries/1.11.2/Security/CWE/CWE-022/TaintedPath.ql /Users/ghj/.codeql/packages/codeql/java-queries/1.11.2/AlertSuppression.ql /Users/ghj/.codeql/packages/codeql/java-queries/1.11.2/AlertSuppressionAnnotations.ql --format=sarif-latest --output=/tmp/auraboot-codeql-java-path.sarif --sarif-category=/language:java-kotlin --threads=0 --ram=8192
```

Expected: targeted Java tests pass and no local `java/path-injection` result remains for `PluginPackageServiceImpl`.
