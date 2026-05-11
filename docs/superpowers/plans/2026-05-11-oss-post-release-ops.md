# OSS Post-Release Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the published `v0.1.0-beta.1` beta into an operationally safe public project with protected main, security defaults, community triage, follow-up issues, and release automation backlog.

**Architecture:** Keep repository settings changes in GitHub, and keep durable project/process changes in versioned docs and issues. Code changes after release must land through PRs from worktrees; canonical `/Users/ghj/work/auraboot/auraboot` stays on `main` and is not edited directly.

**Tech Stack:** GitHub CLI/API, GitHub branch protection, GitHub Issues/Labels/Discussions, GitHub Actions, Markdown docs, existing AuraBoot CI workflows.

---

## Task List

### Task 1: Release Governance Tracker

**Files:**
- Create: `docs/superpowers/plans/2026-05-11-oss-post-release-ops.md`
- GitHub: create one pinned/tracked issue for post-release operations

- [x] **Step 1: Write this task list**

Create this plan file with the execution checklist and verification commands.

- [ ] **Step 2: Create GitHub tracker issue**

Run:

```bash
gh issue create \
  --repo AuraBootTeam/auraboot \
  --title "Post-release operations tracker for v0.1.0-beta.1" \
  --label "release,ops" \
  --body-file /tmp/auraboot-post-release-tracker.md
```

Expected: an issue URL.

### Task 2: Main Branch Protection

**GitHub settings:**
- Protect branch: `main`
- Require PR before merge
- Require status checks:
  - `Build & Quality Gate (Java 21)`
  - `docker compose --profile full + smoke`
  - `Analyze java-kotlin`
  - `Analyze javascript-typescript`
  - `Documentation Quality Gate`
  - `Verify OSS does not reference enterprise code`
  - `Gitleaks secret scan`
- Require branch up to date before merge
- Enforce admins

- [ ] **Step 1: Apply branch protection**

Run:

```bash
gh api -X PUT repos/AuraBootTeam/auraboot/branches/main/protection --input /tmp/auraboot-main-protection.json
```

Expected: protection JSON with `required_status_checks.strict=true`.

- [ ] **Step 2: Verify protection**

Run:

```bash
gh api repos/AuraBootTeam/auraboot/branches/main/protection --jq '.required_status_checks.contexts'
```

Expected: the required check list above.

### Task 3: Repository Security Defaults

**GitHub settings:**
- Enable `delete_branch_on_merge`
- Enable Dependabot security updates if supported
- Enable secret scanning and push protection if available for this repository plan
- Keep Issues and Discussions enabled

- [ ] **Step 1: Patch repo settings**

Run:

```bash
gh api -X PATCH repos/AuraBootTeam/auraboot --input /tmp/auraboot-repo-security.json
```

Expected: settings update succeeds. If GitHub rejects unavailable security features, record the rejection in the tracker issue.

- [ ] **Step 2: Verify settings**

Run:

```bash
gh api repos/AuraBootTeam/auraboot --jq '{delete_branch_on_merge, has_discussions, security_and_analysis}'
```

Expected: `delete_branch_on_merge=true`, `has_discussions=true`, and enabled security settings where GitHub allows them.

### Task 4: Community Labels and Issue Taxonomy

**GitHub labels:**
- `release`
- `ops`
- `security`
- `license`
- `plugin`
- `frontend`
- `backend`
- `docs`
- `ci`
- `known-issue`
- `good first issue`
- `help wanted`

- [ ] **Step 1: Create or update labels**

Run a label upsert script that calls `gh label create` and falls back to `gh label edit`.

Expected: all labels exist with stable colors and descriptions.

- [ ] **Step 2: Verify labels**

Run:

```bash
gh label list --repo AuraBootTeam/auraboot --limit 200
```

Expected: all labels above are present.

### Task 5: Known Issue Backlog

**GitHub issues to create:**
- Mobile UI feature coverage trails desktop
- Page Designer UX polish for beta.2
- Public performance benchmark suite
- Gitee mirror retry/backoff hardening
- Multi-arch image build time reduction
- Release notes automation
- License/README source-available wording audit

