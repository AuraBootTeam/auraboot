# Release Health Baselines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the remaining v0.1.0-beta.1 post-release health work: GHCR pullability and first public performance baseline evidence.

**Architecture:** Use GitHub/GHCR API checks for package health and an isolated Docker Compose stack for k6 smoke baselines so canonical workspaces and shared host services are not touched. Repository changes are allowed where reproducibility gaps are found in the public quickstart or performance scripts.

**Tech Stack:** GitHub CLI/API, GHCR, Docker Compose, k6, jq, Bash.

---

## Task 1: GHCR Pullability

**Files:**
- Update: GitHub issue `#147`

- [x] Verify current GitHub CLI token package scopes with `gh auth status -t`.
- [x] Attempt package metadata lookup through `gh api /orgs/AuraBootTeam/packages/container/auraboot`.
- [x] Attempt manifest inspection for `ghcr.io/aurabootteam/auraboot:0.1.0-beta.1`.
- [x] Record blocker when package API or manifest inspection fails because current token lacks `read:packages`.
- [ ] After a token with `read:packages` / `write:packages` is available, set package visibility to public if package settings are private.

## Task 2: Performance Baseline Stack

**Files:**
- Update: GitHub issue `#150`
- Update: `docker-compose.yml`
- Update: `scripts/perf-ci/run-perf-regression.sh`
- Update: `scripts/perf-ci/baseline/*.json`
- Update: `tests/load/k6/*.js`

- [x] Probe existing local AuraBoot backend containers.
- [x] Reject existing backend if admin login is not valid for the public benchmark credentials.
- [x] Start an isolated Docker Compose stack with `AURABOOT_PORT=18300` and `POSTGRES_PORT=15432`.
- [x] Fix public quickstart plugin mounting so built-in OSS plugins import in Docker.
- [x] Make k6 defaults work against a clean public quickstart stack.
- [x] Wait for frontend/BFF login and authenticated endpoints.
- [x] Run `scripts/perf-ci/run-perf-regression.sh --profile smoke` against `http://localhost:18300`.
- [x] Capture pass outcome and p95 summary.
- [x] Tear down the isolated Docker Compose stack.

## Task 3: Tracker Updates

**Files:**
- Update: GitHub issue `#147`
- Update: GitHub issue `#150`

- [x] Comment GHCR scope blocker on #147.
- [x] Comment k6 smoke baseline outcome on #150.
- [x] Comment Docker quickstart reproducibility fix on #152.
- [x] Keep #150 open until stable baseline JSON numbers are captured from an agreed reference machine.

## Task 4: Repository Change

**Files:**
- Create: `docs/superpowers/plans/2026-05-11-release-health-baselines.md`

- [x] Add this task list to the repo for auditability.
- [x] Run shell/JSON validation for changed performance scripts and baselines.
- [ ] Commit and push the branch with quickstart and performance baseline fixes.
