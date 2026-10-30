---
type: system-reference
status: active
---

# Dependabot security updates and override pins

This repo pins known-vulnerable transitive dependencies with `overrides` and expects Dependabot to keep everything else current. When a new advisory lands on a pinned package, Dependabot cannot lift the pin by itself and its security-update job on `main` fails on every re-scan. This document records how the mechanisms work, the failure-mode fingerprints, and the playbook for lifting a stale pin. Worked examples: #2411, #2417, #2419 (all merged 2026-10-25, which took open alerts 51 → 0).

## What runs where

- Security alerts and security-update jobs run for every detected manifest **regardless of `.github/dependabot.yml`** — that file only governs the scheduled *version* updates. Security jobs appear in the Actions list as `dynamic` runs on the default branch; a failing one is a red run on `main`.
- Three lockfiles are in play: `pnpm-lock.yaml` (workspace root), `plugins/cli/package-lock.json` (standalone `npm ci` inside the Frontend CI "CLI capability and MCP regression" step), and `airflow-provider/poetry.lock`.
- Every push to `main` refreshes the dependency graph; Dependabot re-evaluates all alerts a few minutes later. This is the moment stale pins resurface.

## Where a dependency is declared decides the fix

| Declaration | Who can fix it | Fix |
| --- | --- | --- |
| Direct range in a manifest (`web-admin`, `plugins/cli`, `packages/*`) | Dependabot (opens a PR) | Bump the range floor to the fixed version |
| Transitive pinned in root `pnpm.overrides` (single version line) | Dependabot (it edits the override, e.g. the ip-address PR) or manual | Raise the pin |
| Transitive pinned via a **selector** override (`"body-parser@1"`, `"undici@7"`) | **Manual only** — Dependabot aborts with `dependency_file_not_resolvable` | Raise each selector line |
| Transitive pinned in `plugins/cli` `overrides` (npm manifest) | **Manual only** — Dependabot reports `security_update_not_possible` | Raise the pin |
| Transitive with no pin anywhere | Manual once (add a pin) or accept Dependabot's PR | Add/raise pin at fixed version |

## Failure-mode fingerprints

| Log signature | Cause | Remedy |
| --- | --- | --- |
| `security_update_not_possible` (with `conflicting-dependencies` naming an override holder) | A pin holds the package below `fixed_in` | Raise the pin |
| `dependency_file_not_resolvable: pnpm resolved X to <newer>, above the requested <older>` | Multi-major selector pins; Dependabot bumps one line and pnpm resolves the other to the newer one | Raise both selector lines manually |
| `illformed_requirement ["<X \|\| >X"]` (pip) | A `pyproject.toml` constraint spelled with Poetry's `\|\|`, which Dependabot's pip parser rejects | Rewrite in PEP 440 form (`!=`), keeping identical semantics |
| Dependabot opens a normal PR | The alert is self-serviceable | Review and merge it; it lives on its own branch, not on `main` |

## Playbook: lifting a stale pin

1. Read the alert's `fixed_in` version:

   ```bash
   gh api "repos/AuraBootTeam/auraboot/dependabot/alerts?state=open" \
     --jq '.[] | "\(.security_vulnerability.package.name)\t\(.dependency.manifest_path)\t\(.security_vulnerability.first_patched_version.identifier)"'
   ```

2. Choose the target: **latest same-major, at least `fixed_in`**. Never cross a major inside a security lift — that is a dedicated migration. Check the consumer's declared range (e.g. `npm view <consumer>@<version> dependencies`) to confirm the target satisfies it.
3. Edit the pin: `package.json` `pnpm.overrides`, `plugins/cli/package.json` `overrides`, or the owning manifest's direct range. Do not delete a pin without an owner decision — pins are deliberate remediation (see #2179).
4. Regenerate the locks:

   ```bash
   pnpm install --lockfile-only                      # workspace root
   (cd plugins/cli && npm install --package-lock-only --ignore-scripts --no-audit --no-fund)
   (cd airflow-provider && poetry update <pkgs>)     # pip alerts, targeted
   ```

   Expect npm to also refresh the moved package's subtree within its declared ranges; `poetry update` refreshes the target's own transitives. Anything beyond that in the diff is a finding.
5. Audit the lock diffs so only the intended packages moved.
6. Run the validation battery (match the surface you touched): `pnpm install --frozen-lockfile`; `web-admin` `pnpm test:unit` (plus the full production build when the vite/build chain moved); `plugins/cli` `npm ci --ignore-scripts --no-audit --no-fund && npm run build && npm test`; `airflow-provider` `poetry install && poetry run pytest` (install the project itself first or pytest dies at collection with `No module named airflow_provider_auraboot`).
7. Open the PR. After merge, the next push refreshes the graph, Dependabot re-sweeps (its security-update jobs should all succeed), alerts close, and superseded Dependabot PRs close automatically. Confirm all three before declaring the incident closed.