- [ ] **Step 1: Create issues**

Run `gh issue create` once per issue with labels from Task 4.

Expected: issue URLs for every backlog item.

- [ ] **Step 2: Link issues from release tracker**

Edit the tracker issue body/comment with the created issue URLs.

### Task 6: Community Discussion Entry Points

**GitHub Discussions:**
- Create a welcome/general feedback discussion.
- Create a beta feedback discussion for `v0.1.0-beta.1`.

- [ ] **Step 1: Check discussion categories**

Run:

```bash
gh api graphql -f query='query($owner:String!, $name:String!) { repository(owner:$owner, name:$name) { discussionCategories(first:20) { nodes { id name slug } } } }' -f owner=AuraBootTeam -f name=auraboot
```

Expected: category IDs.

- [ ] **Step 2: Create discussions**

Use GitHub GraphQL `createDiscussion` with the selected category.

Expected: discussion URLs.

### Task 7: Documentation Follow-Up PR

**Files:**
- Modify: `README.md`
- Modify: `docs/getting-started/quick-start.md`
- Modify: `docs/deployment/docker.md`
- Modify: `LICENSE-FAQ-en.md`
- Modify: `LICENSE-FAQ.md`

- [ ] **Step 1: Create a worktree**

Run:

```bash
git worktree add -b codex/oss-post-release-docs /Users/ghj/work/auraboot/.worktrees/oss-post-release-docs origin/main
```

Expected: isolated worktree on `codex/oss-post-release-docs`.

- [ ] **Step 2: Update release/version wording**

Update docs to reference `v0.1.0-beta.1`, GHCR image tags, and source-available wording.

- [ ] **Step 3: Verify docs**

Run:

```bash
bash scripts/check-docs.sh --strict
git diff --check
```

Expected: both pass.

- [ ] **Step 4: Open PR**

Run:

```bash
gh pr create --repo AuraBootTeam/auraboot --base main --head codex/oss-post-release-docs --title "docs: align public beta install and license wording" --body-file /tmp/auraboot-post-release-docs-pr.md
```

Expected: PR URL.

### Task 8: CI/Release Automation PR

**Files:**
- Modify: `.github/workflows/gitee-mirror.yml`
- Create or modify: `.github/workflows/release-notes.yml`
- Modify: `.github/workflows/build-image.yml`

- [ ] **Step 1: Add retry/backoff to Gitee mirror**

Wrap Gitee pushes in a retry helper that retries transient `SSL connection timeout` failures three times with exponential backoff.

- [ ] **Step 2: Add release notes automation**

Create a workflow that validates `CHANGELOG.md` and can seed GitHub Release notes from the changelog section.

- [ ] **Step 3: Reduce multi-arch image build time**

Audit whether release tag builds can use cache more effectively and whether `main` image builds should skip on docs-only changes.

- [ ] **Step 4: Verify workflow syntax**

Run:

```bash
ruby -e 'require "yaml"; Dir[".github/workflows/*.yml"].each { |f| YAML.load_file(f) }'
git diff --check
```

Expected: YAML parse succeeds and diff has no whitespace errors.

### Task 9: Final Release Audit

**Checks:**
- Release exists and is prerelease.
- Tag points at intended commit.
- GHCR tags exist.
- Gitee mirror last run succeeds.
- Main branch protection exists.
- Tracker issue links all follow-up work.

- [ ] **Step 1: Run release audit commands**

Run:

```bash
gh release view v0.1.0-beta.1 --repo AuraBootTeam/auraboot --json url,tagName,isPrerelease,isDraft,publishedAt
git ls-remote --tags origin 'v0.1.0-beta.1*'
gh run list --repo AuraBootTeam/auraboot --branch main --limit 10
gh api repos/AuraBootTeam/auraboot/branches/main/protection --jq '.required_status_checks.contexts'
```

Expected: all release-critical checks green or explicitly tracked.

---

## Execution Status

- In progress: Task 1, Task 2, Task 3, Task 4, Task 5.
- Deferred to PRs: Task 7, Task 8.
- Final audit runs after settings and issue creation.
