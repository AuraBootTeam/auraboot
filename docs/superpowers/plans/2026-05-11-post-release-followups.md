# Post-Release Followups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the highest-risk v0.1.0-beta.1 follow-ups: stable required checks, faster image publishing, and public performance benchmark scaffolding.

**Architecture:** Keep canonical workspaces clean by using isolated worktrees. Prefer existing workflow names and scripts so repository settings can point at stable checks. Add public performance scripts without requiring a long-running CI load test on every PR.

**Tech Stack:** GitHub Actions, Docker Buildx/GHCR, Bash, k6, jq, markdownlint.

---

## Task List

### Task 1: Stable Required Checks

**Files:**
- Modify: `.github/workflows/backend.yml`
- Modify: `.github/workflows/docs.yml`
- Modify: `.github/workflows/quickstart.yml`

- [x] Remove PR/push path filters so required check contexts always appear.
- [x] Add a lightweight `changed-files` gate to each workflow.
- [x] Run expensive backend, docs, and Docker steps only when relevant files changed.
- [x] Keep job names unchanged so branch protection can keep using existing contexts.
- [ ] After merge, restore branch protection required contexts for backend/docs/docker checks.

### Task 2: Multi-Arch Image Build Time

**Files:**
- Modify: `.github/workflows/build-image.yml`

- [x] Skip image publishing for docs-only and metadata-only main pushes.
- [x] Keep tag builds publishing `linux/amd64,linux/arm64`.
- [x] Keep GHA Buildx cache enabled.
- [ ] Review build timings after the next main/tag build and update #152.

### Task 3: Public Performance Benchmark Suite

**Files:**
- Create: `tests/load/k6/lib/http.js`
- Create: `tests/load/k6/auth-baseline.js`
- Create: `tests/load/k6/list-query.js`
- Create: `tests/load/k6/command-execution.js`
- Modify: `scripts/perf-ci/baseline/README.md`
- Modify: `docs/releases/v0.1.0-beta.1.md`

- [x] Add shared k6 helpers for login, auth headers, and API response checks.
- [x] Add auth baseline scenario.
- [x] Add authenticated dynamic list query scenario.
- [x] Add command-pipeline dry-run scenario.
- [x] Document the local/public benchmark workflow.
- [ ] Capture fresh baseline JSON from a warmed public beta stack and attach numbers to #150.

### Task 4: Release Health Check

**Files:**
- GitHub issue tracker only.

- [x] Verify GitHub Release page.
- [x] Verify GHCR image pull attempt and record the current 403 visibility/permission blocker.
- [x] Verify Gitee mirror after the retry/backoff workflow has run.
- [x] Verify Discussions/Issues entry points.
- [x] Record results on #147.

### Task 5: Beta.2 Planning

**Files:**
- GitHub milestone/issues only.

- [x] Create or update `v0.1.0-beta.2` milestone.
- [x] Assign #148, #149, #150, #152 to the milestone after owner confirmation.
- [ ] Keep Page Designer UX and mobile coverage as product-quality work, not release-infra work.
