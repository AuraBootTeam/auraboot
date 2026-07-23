---
name: auraboot-dsl-gitops
description: Use only when the user explicitly wants a GitOps / version-controlled DSL workflow — comparing local plugin config against a live instance and publishing changes deliberately. This is the advanced, explicit path; ordinary "create a page/model" requests use the data-modeling / ui-builder skills instead.
---

# AuraBoot DSL GitOps (advanced)

Manage an AuraBoot instance's config as version-controlled DSL. **Use this only when the user explicitly asks for YAML/DSL/GitOps/diff/publish** — otherwise author directly with `auraboot-data-modeling` / `auraboot-ui-builder`.

## Before you start

```bash
aura status && aura doctor
aura plugin --help
```

## Inspect desired vs live

```bash
aura dsl status              # local plugin health: counts, score, orphans, issues
aura dsl diagnose            # full diagnostic (14 checks)
aura plugin diff .           # local config vs remote instance state
```

## Validate, then publish deliberately

```bash
aura plugin validate . --agent-mode     # fix every error first
aura plugin build .                      # package to a single JSON bundle
aura plugin publish . --yes              # push to the instance
```

Always `diff` and `validate` before `publish`. Treat a destructive change (deleting a model, changing permissions) as high-risk: review the diff, and expect the platform to route it through the approval gate.

> The full desired-state reconciler (`aura dsl plan / apply / reconcile / drift` with a persisted state file and risk levels) is the target end-state for this skill; until it lands, `plugin diff` + `validate` + `publish` is the interim GitOps loop.
